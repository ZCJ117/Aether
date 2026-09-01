package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
import cn.zcj.aether.domain.agent.service.agent.core.*;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import cn.zcj.aether.domain.agent.service.agent.middleware.MiddlewareChain;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall;
import cn.zcj.aether.domain.agent.observability.ModelCallObservability;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.model.failover.ResilientChatModelExecutor;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 标准 ReAct（Reasoning + Acting）Agent 实现。
 * 迁移自 AgentRuntime.queryLoop()，但现在是 Agent 的自有行为。
 */
@Slf4j
public class ReActAgent extends BaseAgent {

    private static final int MAX_TURNS = 100;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel chatModel;
    private final ModelInvoker modelInvoker;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final AgentEventPublisher eventPublisher;  // P0-6: 可选，无 Bean 时为 null

    /** P0-#8: 检查点收集器（可选注入，无 Bean 时为 null） */
    private final CheckpointCollector checkpointCollector;

    /** Phase 9: Token 预算 */
    private final TokenBudget tokenBudget;
    /** M7: 模型定价注册表（成本熔断基础） */
    private final cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry pricingRegistry;
    /** Phase 9: 策展管道 */
    private final cn.zcj.aether.domain.agent.service.curation.CurationPipeline curationPipeline;
    /** Phase 9: 外部笔记 */
    private final cn.zcj.aether.domain.agent.service.notes.ExternalNotes externalNotes;

    // ── D3 API 请求生命周期钩子（对齐 hermes pre_api_request / post_api_request / api_request_error）──
    /** 全局生命周期钩子分发器（由 DefaultAgentFactory 注入；未注入则跳过） */
    private HookRegistry hookRegistry;

    /** 注入全局生命周期钩子注册表（由 DefaultAgentFactory 在构造后调用） */
    public void setHookRegistry(HookRegistry registry) {
        this.hookRegistry = registry;
    }

    /** P1(1.1): 模型调用等待可观测（gauge + 超时计数；由 DefaultAgentFactory 注入，未注入则 no-op） */
    private ModelCallObservability modelCallObservability;

    /** P1(1.1): 注入模型等待可观测（DefaultAgentFactory 构造后调用）。 */
    public void setModelCallObservability(ModelCallObservability observability) {
        this.modelCallObservability = observability;
    }

    private Instant startTime;

    public ReActAgent(AgentConfig config,
                      ChatModel chatModel,
                      ModelInvoker modelInvoker,
                      ToolExecutor toolExecutor,
                      ContextManager contextManager,
                      AgentEventPublisher eventPublisher,
                      CheckpointCollector checkpointCollector,
                      TokenBudget tokenBudget,
                      cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry pricingRegistry,
                      cn.zcj.aether.domain.agent.service.curation.CurationPipeline curationPipeline,
                      cn.zcj.aether.domain.agent.service.notes.ExternalNotes externalNotes) {
        super(config);
        this.chatModel = chatModel;
        this.modelInvoker = modelInvoker;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.eventPublisher = eventPublisher;
        this.checkpointCollector = checkpointCollector;
        this.tokenBudget = tokenBudget;
        this.pricingRegistry = pricingRegistry;
        this.curationPipeline = curationPipeline;
        this.externalNotes = externalNotes;
    }

    @Override
    public Flowable<RuntimeEvent> execute(RuntimeContext ctx) {
        return Flowable.create(emitter -> {
            startTime = Instant.now();
            try {
                // P1 容错：将 AgentState 注入 ResilientChatModelExecutor（供 fallback 冷却使用）
                wireResilientExecutor();

                // H4-步骤5: 检测挂起恢复
                if (state.getStatus() == AgentState.AgentStatus.PAUSED) {
                    checkAndResumeIfPaused(ctx);
                }

                // 生命周期：before
                onBeforeExecute(ctx);
                state.setStatus(AgentState.AgentStatus.RUNNING);

                // ====== P1-2: 构建中间件链 ======
                MiddlewareChain chain = new MiddlewareChain(this, ctx);
                for (AgentHook hook : hooks) {
                    if (hook instanceof AgentMiddleware mw) {
                        chain.use(mw);
                    }
                }

                // ====== P1-2: 应用系统提示词变换 ======
                String enrichedInstruction = chain.applySystemPrompt(config.getInstruction());

                // ==== 初始化对话 ====
                List<TurnMessage> messages = state.messagesMutable();
                if (ctx.initialMessage() != null && !ctx.initialMessage().isEmpty()) {
                    messages.add(TurnMessage.user(ctx.initialMessage()));
                }

                // ==== 主循环（原 AgentRuntime.queryLoop 逻辑） ====
                queryLoop(emitter, ctx, enrichedInstruction, chain);

                // 生命周期：after
                long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
                AgentResult result = AgentResult.success(getId(), "done", state.getCurrentTurn(), durationMs);
                state.setStatus(AgentState.AgentStatus.IDLE);
                onAfterExecute(ctx, result);

            } catch (Exception e) {
                log.error("Agent [{}] execute failed", getId(), e);
                state.setStatus(AgentState.AgentStatus.ERROR);
                onError(ctx, e);
                if (!emitter.isCancelled()) {
                    emitter.onError(e);
                }
            }
        }, BackpressureStrategy.BUFFER);
    }

    @SuppressWarnings("unchecked")
    private void queryLoop(FlowableEmitter<RuntimeEvent> emitter, RuntimeContext ctx,
            String enrichedInstruction, MiddlewareChain chain) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        // O5: 持有进行中的模型流订阅，取消时 dispose 即时停止上游（取消传播到模型流）
        AtomicReference<reactor.core.Disposable> activeModelSub = new AtomicReference<>();
        emitter.setCancellable(() -> {
            aborted.set(true);
            // O5: 取消同时置位中断信号，传播到后续检查点（InterruptControl 取消传播）
            state.interruptControl().interrupt();
            disposeActiveModelSub(activeModelSub);
        });

        // H5: 执行开始时清除上轮中断信号（对齐 AgentScope 模式：reset 在 execute 起点，检查在每轮循环）
        state.interruptControl().reset();

        int consecutiveToolFailures = 0;

        while (state.getCurrentTurn() < MAX_TURNS && !config.getCancelToken().isCancelled()
                && !aborted.get() && !state.interruptControl().isInterrupted()) {
            state.incrementTurn();

            // H5-步骤5: 每轮清空快照去重集合（对齐 hermes new_turn() L737-739）
            toolExecutor.clearSnapshotTracking();

            Instant turnStart = Instant.now();

            // P0-6: 发布 TurnStarted 事件
            if (eventPublisher != null) {
                eventPublisher.publishTurnStarted(getId(),
                        ctx.sessionId(), ctx.correlationId(), state.getCurrentTurn());
            }

            // ====== Phase 1: Context Management ======
            List<TurnMessage> messages = state.messagesMutable();

            // P2-2: 消息修剪——超过500条保留最近200条（下沉至 ContextManager，含配对对齐 + 占位消息）
            if (messages.size() > 500) {
                contextManager.trimMessages(messages, 200);
            }

            messages = contextManager.applyToolResultBudget(messages);
            messages = contextManager.microCompact(messages);

            var compactResult = contextManager.autoCompactIfNeeded(messages, config.getModelRef(), ctx.sessionId(), tokenBudget);
            if (compactResult.isCompacted()) {
                List<TurnMessage> compacted = (List<TurnMessage>) compactResult.getCompressedMessages();
                messages.clear();
                messages.addAll(compacted);
                emitter.onNext(RuntimeEvent.builder()
                        .type(RuntimeEvent.EventType.compactBoundary)
                        .compactSummary(compactResult.getSummary())
                        .build());

                // C2: 发射内部 LLM 调用事件（context compaction）
                if (compactResult.getInternalLlmCallEvent() != null) {
                    emitter.onNext(compactResult.getInternalLlmCallEvent());
                }
            }

            // Phase 9: Pipe compaction (六步压缩管道)
            if (!compactResult.isCompacted() && externalNotes != null) {
                var pipeResult = contextManager.runCompactionPipeline(messages, config.getModelRef(), ctx.sessionId(), state.getCurrentTurn(), tokenBudget);
                if (pipeResult.compacted()) {
                    messages.clear();
                    messages.addAll(pipeResult.messages());
                    String noteBlock = externalNotes.buildSummaryBlock(ctx.sessionId());
                    if (!noteBlock.isEmpty()) messages.add(TurnMessage.user(noteBlock));
                    emitter.onNext(RuntimeEvent.builder().type(RuntimeEvent.EventType.compactBoundary).compactSummary(pipeResult.summary()).build());
                }
            }

            // 将裁剪/紧凑后的消息同步回 state，确保后续 assistant/tool 消息写入持久化列表
            state.messagesMutable().clear();
            state.messagesMutable().addAll(messages);
            messages = state.messagesMutable();

            // ====== Phase 2: Model Call ======
            List<Message> springMessages = convertToSpringMessages(messages);

            // P1-2: 通过中间件链处理推理消息
            List<Message> enrichedMessages = chain.applyReasoning(springMessages);

            // 在模型调用前发送 turnStarted，避免前端长时间空白
            emitter.onNext(RuntimeEvent.turnStarted(state.getCurrentTurn()));

            // P1-6 + O15: Hook - before model call（经 HookRegistry 单一命名空间分发）
            long modelStart = System.currentTimeMillis();
            fireBeforeModelCall(ctx, state.getCurrentTurn());

            // D3: PRE_API_REQUEST（对齐 hermes pre_api_request）
            if (hookRegistry != null) {
                hookRegistry.invokeAll(HookPoint.PRE_API_REQUEST, HookContext.builder()
                        .agentId(getId()).sessionId(ctx.sessionId())
                        .turnNumber(state.getCurrentTurn())
                        .request(enrichedInstruction != null
                                ? enrichedInstruction.substring(0, Math.min(300, enrichedInstruction.length()))
                                : null)
                        .build());
            }

            // P1-#2 + P1-#1 + O5: 真流式/缓冲路径按 aether.model.invoker.true-streaming 切换
            var modelResult = chain.applyModelCall(
                () -> invokeModel(enrichedMessages, enrichedInstruction, emitter, aborted, activeModelSub),
                config.getModelRef());
            long modelDuration = System.currentTimeMillis() - modelStart;

            // D3: POST_API_REQUEST / API_REQUEST_ERROR（对齐 hermes post_api_request / api_request_error）
            // ModelInvoker 吞异常返回 error 结果 → hasError() 等价异常回调
            if (hookRegistry != null) {
                if (modelResult.hasError()) {
                    hookRegistry.invokeAll(HookPoint.API_REQUEST_ERROR, HookContext.builder()
                            .agentId(getId()).sessionId(ctx.sessionId())
                            .turnNumber(state.getCurrentTurn())
                            .error(modelResult.getError())
                            .durationMs(modelDuration).build());
                } else {
                    hookRegistry.invokeAll(HookPoint.POST_API_REQUEST, HookContext.builder()
                            .agentId(getId()).sessionId(ctx.sessionId())
                            .turnNumber(state.getCurrentTurn())
                            .response(modelResult.getFullText())
                            .durationMs(modelDuration).build());
                }
            }

            // P1-6 + O15: Hook - after model call（经 HookRegistry 单一命名空间分发）
            fireAfterModelCall(ctx, modelResult, modelDuration);

            if (modelResult.hasError()) {
                emitter.onNext(RuntimeEvent.error(modelResult.getError()));
                break;
            }

            // ====== M7: 成本跟踪与熔断检查 ======
            if (pricingRegistry != null && tokenBudget != null) {
                // 设置每轮成本上限（从 AgentConfig 读取）
                if (config.getMaxCostUsd() != null && config.getMaxCostUsd() > 0) {
                    tokenBudget.setMaxCostUsd(config.getMaxCostUsd());
                }
                var pricing = pricingRegistry.lookup(config.getModelRef());
                tokenBudget.accumulateCost(
                        modelResult.getInputTokens(),
                        modelResult.getOutputTokens(),
                        pricing);
                if (!tokenBudget.isWithinBudget()) {
                    log.warn("Agent [{}] 成本超限 ${}，触发熔断",
                            getId(), String.format("%.4f", tokenBudget.getTotalCostUsd()));
                    emitter.onNext(RuntimeEvent.costExceeded(
                            tokenBudget.getTotalCostUsd(), tokenBudget.getMaxCostUsd()));
                    emitter.onComplete();
                    return;
                }
            }

            // 转发模型事件
            if (modelResult.getEvents() != null) {
                modelResult.getEvents().forEach(emitter::onNext);
            }

            // 存储 assistant 消息
            if (!modelResult.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tcMeta = modelResult.getToolCalls().stream()
                        .map(tc -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", tc.getId());
                            m.put("name", tc.getName());
                            m.put("input", tc.getInput());
                            return m;
                        })
                        .toList();
                messages.add(TurnMessage.assistantWithToolCalls(
                        modelResult.getFullText(), tcMeta));
            } else if (modelResult.getFullText() != null && !modelResult.getFullText().isEmpty()) {
                messages.add(TurnMessage.assistant(modelResult.getFullText()));
            }

            // ====== Phase 3: Exit Check ======
            if (modelResult.getToolCalls().isEmpty()) {
                emitter.onNext(RuntimeEvent.done());
                emitter.onComplete();
                return;
            }

            // ====== Phase 4: Tool Execution ======
            List<ToolExecutor.ToolCallRequest> requests = modelResult.getToolCalls().stream()
                .map(tc -> new ToolExecutor.ToolCallRequest(tc.getId(), tc.getName(), tc.getInput()))
                .toList();

            // P1-6 + O15: Hook - before tool call（经 HookRegistry 单一命名空间分发）
            long toolStart = System.currentTimeMillis();
            fireBeforeToolCall(ctx, requests);

            // P1-2: 通过中间件链过滤/检查工具调用
            List<ToolExecutor.ToolCallRequest> filteredRequests = chain.applyActing(requests);

            // ====== O10: 工具环护栏 —— 连续失败超限的工具拒绝执行（结果仍回注以保持 tool_call 配对）======
            List<ToolExecutor.ToolCallRequest> guardPassed = new ArrayList<>();
            List<ToolResult> guardBlocked = new ArrayList<>();
            for (ToolExecutor.ToolCallRequest req : filteredRequests) {
                int failCount = getToolFailCount(req.toolName());
                if (failCount >= TOOL_GUARDRAIL_FAILURE_LIMIT) {
                    log.warn("Agent [{}] 工具 [{}] 已连续失败 {} 次，护栏熔断跳过执行",
                            getId(), req.toolName(), failCount);
                    guardBlocked.add(ToolResult.error(req.toolCallId(), req.toolName(),
                            "[系统护栏] 工具 '" + req.toolName() + "' 已连续失败 " + failCount
                                    + " 次，已被熔断跳过执行。请放弃该工具调用路径，改用其他方式完成任务。",
                            ToolResult.ErrorType.GUARDRAIL));
                } else {
                    guardPassed.add(req);
                }
            }

            String userId = ctx.userId() != null ? ctx.userId() : "system";
            String sessionId = ctx.sessionId() != null ? ctx.sessionId() : "session";

            List<ToolResult> results = new ArrayList<>(guardBlocked);
            if (!guardPassed.isEmpty()) {
                results.addAll(toolExecutor.executeBatch(guardPassed, userId, sessionId));
            }

            long toolDuration = System.currentTimeMillis() - toolStart;

            // P1-6 + O15: Hook - after tool call（经 HookRegistry 单一命名空间分发）
            fireAfterToolCall(ctx, results, toolDuration);

            // Phase 9: Token budget event
            if (tokenBudget != null) {
                emitter.onNext(RuntimeEvent.tokenBudget(tokenBudget.getCurrentElasticUsage(), tokenBudget.getElasticBudget()));
            }

            boolean allFailed = true;
            for (ToolResult result : results) {
                // O10: per-tool 失败计数（GUARDRAIL 拦截结果为护栏自身产生，不再累计）；
                // 成功即复位，保持"连续失败"语义
                if (result.isError() && result.getErrorType() != ToolResult.ErrorType.GUARDRAIL) {
                    incrementToolFailCount(result.getToolName());
                } else if (!result.isError()) {
                    resetToolFailCount(result.getToolName());
                }

                // P0-1: 同一 toolCallId 连续 VALIDATION 失败计数（防死循环，对齐 crewAI _max_parsing_attempts=3）
                if (result.isError() && result.getErrorType() == ToolResult.ErrorType.VALIDATION) {
                    String counterKey = "valFailCount:" + result.getToolCallId();
                    int valFailCount = state.getAttribute(counterKey) instanceof Integer i
                            ? i.intValue() + 1 : 1;
                    state.setAttribute(counterKey, valFailCount);
                    if (valFailCount >= 3) {
                        log.warn("Agent [{}] toolCallId [{}] 连续 {} 次 VALIDATION 失败，标记为终态错误",
                                getId(), result.getToolCallId(), valFailCount);
                        String terminalMsg = result.getContent()
                                + "\n\n[系统提示] 该工具已连续 " + valFailCount
                                + " 次参数校验失败，请放弃此工具调用路径，改用其他方式完成任务。";
                        emitter.onNext(RuntimeEvent.builder()
                                .type(RuntimeEvent.EventType.toolResult)
                                .toolCallId(result.getToolCallId())
                                .toolName(result.getToolName())
                                .toolOutput(terminalMsg)
                                .toolError(true)
                                .build());
                        messages.add(TurnMessage.toolResult(
                                result.getToolCallId(), result.getToolName(), terminalMsg));
                        allFailed = false; // 不计入 allFailed（已明确告知 LLM 放弃）
                        continue;
                    }
                } else if (!result.isError()) {
                    // P0-1: 成功的工具调用清除该 toolCallId 的 VALIDATION 计数器
                    String counterKey = "valFailCount:" + result.getToolCallId();
                    state.setAttribute(counterKey, 0);
                }

                // Phase 9: 策展管道处理工具结果
                String curatedContent = result.getContent();
                if (curationPipeline != null && tokenBudget != null) {
                    var curated = curationPipeline.curate(result.getContent(), result.getToolName(),
                            tokenBudget.remainingElastic() / Math.max(1, results.size()));
                    curatedContent = curated.summary();
                }

                emitter.onNext(RuntimeEvent.builder()
                        .type(RuntimeEvent.EventType.toolResult)
                        .toolCallId(result.getToolCallId())
                        .toolName(result.getToolName())
                        .toolOutput(curatedContent)
                        .toolError(result.isError())
                        .build());
                messages.add(TurnMessage.toolResult(
                        result.getToolCallId(), result.getToolName(), curatedContent));
                if (!result.isError()) allFailed = false;
            }

            consecutiveToolFailures = allFailed ? consecutiveToolFailures + 1 : 0;
            if (consecutiveToolFailures >= 3) {
                log.warn("Agent [{}] 连续 3 轮工具调用全部失败，强制退出", getId());
                if (eventPublisher != null) {
                    eventPublisher.publishError(getId(), ctx.sessionId(), ctx.correlationId(),
                            "ConsecutiveToolFailures", "连续三轮工具调用失败", state.getCurrentTurn());
                }
                emitter.onNext(RuntimeEvent.error("连续三轮工具调用失败"));
                emitter.onComplete();
                return;
            }

            // P0-6: 发布 TurnCompleted 事件
            if (eventPublisher != null) {
                boolean hasToolCalls = !modelResult.getToolCalls().isEmpty();
                long turnDurationMs = Duration.between(turnStart, Instant.now()).toMillis();
                eventPublisher.publishTurnCompleted(getId(), ctx.sessionId(), ctx.correlationId(),
                        state.getCurrentTurn(), hasToolCalls,
                        results.size(), turnDurationMs);
            }

            // ====== H4-步骤4: 检测挂起的工具调用 ======
            if (state.hasPendingAsking()) {
                handlePermissionSuspend(ctx, emitter);
                return; // 终止本轮执行流，等待用户确认后恢复
            }

            // P0-#8: 每 N 轮自动保存检查点（借鉴 CrewAI 多粒度检查点 + cc-haha WAL 日志模式）
            if (config.isCheckpointEnabled()
                    && state.getCurrentTurn() % config.getCheckpointInterval() == 0) {
                saveCheckpoint(ctx, emitter, state.getCurrentTurn());
            }

            emitter.onNext(RuntimeEvent.builder()
                    .type(RuntimeEvent.EventType.turnComplete)
                    .turnCount(state.getCurrentTurn())
                    .build());
        }

        // 达到最大轮次
        if (state.getCurrentTurn() >= MAX_TURNS) {
            emitter.onNext(RuntimeEvent.builder()
                    .type(RuntimeEvent.EventType.maxTurnsReached)
                    .build());
        }
        emitter.onComplete();
    }

    // ============== 以下方法从原 AgentRuntime 迁移 ==============

    /**
     * O5: 模型调用入口 —— 按灰度开关 {@code aether.model.invoker.true-streaming} 选择路径。
     *
     * <p>true（默认）→ 真流式：textDelta 经 emitter 边收边发，取消可 dispose 模型流；
     * false（回滚）→ 旧缓冲路径：带缓存异步 + block 超时预算收敛。
     */
    private ModelInvoker.ModelCallResult invokeModel(List<Message> messages, String instruction,
            FlowableEmitter<RuntimeEvent> emitter, AtomicBoolean aborted,
            AtomicReference<reactor.core.Disposable> activeModelSub) {
        if (modelInvoker.isTrueStreaming()) {
            return invokeModelStreaming(messages, instruction, emitter, aborted, activeModelSub);
        }

        // ---- 旧缓冲路径（true-streaming=false 灰度回滚，保留 callWithStreamCachedAsync）----
        try {
            return modelInvoker.callWithStreamCachedAsync(chatModel,
                    messages, instruction, config.getModelRef(),
                    config.isCacheEnabled(), config.getCacheTtlSeconds())
                    // P0-2: 与 ModelInvoker 同源 aether.model.invoker.call-timeout-ms
                    .block(java.time.Duration.ofMillis(modelInvoker.getCallTimeoutMs()));
        } catch (Exception e) {
            if (isBlockTimeout(e)) {
                // P0-2 预算收敛：block 超时即预算耗尽，不再回退同步
                log.warn("异步缓存调用超时（预算耗尽，不回退同步）: {}", e.getMessage());
                return ModelInvoker.ModelCallResult.error(
                        e.getMessage() != null ? e.getMessage() : "模型调用超时");
            }
            log.warn("异步缓存调用失败，回退同步: {}", e.getMessage());
            return modelInvoker.callWithStreamCached(chatModel, messages,
                    instruction, config.getModelRef(),
                    config.isCacheEnabled(), config.getCacheTtlSeconds());
        }
    }

    /**
     * O5 真流式模型调用：订阅 {@link ModelInvoker#callWithStreamingAsync}，
     * textDelta 实时下推 emitter；latch 等待完成（预算 = call-timeout-ms）。
     *
     * <p>取消语义：emitter 取消 / 中断 → dispose 订阅 → Reactor 立即停止上游模型流。
     */
    private ModelInvoker.ModelCallResult invokeModelStreaming(List<Message> messages, String instruction,
            FlowableEmitter<RuntimeEvent> emitter, AtomicBoolean aborted,
            AtomicReference<reactor.core.Disposable> activeModelSub) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<ModelInvoker.ModelCallResult> okRef = new AtomicReference<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        try {
            activeModelSub.set(modelInvoker
                    .callWithStreamingAsync(chatModel, messages, instruction, config.getModelRef(),
                            delta -> {
                                // 边收边发：模型 chunk 一到即下推 SSE（FlowableCreate 内部串行化，跨线程安全）
                                // O5: 中断信号置位后停止下推（外部 interrupt() 可即时停止流输出）
                                if (!aborted.get() && !emitter.isCancelled()
                                        && !state.interruptControl().isInterrupted()) {
                                    emitter.onNext(delta);
                                }
                            })
                    .doOnCancel(latch::countDown)
                    .subscribe(okRef::set, errRef::set, latch::countDown));

            // P1(1.1): latch 等待可观测 —— 记录当前阻塞等待的调用线程数（容量规划）
            ModelCallObservability.WaitHandle wait =
                    modelCallObservability == null ? null : modelCallObservability.beginWait();
            try {
                boolean finished = latch.await(modelInvoker.getCallTimeoutMs(),
                        TimeUnit.MILLISECONDS);
                if (!finished) {
                    disposeActiveModelSub(activeModelSub);
                    if (modelCallObservability != null) {
                        modelCallObservability.recordTimeout();
                    }
                    log.warn("真流式模型调用超时（预算耗尽）: turn={}", state.getCurrentTurn());
                    return ModelInvoker.ModelCallResult.error("模型调用超时");
                }
            } finally {
                if (wait != null) {
                    wait.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposeActiveModelSub(activeModelSub);
            return ModelInvoker.ModelCallResult.error("模型调用被中断");
        }

        if (errRef.get() != null) {
            Throwable e = errRef.get();
            log.warn("真流式模型调用失败: {}", e.getMessage());
            return ModelInvoker.ModelCallResult.error(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        if (okRef.get() == null) {
            // doOnCancel 计数（外部取消 dispose）且无错误完成 → 已取消
            return ModelInvoker.ModelCallResult.error("模型流已取消");
        }
        return okRef.get();
    }

    private void disposeActiveModelSub(AtomicReference<reactor.core.Disposable> activeModelSub) {
        reactor.core.Disposable sub = activeModelSub.getAndSet(null);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
        }
    }

    // ============== O10: per-tool 失败护栏 ==============

    /** O10: per-tool 连续失败熔断阈值（默认 3，对齐 hermes tool_guardrails 保守取值）。 */
    private static final int TOOL_GUARDRAIL_FAILURE_LIMIT = 3;

    private static final String TOOL_FAIL_COUNT_PREFIX = "toolFailCount:";

    // ====== O15: LLM/工具挂点统一经 HookRegistry 分发（无注册表时降级为直接回调）======

    private void fireBeforeModelCall(RuntimeContext ctx, int turnNumber) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(HookPoint.PRE_LLM_CALL, HookContext.builder()
                    .agent(this).runtimeCtx(ctx).agentId(getId()).sessionId(ctx.sessionId())
                    .turnNumber(turnNumber).build());
        } else {
            for (AgentHook hook : hooks) hook.onBeforeModelCall(this, ctx, turnNumber);
        }
    }

    private void fireAfterModelCall(RuntimeContext ctx,
            ModelInvoker.ModelCallResult result, long durationMs) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(HookPoint.POST_LLM_CALL, HookContext.builder()
                    .agent(this).runtimeCtx(ctx).agentId(getId()).sessionId(ctx.sessionId())
                    .modelCallResult(result).durationMs(durationMs).build());
        } else {
            for (AgentHook hook : hooks) hook.onAfterModelCall(this, ctx, result, durationMs);
        }
    }

    private void fireBeforeToolCall(RuntimeContext ctx, List<ToolExecutor.ToolCallRequest> requests) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(HookPoint.PRE_TOOL_CALL, HookContext.builder()
                    .agent(this).runtimeCtx(ctx).agentId(getId()).sessionId(ctx.sessionId())
                    .toolRequests(requests).build());
        } else {
            for (AgentHook hook : hooks) hook.onBeforeToolCall(this, ctx, requests);
        }
    }

    private void fireAfterToolCall(RuntimeContext ctx, List<ToolResult> results, long durationMs) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(HookPoint.POST_TOOL_CALL, HookContext.builder()
                    .agent(this).runtimeCtx(ctx).agentId(getId()).sessionId(ctx.sessionId())
                    .toolResults(results).durationMs(durationMs).build());
        } else {
            for (AgentHook hook : hooks) hook.onAfterToolCall(this, ctx, results, durationMs);
        }
    }

    private int getToolFailCount(String toolName) {
        if (toolName == null) return 0;
        Object v = state.getAttribute(TOOL_FAIL_COUNT_PREFIX + toolName);
        return v instanceof Integer i ? i : 0;
    }

    private void incrementToolFailCount(String toolName) {
        if (toolName == null) return;
        state.setAttribute(TOOL_FAIL_COUNT_PREFIX + toolName, getToolFailCount(toolName) + 1);
    }

    private void resetToolFailCount(String toolName) {
        if (toolName == null) return;
        state.setAttribute(TOOL_FAIL_COUNT_PREFIX + toolName, 0);
    }

    /**
     * 将内部 TurnMessage 转换为 Spring AI Message。
     * 原 AgentRuntime.convertToSpringMessages() 逻辑完全相同。
     */
    private List<Message> convertToSpringMessages(List<TurnMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (TurnMessage tm : messages) {
            switch (tm.role()) {
                case "user" -> result.add(new UserMessage(tm.content()));
                case "assistant" -> {
                    if (tm.hasToolCalls()) {
                        List<AssistantMessage.ToolCall> toolCalls = tm.toolCalls().stream()
                            .map(tc -> new AssistantMessage.ToolCall(
                                (String) tc.get("id"), "function",
                                (String) tc.get("name"), toJsonString(tc.get("input"))))
                            .toList();
                        result.add(new AssistantMessage(tm.content(), Map.of(), toolCalls));
                    } else {
                        result.add(new AssistantMessage(tm.content()));
                    }
                }
                case "tool_result" -> result.add(new ToolResponseMessage(
                    List.of(new ToolResponseMessage.ToolResponse(
                        tm.toolCallId(), tm.toolName(), tm.content() != null ? tm.content() : "")),
                    Map.of()));
                default -> {
                    if (tm.isToolResult()) {
                        String responseText = tm.content() != null ? tm.content() : "";
                        result.add(new ToolResponseMessage(
                                List.of(new ToolResponseMessage.ToolResponse(
                                        tm.toolCallId(), tm.toolName(), responseText)),
                                Map.of()));
                    } else {
                        result.add(new UserMessage(
                                tm.content() != null ? tm.content() : ""));
                    }
                }
            }
        }
        return result;
    }

    private String toJsonString(Object input) {
        if (input == null) return "{}";
        if (input instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    /**
     * P1 容错：将当前 AgentState 注入 ResilientChatModelExecutor。
     *
     * <p>如果 chatModel 是 {@link ResilientChatModelExecutor} 实例，
     * 则设置其 AgentState 引用，使 fallback 冷却时间等状态在主循环轮回间持久化。</p>
     */
    private void wireResilientExecutor() {
        if (chatModel instanceof ResilientChatModelExecutor executor) {
            executor.setAgentState(state);
        }
    }

    /**
     * P0-2：判断异常是否为 {@code Mono.block(Duration)} 超时。
     * Reactor 超时抛 {@code IllegalStateException("Timeout on blocking read...")}；
     * 递归 cause 链兼容包装（如 Reactor Exceptions 包装）。
     */
    private static boolean isBlockTimeout(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.util.concurrent.TimeoutException) return true;
            if (cur instanceof IllegalStateException && cur.getMessage() != null
                    && cur.getMessage().contains("Timeout on blocking read")) return true;
            cur = cur.getCause();
        }
        return false;
    }

    // ============== H4: 权限挂起/恢复协议 ==============

    /**
     * H4-步骤4: 处理权限挂起 —— 发出 RequireUserConfirmEvent，
     * 将 Agent 置为 PAUSED 状态，持久化，终止本轮执行流。
     *
     * <p>对齐 AgentScope ReActAgent L2302-2322 的 ASK 事件发出 + RequestStopEvent 语义。
     */
    private void handlePermissionSuspend(RuntimeContext ctx, FlowableEmitter<RuntimeEvent> emitter) {
        List<SuspendedToolCall> asking = state.getAsking().stream()
                .filter(s -> s.state() == SuspendedToolCall.SuspendedState.ASKING)
                .toList();

        if (asking.isEmpty()) return;

        String replyId = UUID.randomUUID().toString().substring(0, 8);
        String pendingJson;
        try {
            pendingJson = objectMapper.writeValueAsString(asking);
        } catch (Exception e) {
            pendingJson = "[]";
        }

        log.info("Agent [{}] 暂停等待用户确认: replyId={}, pendingCount={}, tools={}",
                getId(), replyId, asking.size(),
                asking.stream().map(SuspendedToolCall::toolName).collect(Collectors.joining(",")));

        // 发出 permission_asking SSE 事件
        emitter.onNext(RuntimeEvent.permissionAsking(replyId, pendingJson));
        emitter.onNext(RuntimeEvent.agentPaused("等待用户确认 " + asking.size() + " 个工具调用"));

        // 发布领域事件
        if (eventPublisher != null) {
            eventPublisher.publishPermissionAsking(getId(), ctx.sessionId(), ctx.correlationId(),
                    replyId, asking.size(),
                    asking.stream().map(SuspendedToolCall::toolName).collect(Collectors.joining(",")));
        }

        // 置为 PAUSED 状态
        state.setStatus(AgentState.AgentStatus.PAUSED);

        // 持久化挂起状态
        persistState(ctx);

        emitter.onComplete();
    }

    /**
     * H4-步骤5: 恢复分派点 —— 检测挂起状态并应用用户确认结果。
     *
     * <p>对齐 AgentScope ReActAgent L1435-1470 的恢复协议。
     * 调用时机：在 execute() 入口、每次新消息到来时检测。
     *
     * @param ctx 运行时上下文（需包含 confirmResults 元数据）
     * @throws IllegalStateException 如果 Agent 处于 PAUSED 状态但无确认回执
     */
    private void checkAndResumeIfPaused(RuntimeContext ctx) {
        if (!state.hasPendingAsking()) return;

        // 从 metadata 提取确认回执
        @SuppressWarnings("unchecked")
        List<ConfirmResult> confirmResults = ctx.metadata() != null
                ? (List<ConfirmResult>) ctx.metadata().get("confirmResults")
                : null;

        if (confirmResults == null || confirmResults.isEmpty()) {
            throw new IllegalStateException(
                    "Agent [%s] is paused for human-in-the-loop confirmation. "
                            .formatted(getId())
                            + "Please provide confirmResults in the request metadata.");
        }

        applyConfirmResults(confirmResults, ctx);
    }

    /**
     * H4-步骤5: 应用用户确认结果。
     *
     * <p>批准项 → withState(ALLOWED) 并执行；
     * 拒绝项 → 写入 DENIED 的 ToolResult 消息让 Agent 知晓。
     */
    private void applyConfirmResults(List<ConfirmResult> confirmResults, RuntimeContext ctx) {
        List<SuspendedToolCall> asking = state.askingMutable();
        java.util.concurrent.atomic.AtomicInteger approved = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger denied = new java.util.concurrent.atomic.AtomicInteger(0);

        for (ConfirmResult cr : confirmResults) {
            // 找到匹配的挂起工具调用
            asking.stream()
                    .filter(s -> s.toolCallId().equals(cr.toolCallId())
                            && s.state() == SuspendedToolCall.SuspendedState.ASKING)
                    .findFirst()
                    .ifPresentOrElse(suspended -> {
                        int idx = asking.indexOf(suspended);
                        if (cr.approved()) {
                            // 批准：标记为 ALLOWED 并立即执行
                            asking.set(idx, suspended.withState(SuspendedToolCall.SuspendedState.ALLOWED));

                            // 执行工具调用
                            ToolExecutor.ToolCallRequest req = new ToolExecutor.ToolCallRequest(
                                    suspended.toolCallId(), suspended.toolName(), suspended.input());
                            List<ToolResult> results = toolExecutor.executeBatch(
                                    List.of(req), ctx.userId(), ctx.sessionId());

                            for (ToolResult result : results) {
                                String content = result.getContent();
                                state.messagesMutable().add(TurnMessage.toolResult(
                                        result.getToolCallId(), result.getToolName(), content));
                            }
                            approved.incrementAndGet();
                            log.info("用户批准工具调用: toolCallId={}, toolName={}",
                                    suspended.toolCallId(), suspended.toolName());
                        } else {
                            // 拒绝：标记为 DENIED，写入拒绝消息
                            asking.set(idx, suspended.withState(SuspendedToolCall.SuspendedState.DENIED));
                            String denyMsg = "[用户已拒绝] 工具 [" + suspended.toolName()
                                    + "] 的调用已被用户拒绝。";
                            state.messagesMutable().add(TurnMessage.toolResult(
                                    suspended.toolCallId(), suspended.toolName(), denyMsg));
                            denied.incrementAndGet();
                            log.info("用户拒绝工具调用: toolCallId={}, toolName={}",
                                    suspended.toolCallId(), suspended.toolName());
                        }
                    }, () -> log.warn("未找到匹配的挂起工具调用: toolCallId={}", cr.toolCallId()));
        }

        int approvedCount = approved.get();
        int deniedCount = denied.get();

        // 发布领域事件
        if (eventPublisher != null) {
            eventPublisher.publishPermissionResolved(getId(), ctx.sessionId(), ctx.correlationId(),
                    "resume-" + UUID.randomUUID().toString().substring(0, 8), approvedCount, deniedCount);
        }

        // 清空挂起列表
        state.clearAsking();

        // 恢复运行状态
        state.setStatus(AgentState.AgentStatus.RUNNING);
        log.info("Agent [{}] 权限确认完成，恢复运行: approved={}, denied={}", getId(), approvedCount, deniedCount);
    }

    /**
     * 持久化当前 Agent 状态到 SessionRepository。
     */
    private void persistState(RuntimeContext ctx) {
        // 持久化依赖外部 SessionRepository，通过 ChatService 的会话持久化机制完成。
        // 此处通过 saveState() 序列化，由调用方（ChatService）负责写入存储。
        // ReActAgent 自身不持有 SessionRepository 引用（保持 DDD 分层约束）。
        Map<String, Object> stateJson = saveState();
        log.debug("Agent [{}] 状态已序列化待持久化: sessionId={}, askingCount={}",
                getId(), ctx.sessionId(),
                state.getAsking().size());
    }

    /**
     * P0-#8: 保存检查点快照。
     * 通过 FileCheckpointCollector 写入 .claude/checkpoints/ 目录（文件快照），
     * 同时通过 AgentEventPublisher 写入结构化 JSON 日志（WAL），
     * 并发射 checkpoint SSE 事件供前端展示检查点标记。
     *
     * 异常不阻断主循环——检查点保存是尽力而为的。
     */
    private void saveCheckpoint(RuntimeContext ctx,
            FlowableEmitter<RuntimeEvent> emitter, int turnNumber) {
        try {
            java.util.Map<String, Object> stateJson = saveState();
            CheckpointData ckpt = CheckpointData.create(
                    ctx.sessionId(), getId(), turnNumber,
                    stateJson, state.messagesMutable().size());

            // 文件快照
            if (checkpointCollector != null) {
                checkpointCollector.save(ckpt);
            }

            // WAL 日志（结构化事件）
            if (eventPublisher != null) {
                eventPublisher.publishCheckpoint(getId(), ctx.sessionId(),
                        ctx.correlationId(), turnNumber, ckpt.getMessageCount());
            }

            // SSE 事件（前端展示）
            emitter.onNext(RuntimeEvent.checkpoint(ctx.sessionId(), turnNumber));

            log.debug("检查点已保存: agentId={}, sessionId={}, turn={}, messages={}",
                    getId(), ctx.sessionId(), turnNumber, ckpt.getMessageCount());
        } catch (Exception e) {
            log.warn("检查点保存失败（不阻断主循环）: agentId={}, sessionId={}, turn={}",
                    getId(), ctx.sessionId(), turnNumber, e);
        }
    }
}

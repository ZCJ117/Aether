package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.*;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import cn.zcj.aether.domain.agent.service.agent.middleware.MiddlewareChain;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    private Instant startTime;

    public ReActAgent(AgentConfig config,
                      ChatModel chatModel,
                      ModelInvoker modelInvoker,
                      ToolExecutor toolExecutor,
                      ContextManager contextManager,
                      AgentEventPublisher eventPublisher) {
        super(config);
        this.chatModel = chatModel;
        this.modelInvoker = modelInvoker;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Flowable<RuntimeEvent> execute(RuntimeContext ctx) {
        return Flowable.create(emitter -> {
            startTime = Instant.now();
            try {
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
        emitter.setCancellable(() -> aborted.set(true));

        int consecutiveToolFailures = 0;

        while (state.getCurrentTurn() < MAX_TURNS && !aborted.get()) {
            state.incrementTurn();
            Instant turnStart = Instant.now();

            // P0-6: 发布 TurnStarted 事件
            if (eventPublisher != null) {
                eventPublisher.publishTurnStarted(getId(),
                        ctx.sessionId(), ctx.correlationId(), state.getCurrentTurn());
            }

            // ====== Phase 1: Context Management ======
            List<TurnMessage> messages = state.messagesMutable();

            // P0-6: 消息修剪——超过500条保留最近200条
            if (messages.size() > 500) {
                int keepRecent = 200;
                List<TurnMessage> trimmed = new ArrayList<>();
                if (!messages.isEmpty()) {
                    trimmed.add(messages.get(0)); // 保留第一条（通常是 system initial）
                }
                int fromIndex = Math.max(1, messages.size() - keepRecent);
                if (fromIndex < messages.size()) {
                    trimmed.addAll(messages.subList(fromIndex, messages.size()));
                }
                messages.clear();
                messages.addAll(trimmed);
                log.info("会话消息已修剪: agentId={}, 保留 {} 条", getId(), trimmed.size());
            }

            messages = contextManager.applyToolResultBudget(messages);
            messages = contextManager.microCompact(messages);

            var compactResult = contextManager.autoCompactIfNeeded(messages, config.getModelRef());
            if (compactResult.isCompacted()) {
                List<TurnMessage> compacted = (List<TurnMessage>) compactResult.getCompressedMessages();
                messages.clear();
                messages.addAll(compacted);
                emitter.onNext(RuntimeEvent.builder()
                        .type(RuntimeEvent.EventType.compactBoundary)
                        .compactSummary(compactResult.getSummary())
                        .build());
            }

            // ====== Phase 2: Model Call ======
            List<Message> springMessages = convertToSpringMessages(messages);

            // P1-2: 通过中间件链处理推理消息
            List<Message> enrichedMessages = chain.applyReasoning(springMessages);

            // P1-6: Hook - before model call
            long modelStart = System.currentTimeMillis();
            for (AgentHook hook : hooks) {
                hook.onBeforeModelCall(this, ctx, state.getCurrentTurn());
            }

            var modelResult = chain.applyModelCall(
                () -> modelInvoker.callWithStream(chatModel, enrichedMessages,
                    enrichedInstruction, config.getModelRef()),
                config.getModelRef());
            long modelDuration = System.currentTimeMillis() - modelStart;

            // P1-6: Hook - after model call
            for (AgentHook hook : hooks) {
                hook.onAfterModelCall(this, ctx, modelResult, modelDuration);
            }

            if (modelResult.hasError()) {
                emitter.onNext(RuntimeEvent.error(modelResult.getError()));
                break;
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

            // P1-6: Hook - before tool call
            long toolStart = System.currentTimeMillis();
            for (AgentHook hook : hooks) {
                hook.onBeforeToolCall(this, ctx, requests);
            }

            // P1-2: 通过中间件链过滤/检查工具调用
            List<ToolExecutor.ToolCallRequest> filteredRequests = chain.applyActing(requests);

            String userId = ctx.userId() != null ? ctx.userId() : "system";
            String sessionId = ctx.sessionId() != null ? ctx.sessionId() : "session";

            List<ToolResult> results = toolExecutor.executeBatch(filteredRequests, userId, sessionId);

            long toolDuration = System.currentTimeMillis() - toolStart;

            // P1-6: Hook - after tool call
            for (AgentHook hook : hooks) {
                hook.onAfterToolCall(this, ctx, results, toolDuration);
            }

            boolean allFailed = true;
            for (ToolResult result : results) {
                emitter.onNext(RuntimeEvent.builder()
                        .type(RuntimeEvent.EventType.toolResult)
                        .toolCallId(result.getToolCallId())
                        .toolName(result.getToolName())
                        .toolOutput(result.getContent())
                        .toolError(result.isError())
                        .build());
                messages.add(TurnMessage.toolResult(
                        result.getToolCallId(), result.getToolName(), result.getContent()));
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
}

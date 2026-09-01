package cn.zcj.aether.eval;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.agent.middleware.impl.PermissionMiddleware;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.SummaryChatModelResolver;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.context.ModelContextWindowRegistry;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0(4.1) Eval 引擎 —— 四类用例的确定性执行核（真实 ReActAgent + 真实 ToolExecutor/ToolRegistry +
 * 脚本化 ModelInvoker；权限/压缩走真实 PermissionEngine / ContextManager 管道）。
 */
public class EvalEngines {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String EVAL_MODEL = "eval-p0-model";

    /** 单 case 结果。 */
    public record CaseOutcome(String id, String category, boolean pass, long durationMs,
                              long inputTokens, long outputTokens, String detail) {
    }

    // ============ tool_selection ============

    public static CaseOutcome runToolSelection(EvalCase c, boolean realMode) {
        long start = System.currentTimeMillis();
        try {
            List<ModelInvoker.ModelCallResult> script;
            ChatModel chatModel = null;
            ModelInvoker invoker;
            if (realMode) {
                chatModel = RealLlmSupport.buildChatModel(c);
                invoker = RealLlmSupport.newRealInvoker();
                script = List.of(); // 真实模式不脚本化，由模型自主决策
            } else {
                String chosen = ToolRouter.select(c.userMessage, c.tools);
                if (chosen == null) {
                    return fail(c, start, "路由器无法判定工具（零重叠）：" + ToolRouter.scores(c.userMessage, c.tools));
                }
                script = List.of(
                        toolCallResult(toolCall("call-1", chosen, Map.of())),
                        finalText("已根据工具 " + chosen + " 的结果回答用户。"));
                invoker = scriptedInvoker(script);
                chatModel = mock(ChatModel.class);
            }
            List<RuntimeEvent> events = runAgent(c, chatModel, invoker, false, null);
            List<String> executed = toolSequence(events);
            boolean pass = !executed.isEmpty() && executed.get(0).equals(c.expectedTool);
            long[] tokens = sumTokens(events);
            return new CaseOutcome(c.id, c.category, pass, System.currentTimeMillis() - start,
                    tokens[0], tokens[1],
                    pass ? "selected=" + executed.get(0)
                         : "expected first tool " + c.expectedTool + " but got " + executed
                                 + " scores=" + ToolRouter.scores(c.userMessage, c.tools));
        } catch (Exception e) {
            return fail(c, start, "exception: " + e);
        }
    }

    // ============ multi_step ============

    public static CaseOutcome runMultiStep(EvalCase c, boolean realMode) {
        long start = System.currentTimeMillis();
        try {
            ChatModel chatModel;
            ModelInvoker invoker;
            if (realMode) {
                chatModel = RealLlmSupport.buildChatModel(c);
                invoker = RealLlmSupport.newRealInvoker();
            } else {
                List<ModelInvoker.ModelCallResult> script = new ArrayList<>();
                for (EvalCase.StepDef step : c.steps) {
                    if (step.toolCall != null) {
                        Map<String, Object> input = step.toolCall.input == null ? Map.of() : step.toolCall.input;
                        script.add(toolCallResult(toolCall("call-" + script.size(), step.toolCall.name, input)));
                    } else {
                        script.add(finalText(step.text));
                    }
                }
                invoker = scriptedInvoker(script);
                chatModel = mock(ChatModel.class);
            }
            List<RuntimeEvent> events = runAgent(c, chatModel, invoker, false, null);
            List<String> executed = toolSequence(events);
            StringBuilder failReason = new StringBuilder();
            if (c.expectedToolSequence != null && !executed.equals(c.expectedToolSequence)) {
                failReason.append("tool sequence ").append(executed).append(" != ").append(c.expectedToolSequence).append("; ");
            }
            String finalText = joinedText(events);
            for (String frag : orEmpty(c.finalTextContains)) {
                if (!finalText.contains(frag)) {
                    failReason.append("final text missing [").append(frag).append("]; ");
                }
            }
            if (!events.stream().anyMatch(e -> e.getType() == RuntimeEvent.EventType.done)) {
                failReason.append("no done event; ");
            }
            long[] tokens = sumTokens(events);
            boolean pass = failReason.length() == 0;
            return new CaseOutcome(c.id, c.category, pass, System.currentTimeMillis() - start,
                    tokens[0], tokens[1], pass ? "sequence=" + executed : failReason.toString());
        } catch (Exception e) {
            return fail(c, start, "exception: " + e);
        }
    }

    // ============ permission ============

    public static CaseOutcome runPermission(EvalCase c) {
        long start = System.currentTimeMillis();
        try {
            if (!c.flow) {
                PermissionEngine engine = new PermissionEngine(new DangerousToolRule());
                PermissionContext ctx = PermissionContext.builder()
                        .toolName(c.toolName)
                        .toolCallId("eval-" + c.id)
                        .toolInput(c.toolInput == null ? Map.of() : c.toolInput)
                        .agentId("eval-agent")
                        .userId("eval-user")
                        .sessionId("eval-session-" + c.id)
                        .isReadOnly(Boolean.TRUE.equals(c.readOnly))
                        .mode(PermissionMode.DEFAULT)
                        .build();
                PermissionDecision decision = engine.check(ctx, PermissionMode.DEFAULT);
                boolean pass = decision.name().equals(c.expectedDecision);
                return new CaseOutcome(c.id, c.category, pass, System.currentTimeMillis() - start,
                        0, 0, "decision=" + decision + " expected=" + c.expectedDecision);
            }

            // flow：危险工具 → permissionAsking 挂起 → 拒绝/批准 → 恢复并改道/执行
            List<ModelInvoker.ModelCallResult> script = new ArrayList<>();
            for (EvalCase.StepDef step : c.steps) {
                if (step.toolCall != null) {
                    script.add(toolCallResult(toolCall("call-0", step.toolCall.name,
                            step.toolCall.input == null ? Map.of() : step.toolCall.input)));
                } else {
                    script.add(finalText(step.text));
                }
            }
            ModelInvoker invoker = scriptedInvoker(script);
            // 权限恢复必须在同一 agent 实例上执行（PAUSED 状态在实例内）；
            // 桩工具带执行记录器（批准/拒绝是否真正触达 ToolExecutor 以此为准）
            Map<String, Integer> toolInvocations = new LinkedHashMap<>();
            ReActAgent agent = buildAgent(c, mock(ChatModel.class), invoker, true, toolInvocations);
            List<RuntimeEvent> events = execute(agent, c, null);

            boolean askingSeen = events.stream()
                    .anyMatch(e -> e.getType() == RuntimeEvent.EventType.permissionAsking);
            if (!askingSeen) {
                return fail(c, start, "未触发 permissionAsking（挂起协议失效）");
            }
            String pendingJson = events.stream()
                    .filter(e -> e.getType() == RuntimeEvent.EventType.permissionAsking)
                    .map(RuntimeEvent::getPendingToolCallsJson).findFirst().orElse("[]");
            String toolCallId = JSON.readTree(pendingJson).get(0).get("toolCallId").asText();

            boolean approve = "ALLOW".equals(c.expectedDecision);
            RuntimeContext resumeCtx = new RuntimeContext("eval-user", "eval-session-" + c.id, null, null,
                    null, Map.of("confirmResults",
                            List.of(approve ? ConfirmResult.approve(toolCallId) : ConfirmResult.deny(toolCallId))),
                    null, null);
            // 对齐生产恢复协议（ChatService.handleConfirm）：新建 Agent 实例 + 从挂起快照恢复状态。
            // 生产快照由 persistState 在挂起瞬间落盘（status=PAUSED）；execute 返回后原实例状态已被
            // 收尾置回 IDLE，故此处在序列化前补设 PAUSED 以复刻挂起快照语义。
            agent.getState().setStatus(cn.zcj.aether.domain.agent.service.agent.core.AgentState.AgentStatus.PAUSED);
            Map<String, Object> suspendedSnapshot = agent.saveState();
            ReActAgent resumed = buildAgent(c, mock(ChatModel.class), invoker, true, toolInvocations);
            resumed.loadState(suspendedSnapshot);
            List<RuntimeEvent> resumeEvents = execute(resumed, c, resumeCtx);
            events = new ArrayList<>(events);
            events.addAll(resumeEvents);

            StringBuilder failReason = new StringBuilder();
            boolean doneSeen = events.stream().anyMatch(e -> e.getType() == RuntimeEvent.EventType.done);
            if (!doneSeen) {
                failReason.append("恢复后未正常完成; ");
            }
            String finalText = joinedText(events);
            for (String frag : orEmpty(c.finalTextContains)) {
                if (!finalText.contains(frag)) {
                    failReason.append("final text missing [").append(frag).append("]; ");
                }
            }
            if (approve) {
                if (toolInvocations.getOrDefault(c.toolName, 0) < 1) {
                    failReason.append("批准后工具未执行; ");
                }
            } else {
                if (toolInvocations.getOrDefault(c.toolName, 0) > 0) {
                    failReason.append("拒绝后工具不应执行; ");
                }
            }
            boolean pass = failReason.length() == 0;
            return new CaseOutcome(c.id, c.category, pass, System.currentTimeMillis() - start, 0, 0,
                    pass ? "flow ok (asking→" + (approve ? "approve" : "deny") + "→resume)" : failReason.toString());
        } catch (Exception e) {
            return fail(c, start, "exception: " + e);
        }
    }

    // ============ context_retention ============

    public static CaseOutcome runRetention(EvalCase c) {
        long start = System.currentTimeMillis();
        try {
            ModelContextWindowRegistry windowRegistry = new ModelContextWindowRegistry();
            TokenEstimator tokenEstimator = new TokenEstimator();
            ReflectionTestUtils.setField(tokenEstimator, "windowRegistry", windowRegistry);
            // 窗口 8000 < 20000 预留 → effectiveWindow 为负 → 历史必触发压缩（同 ContextManagerTest 配方）
            windowRegistry.register(EVAL_MODEL, 8_000);

            ModelInvoker summaryInvoker = mock(ModelInvoker.class);
            when(summaryInvoker.callWithStream(any(), anyList(), anyString(), anyString()))
                    .thenReturn(finalText(c.summaryText));

            ContextManager cm = new ContextManager(summaryInvoker, mock(ModelPricingRegistry.class));
            ReflectionTestUtils.setField(cm, "tokenEstimator", tokenEstimator);
            SummaryChatModelResolver resolver = mock(SummaryChatModelResolver.class);
            when(resolver.resolve()).thenReturn(mock(ChatModel.class));
            ReflectionTestUtils.setField(cm, "summaryChatModelResolver", resolver);
            Object trigger = ReflectionTestUtils.getField(cm, "compactionTrigger");
            ReflectionTestUtils.setField(trigger, "minMessagesToCompact", 5);

            List<TurnMessage> msgs = new ArrayList<>();
            for (EvalCase.HistoryMsg h : c.history) {
                msgs.add("assistant".equals(h.role) ? TurnMessage.assistant(h.content) : TurnMessage.user(h.content));
            }
            msgs.add(TurnMessage.user(c.tailMessage));

            AutoCompactResult r = cm.autoCompactIfNeeded(msgs, EVAL_MODEL, "eval-" + c.id, null);

            StringBuilder failReason = new StringBuilder();
            if (r == null || !r.isCompacted()) {
                failReason.append("未触发压缩; ");
            } else {
                @SuppressWarnings("unchecked")
                List<TurnMessage> compacted = (List<TurnMessage>) r.getCompressedMessages();
                String joined = compacted.stream()
                        .map(TurnMessage::content).collect(Collectors.joining("\n"));
                if (!joined.contains(c.keyFact)) {
                    failReason.append("压缩后关键信息丢失 [").append(c.keyFact).append("]; ");
                }
                TurnMessage last = compacted.get(compacted.size() - 1);
                if (!c.tailMessage.equals(last.content())) {
                    failReason.append("尾部保护消息未保留; ");
                }
            }
            boolean pass = failReason.length() == 0;
            String detail = pass
                    ? String.format("tokens %d -> %d (%.0f%%)", r.getPreCompactTokens(), r.getPostCompactTokens(),
                            100.0 * (r.getPreCompactTokens() - r.getPostCompactTokens()) / Math.max(1, r.getPreCompactTokens()))
                    : failReason.toString();
            return new CaseOutcome(c.id, c.category, pass, System.currentTimeMillis() - start,
                    r != null ? r.getPreCompactTokens() : 0, r != null ? r.getPostCompactTokens() : 0, detail);
        } catch (Exception e) {
            return fail(c, start, "exception: " + e);
        }
    }

    // ============ 共用执行核 ============

    /** 构造 ReActAgent（permission=true 时挂真实 PermissionMiddleware；toolInvocations 非空时桩工具记录执行次数）。 */
    static ReActAgent buildAgent(EvalCase c, ChatModel chatModel, ModelInvoker invoker, boolean permission,
                                 Map<String, Integer> toolInvocations) {
        ToolRegistry registry = buildRegistry(c, toolInvocations);
        ToolExecutor executor = new ToolExecutor();
        ReflectionTestUtils.setField(executor, "toolRegistry", registry);

        ContextManager contextManager = mock(ContextManager.class);
        when(contextManager.applyToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.microCompact(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.notNeeded());

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder()
                        .name(c.id).instruction(c.instruction == null ? "你是评测智能体。" : c.instruction)
                        .modelRef(EVAL_MODEL).cancelToken(new CancelToken())
                        .cacheEnabled(false).build(),
                chatModel, invoker, executor, contextManager,
                null, null, null, null, null, null);

        if (permission) {
            // PermissionMiddleware 是 AgentMiddleware 但非 AgentHook——继承出双接口适配器挂入
            // ReActAgent 的 hooks 链（ReActAgent 只把 instanceof AgentMiddleware 的 hook 装进洋葱链）
            PermissionHookAdapter adapter = new PermissionHookAdapter();
            ReflectionTestUtils.setField(adapter, "permissionEngine", new PermissionEngine(new DangerousToolRule()));
            ReflectionTestUtils.setField(adapter, "toolRegistry", registry);
            agent.addHook(adapter);
        }
        return agent;
    }

    /** 继承真实权限中间件并实现 AgentHook，使其可进入 ReActAgent 的中间件洋葱链。 */
    static final class PermissionHookAdapter extends PermissionMiddleware
            implements cn.zcj.aether.domain.agent.service.agent.hook.AgentHook {
    }

    /** 在（可复用的）agent 上执行一轮；resumeCtx 非空为权限恢复调用。 */
    static List<RuntimeEvent> execute(ReActAgent agent, EvalCase c, RuntimeContext resumeCtx) {
        RuntimeContext ctx = resumeCtx != null ? resumeCtx
                : new RuntimeContext("eval-user", "eval-session-" + c.id, null, null,
                        c.userMessage, null, null, null);
        return agent.execute(ctx).toList().blockingGet();
    }

    static List<RuntimeEvent> runAgent(EvalCase c, ChatModel chatModel, ModelInvoker invoker,
                                       boolean permission, RuntimeContext resumeCtx) {
        return execute(buildAgent(c, chatModel, invoker, permission, null), c, resumeCtx);
    }

    static ToolRegistry buildRegistry(EvalCase c, Map<String, Integer> toolInvocations) {
        ToolRegistry registry = new ToolRegistry();
        for (EvalCase.ToolDef def : orEmpty(c.tools)) {
            registry.register(new Tool() {
                @Override public String name() { return def.name; }
                @Override public String description() { return def.description; }
                @Override public Map<String, Object> inputSchema() {
                    return def.inputSchema == null ? Map.of() : def.inputSchema;
                }
                @Override public ToolResult call(Map<String, Object> input, ToolContext context) {
                    if (toolInvocations != null) {
                        synchronized (toolInvocations) {
                            toolInvocations.merge(def.name, 1, Integer::sum);
                        }
                    }
                    return ToolResult.success("stub", def.name,
                            "工具 " + def.name + " 执行成功，输入=" + String.valueOf(input));
                }
                @Override public boolean isReadOnly() { return def.readOnly; }
                @Override public boolean isConcurrencySafe() { return true; }
            });
        }
        return registry;
    }

    /** 脚本化 ModelInvoker：按队列逐轮返回；队列耗尽返回默认收尾文本防止死循环。 */
    static ModelInvoker scriptedInvoker(List<ModelInvoker.ModelCallResult> script) {
        ModelInvoker invoker = mock(ModelInvoker.class);
        ArrayDeque<ModelInvoker.ModelCallResult> queue = new ArrayDeque<>(script);
        when(invoker.isTrueStreaming()).thenReturn(false);
        when(invoker.getCallTimeoutMs()).thenReturn(30_000L);
        when(invoker.callWithStreamCachedAsync(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> reactor.core.publisher.Mono.just(
                        queue.isEmpty() ? finalText("（脚本耗尽，默认收尾）") : queue.poll()));
        return invoker;
    }

    static List<String> toolSequence(List<RuntimeEvent> events) {
        return events.stream()
                .filter(e -> e.getType() == RuntimeEvent.EventType.toolResult)
                .map(RuntimeEvent::getToolName)
                .collect(Collectors.toList());
    }

    static String joinedText(List<RuntimeEvent> events) {
        return events.stream()
                .filter(e -> e.getText() != null)
                .map(RuntimeEvent::getText)
                .collect(Collectors.joining());
    }

    /** 从 internalLlmCall 事件聚合 token 消耗（脚本模式下按脚本轮次计）。 */
    static long[] sumTokens(List<RuntimeEvent> events) {
        long in = 0, out = 0;
        for (RuntimeEvent e : events) {
            if (e.getType() == RuntimeEvent.EventType.toolResult && e.getToolOutput() != null) {
                in += e.getToolOutput().length() / 4;
            }
            if (e.getText() != null) {
                out += e.getText().length() / 4;
            }
        }
        return new long[]{in, out};
    }

    static ModelInvoker.ModelCallResult toolCallResult(ModelInvoker.ToolCallDef call) {
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of()).fullText("调用工具").toolCalls(List.of(call))
                .inputTokens(10).outputTokens(5).build();
    }

    static ModelInvoker.ModelCallResult finalText(String text) {
        // events 携带 text 事件 —— ReActAgent 直接转发 ModelCallResult.events（真实 ModelInvoker 同构）
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of(RuntimeEvent.text(text))).fullText(text).toolCalls(List.of())
                .inputTokens(10).outputTokens(Math.max(1, text.length() / 4)).build();
    }

    static ModelInvoker.ToolCallDef toolCall(String id, String name, Map<String, Object> input) {
        return ModelInvoker.ToolCallDef.builder().id(id).name(name)
                .input(input == null ? Map.of() : new LinkedHashMap<>(input)).build();
    }

    static CaseOutcome fail(EvalCase c, long start, String reason) {
        return new CaseOutcome(c.id, c.category, false, System.currentTimeMillis() - start, 0, 0, reason);
    }

    static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private EvalEngines() {
    }
}

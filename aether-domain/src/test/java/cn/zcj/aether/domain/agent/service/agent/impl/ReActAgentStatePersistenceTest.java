package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H4: 权限挂起 → 持久化 → 恢复 回路测试。
 *
 * <p>核心回归：
 * <ol>
 *   <li>挂起瞬间 {@code persistState} 必须真实落盘（stateJson 中 status=PAUSED 且含挂起工具调用），
 *       而不是序列化后丢弃的空壳；</li>
 *   <li>{@code execute()} 收尾不得把 PAUSED 覆写为 IDLE（覆写会让 ChatService 的恢复协议失效）；</li>
 *   <li>新实例 {@code loadState(挂起快照)} + confirmResults 可恢复正常执行
 *       （对齐 ChatService.handleConfirm 生产协议）。</li>
 * </ol>
 */
class ReActAgentStatePersistenceTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 测试用权限中间件：模拟 PermissionMiddleware 的 ASK_USER 契约——登记挂起并拒绝放行。
     *  生产中间件以 AgentHook Bean 注册、经 ReActAgent 的 instanceof AgentMiddleware 桥入链中，此处保持同构。 */
    private static final class AskingMiddleware implements AgentHook, AgentMiddleware {
        private final String toolCallId;
        private final String toolName;

        private AskingMiddleware(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
        }

        @Override public String name() { return "test-asking"; }
        @Override public int priority() { return 20; }

        @Override
        public List<ToolExecutor.ToolCallRequest> onActing(
                List<ToolExecutor.ToolCallRequest> requests, Agent agent, RuntimeContext ctx) {
            agent.getState().askingMutable().add(new SuspendedToolCall(
                    toolCallId, toolName, Map.of(),
                    "工具 [" + toolName + "] 需要用户确认",
                    SuspendedToolCall.SuspendedState.ASKING));
            return List.of();
        }
    }

    private ReActAgent buildAgent(ModelInvoker modelInvoker, ToolExecutor toolExecutor) {
        ContextManager contextManager = mock(ContextManager.class);
        // 对齐真实 ContextManager 语义：返回新列表（原地返回会触发 queryLoop 的 clear+addAll 自清空）
        when(contextManager.applyToolResultBudget(any()))
                .thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));
        when(contextManager.microCompact(any()))
                .thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.notNeeded());

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder().name("persist-agent").instruction("测试指令")
                        .cancelToken(new CancelToken()).build(),
                mock(ChatModel.class), modelInvoker, toolExecutor, contextManager,
                null, null, null, null, null, null);
        agent.addHook(new AskingMiddleware("call-1", "dangerous_tool"));
        return agent;
    }

    private static ModelInvoker.ModelCallResult toolCallResult() {
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of())
                .fullText("调用工具")
                .toolCalls(List.of(ModelInvoker.ToolCallDef.builder()
                        .id("call-1").name("dangerous_tool").input(Map.of()).build()))
                .inputTokens(10)
                .outputTokens(5)
                .build();
    }

    private static ModelInvoker.ModelCallResult finalTextResult() {
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of())
                .fullText("任务完成")
                .toolCalls(List.of())
                .inputTokens(10)
                .outputTokens(5)
                .build();
    }

    @Test
    void suspendPersistsPausedSnapshotAndKeepsPausedStatus() throws Exception {
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.isTrueStreaming()).thenReturn(false);
        when(modelInvoker.callWithStreamCachedAsync(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Mono.just(toolCallResult()));

        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(sessionRepository.save(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ReActAgent agent = buildAgent(modelInvoker, toolExecutor);
        agent.setSessionRepository(sessionRepository);

        RuntimeContext ctx = new RuntimeContext("user1", "session1", null, null, "开始任务", null, null);
        List<RuntimeEvent> events = agent.execute(ctx).toList().blockingGet();

        assertTrue(events.stream()
                        .anyMatch(e -> e.getType() == RuntimeEvent.EventType.permissionAsking),
                "挂起协议应发出 permissionAsking 事件");

        // 状态保护：execute 收尾不得把 PAUSED 覆写为 IDLE
        assertEquals(AgentState.AgentStatus.PAUSED, agent.getState().getStatus(),
                "挂起后 Agent 状态必须保持 PAUSED");

        // 真实持久化：挂起快照必须落盘且携带 PAUSED + 挂起工具调用
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, times(1)).save(captor.capture());
        SessionEntity entity = captor.getValue();
        assertEquals("session1", entity.getSessionId());
        assertEquals("ACTIVE", entity.getStatus());

        Map<String, Object> saved = objectMapper.readValue(entity.getStateJson(), Map.class);
        assertEquals("PAUSED", saved.get("status"), "挂起快照的 stateJson 必须携带 PAUSED");
        List<?> permissionContext = (List<?>) saved.get("permissionContext");
        assertTrue(permissionContext != null && !permissionContext.isEmpty(),
                "挂起快照必须包含 permissionContext（挂起的工具调用）");
    }

    @Test
    void resumeFromSuspendedSnapshotExecutesApprovedTool() throws Exception {
        // ── 第一步：挂起并落盘（JSON 往返模拟 SessionRepository 存取格式）──
        ModelInvoker suspendInvoker = mock(ModelInvoker.class);
        when(suspendInvoker.isTrueStreaming()).thenReturn(false);
        when(suspendInvoker.callWithStreamCachedAsync(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Mono.just(toolCallResult()));
        ToolExecutor suspendExecutor = mock(ToolExecutor.class);

        ReActAgent suspended = buildAgent(suspendInvoker, suspendExecutor);
        RuntimeContext suspendCtx =
                new RuntimeContext("user1", "session1", null, null, "开始任务", null, null);
        suspended.execute(suspendCtx).toList().blockingGet();
        assertEquals(AgentState.AgentStatus.PAUSED, suspended.getState().getStatus());

        // JSON 往返（对齐 SessionService.restoreSession 的 readValue 反序列化路径）
        String json = objectMapper.writeValueAsString(suspended.saveState());
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = objectMapper.readValue(json, Map.class);

        // ── 第二步：新实例恢复 + confirmResults 恢复执行（对齐 ChatService.handleConfirm）──
        ModelInvoker resumeInvoker = mock(ModelInvoker.class);
        when(resumeInvoker.isTrueStreaming()).thenReturn(false);
        when(resumeInvoker.callWithStreamCachedAsync(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Mono.just(finalTextResult()));

        ToolExecutor resumeExecutor = mock(ToolExecutor.class);
        when(resumeExecutor.executeBatch(anyList(), any(ToolContext.class)))
                .thenAnswer(inv -> {
                    List<ToolExecutor.ToolCallRequest> reqs = inv.getArgument(0);
                    return reqs.stream()
                            .map(r -> ToolResult.success(r.toolCallId(), r.toolName(), "ok"))
                            .toList();
                });

        ReActAgent resumed = buildAgent(resumeInvoker, resumeExecutor);
        resumed.loadState(snapshot);
        assertEquals(AgentState.AgentStatus.PAUSED, resumed.getState().getStatus(),
                "恢复实例应从快照读取 PAUSED 状态");

        RuntimeContext resumeCtx = new RuntimeContext("user1", "session1", null, null,
                "[用户已提交工具调用确认]",
                Map.of("confirmResults", List.of(ConfirmResult.approve("call-1"))), null);
        List<RuntimeEvent> resumeEvents = resumed.execute(resumeCtx).toList().blockingGet();

        // applyConfirmResults 的契约：批准的工具真实触达 ToolExecutor，
        // 结果作为 tool_result 消息写回对话历史（配对），恢复路径不重发 toolResult SSE 事件
        verify(resumeExecutor, times(1)).executeBatch(anyList(), any(ToolContext.class));
        assertTrue(resumed.getState().messagesMutable().stream().anyMatch(m ->
                        "tool_result".equals(m.role()) && "call-1".equals(m.toolCallId())
                                && "ok".equals(m.content())),
                "批准的工具调用结果应写回对话历史");
        assertTrue(resumeEvents.stream()
                        .anyMatch(e -> e.getType() == RuntimeEvent.EventType.done),
                "恢复后应正常完成执行");
        assertEquals(AgentState.AgentStatus.IDLE, resumed.getState().getStatus(),
                "正常完成后状态应回到 IDLE");
    }
}

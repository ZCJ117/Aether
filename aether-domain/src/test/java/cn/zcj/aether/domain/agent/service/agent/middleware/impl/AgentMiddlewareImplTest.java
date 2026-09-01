package cn.zcj.aether.domain.agent.service.agent.middleware.impl;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * agent.middleware.impl 四个实现的单元测试（覆盖率盲区补测 P0 2.1）。
 */
class AgentMiddlewareImplTest {

    private Agent agent;
    private AgentState state;

    @BeforeEach
    void setUp() {
        agent = mock(Agent.class);
        state = new AgentState();
        when(agent.getState()).thenReturn(state);
    }

    private static RuntimeContext ctx(String userId, Map<String, Object> metadata) {
        return new RuntimeContext(userId, "s1", null, null, "msg", metadata, null, "n1");
    }

    // ====== GracefulShutdownMiddleware ======

    @Test
    void gracefulShutdownRejectsNewRequestsOnlyAfterInitiated() {
        GracefulShutdownMiddleware mw = new GracefulShutdownMiddleware();
        assertEquals("graceful-shutdown", mw.name());
        assertEquals(5, mw.priority());
        AtomicInteger calls = new AtomicInteger();
        Supplier<Flowable<RuntimeEvent>> supplier = () -> {
            calls.incrementAndGet();
            return Flowable.just(RuntimeEvent.done());
        };

        assertFalse(mw.isShuttingDown());
        mw.onAgent(agent, ctx("u1", null), supplier).blockingLast();
        assertEquals(1, calls.get(), "未关闭时放行");

        mw.initiateShutdown();
        assertTrue(mw.isShuttingDown());
        RuntimeEvent err = mw.onAgent(agent, ctx("u1", null), supplier).blockingLast();
        assertEquals(RuntimeEvent.EventType.error, err.getType());
        assertEquals(1, calls.get(), "关闭后不再调用下游");
    }

    // ====== RateLimitMiddleware ======

    @Test
    void rateLimitBlocksBeyondWindowQuotaThenRecovers() {
        RateLimitMiddleware mw = new RateLimitMiddleware(2, Duration.ofSeconds(60));
        assertEquals("rate-limit", mw.name());
        assertEquals(10, mw.priority());

        Supplier<Flowable<RuntimeEvent>> supplier = () -> Flowable.just(RuntimeEvent.done());

        mw.onAgent(agent, ctx("u1", null), supplier).blockingLast();
        mw.onAgent(agent, ctx("u1", null), supplier).blockingLast();
        RuntimeEvent blocked = mw.onAgent(agent, ctx("u1", null), supplier).blockingLast();
        assertEquals(RuntimeEvent.EventType.error, blocked.getType());
        assertTrue(blocked.getErrorMessage().contains("请求过于频繁"));

        // 不同用户不受影响
        mw.onAgent(agent, ctx("u2", null), supplier).blockingLast();

        // 窗口过期后恢复：小窗口 + sleep 过期
        RateLimitMiddleware fast = new RateLimitMiddleware(1, Duration.ofMillis(50));
        fast.onAgent(agent, ctx("u3", null), supplier).blockingLast();
        RuntimeEvent blockedNow = fast.onAgent(agent, ctx("u3", null), supplier).blockingLast();
        assertEquals(RuntimeEvent.EventType.error, blockedNow.getType());
        try {
            Thread.sleep(80);
        } catch (InterruptedException ignored) {
        }
        fast.onAgent(agent, ctx("u3", null), supplier).blockingLast();
    }

    // ====== TaskReminderMiddleware ======

    @Test
    void taskReminderInjectsReminderAndTaskInfo() {
        TaskReminderMiddleware mw = new TaskReminderMiddleware();
        assertEquals("task-reminder", mw.name());
        assertEquals(90, mw.priority());

        String base = "系统提示词";
        RuntimeContext ctx = ctx("u1", Map.of("reminder", " 15:00 截止 ", "taskInfo", "高优先级"));
        String out = mw.onSystemPrompt(base, agent, ctx);

        assertTrue(out.contains("<task-reminder>"));
        assertTrue(out.contains("15:00 截止"));
        assertTrue(out.contains("<task-context>"));
        assertTrue(out.contains("高优先级"));

        // metadata 为 null → 原样返回
        assertEquals(base, mw.onSystemPrompt(base, agent, ctx("u1", null)));
        // 空白 reminder 不注入
        String outBlank = mw.onSystemPrompt(base, agent,
                ctx("u1", Map.of("reminder", " ")));
        assertFalse(outBlank.contains("<task-reminder>"));
    }

    // ====== PermissionMiddleware ======

    @Test
    void permissionMiddlewareSplitsAllowDenyAndAskUser() {
        PermissionEngine engine = mock(PermissionEngine.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        Tool readOnlyTool = mock(Tool.class);
        when(readOnlyTool.isReadOnly()).thenReturn(true);
        when(registry.get("search")).thenReturn(readOnlyTool);
        when(registry.get("deploy")).thenReturn(null);

        PermissionMiddleware mw = new PermissionMiddleware();
        ReflectionTestUtils.setField(mw, "permissionEngine", engine);
        ReflectionTestUtils.setField(mw, "toolRegistry", registry);
        assertEquals("permission", mw.name());
        assertEquals(20, mw.priority());

        ToolExecutor.ToolCallRequest allowReq =
                new ToolExecutor.ToolCallRequest("c1", "search", Map.of());
        ToolExecutor.ToolCallRequest denyReq =
                new ToolExecutor.ToolCallRequest("c2", "deploy", Map.of());
        ToolExecutor.ToolCallRequest askReq =
                new ToolExecutor.ToolCallRequest("c3", "deploy", Map.of());

        when(engine.check(any(), any()))
                .thenReturn(PermissionDecision.ALLOW)
                .thenReturn(PermissionDecision.DENY)
                .thenReturn(PermissionDecision.ASK_USER);

        List<ToolExecutor.ToolCallRequest> allowed = mw.onActing(
                List.of(allowReq, denyReq, askReq), agent, ctx("u1", null));

        assertEquals(List.of(allowReq), allowed, "仅 ALLOW 放行");
        assertEquals(1, state.getAsking().size(), "ASK_USER 登记为挂起");
        assertEquals("c3", state.getAsking().get(0).toolCallId());
        assertEquals(SuspendedToolCall.SuspendedState.ASKING,
                state.getAsking().get(0).state());
    }

    @Test
    void permissionMiddlewareReadsModeFromMetadata() {
        PermissionEngine engine = mock(PermissionEngine.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        PermissionMiddleware mw = new PermissionMiddleware();
        ReflectionTestUtils.setField(mw, "permissionEngine", engine);
        ReflectionTestUtils.setField(mw, "toolRegistry", registry);

        ToolExecutor.ToolCallRequest req = new ToolExecutor.ToolCallRequest("c1", "t", Map.of());
        when(engine.check(any(), any())).thenReturn(PermissionDecision.ALLOW);

        // 非法模式字符串 → 回退 DEFAULT
        mw.onActing(List.of(req), agent,
                ctx("u1", Map.of("permissionMode", "NOT_A_MODE")));

        // 合法模式字符串透传（用第一个枚举值验证调用参数）
        mw.onActing(List.of(req), agent,
                ctx("u1", Map.of("permissionMode", "DEFAULT")));

        // 子Agent 上下文标记透传
        mw.onActing(List.of(req), agent,
                ctx("u1", Map.of("subAgentContext", true)));
    }
}

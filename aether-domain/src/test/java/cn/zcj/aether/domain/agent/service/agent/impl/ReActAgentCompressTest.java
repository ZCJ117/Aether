package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.model.failover.FailoverReason;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VUL-04 / T4-2 + T4-3: {@code compressForRecovery} 的写入语义。
 *
 * <p>返回值是"消息列表是否真的被改变"，它决定恢复分支是否可归因为成功；
 * 两个写回点（microCompact 后、autoCompactIfNeeded 后）必须都落到
 * {@code AgentState.messagesMutable()} 同一引用上，否则 {@code call()} 的
 * 下一轮重试读到的仍是未压缩的消息。</p>
 */
class ReActAgentCompressTest {

    private static final RuntimeContext CTX =
            new RuntimeContext("user1", "session1", null, null, null, null, null);

    @Test
    void microCompactWriteBackReturnsTrue() throws Exception {
        ContextManager contextManager = mock(ContextManager.class);
        // 两遍扫描移除冗余写操作：3 条 → 1 条
        when(contextManager.microCompact(any())).thenReturn(List.of(TurnMessage.user("仅存的最新写操作")));
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.notNeeded());

        ReActAgent agent = agent(contextManager);
        agent.getState().messagesMutable().addAll(List.of(
                TurnMessage.user("写 A"), TurnMessage.user("写 B"), TurnMessage.user("写 C")));

        assertTrue(invokeCompressForRecovery(agent),
                "microCompact 改变了消息列表 → 必须返回 true");
        assertEquals(1, agent.getState().messagesMutable().size(),
                "压缩结果必须就地写回 AgentState.messagesMutable() 同一引用");
    }

    @Test
    void autoCompactWriteBackReturnsTrue() throws Exception {
        ContextManager contextManager = mock(ContextManager.class);
        when(contextManager.microCompact(any())).thenAnswer(inv -> inv.getArgument(0));
        // 超阈值 → LLM 摘要压缩：3 条 → 1 条摘要
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.compacted(
                        "摘要", List.of(TurnMessage.user("[摘要] 历史已压缩")), 8000, 900));

        ReActAgent agent = agent(contextManager);
        agent.getState().messagesMutable().addAll(List.of(
                TurnMessage.user("历史一"), TurnMessage.user("历史二"), TurnMessage.user("历史三")));

        assertTrue(invokeCompressForRecovery(agent),
                "autoCompactIfNeeded 产出压缩结果 → 必须返回 true");
        assertEquals(1, agent.getState().messagesMutable().size(),
                "摘要结果必须就地写回 AgentState.messagesMutable() 同一引用");
    }

    @Test
    void noCompressibleContentReturnsFalse() throws Exception {
        ContextManager contextManager = mock(ContextManager.class);
        when(contextManager.microCompact(any()))
                .thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.notNeeded());

        ReActAgent agent = agent(contextManager);
        agent.getState().messagesMutable().addAll(List.of(
                TurnMessage.user("你好"), TurnMessage.assistant("你好，有什么可以帮你？")));

        assertFalse(invokeCompressForRecovery(agent),
                "消息未达压缩阈值且无可移除冗余 → 返回 false");
        assertEquals(2, agent.getState().messagesMutable().size(), "消息列表不应被改动");
    }

    @Test
    void emptyMessagesReturnsFalse() throws Exception {
        ContextManager contextManager = mock(ContextManager.class);

        ReActAgent agent = agent(contextManager);

        assertFalse(invokeCompressForRecovery(agent), "空消息列表应直接返回 false，不调用压缩");
        // 锁住"空列表在压缩之前短路"这一位置：若把空判断挪到 microCompact 之后，
        // 返回 false 的断言仍会通过，但这两条会失败
        verify(contextManager, never()).microCompact(any());
        verify(contextManager, never()).autoCompactIfNeeded(any(), any(), any(), any());
    }

    // ── helpers ──

    private static ReActAgent agent(ContextManager contextManager) {
        return new ReActAgent(
                AgentConfig.builder().name("compress-agent").instruction("测试指令")
                        .cancelToken(new CancelToken()).build(),
                mock(ChatModel.class), mock(ModelInvoker.class), mock(ToolExecutor.class),
                contextManager, null, null, null, null, null, null);
    }

    /** {@code compressForRecovery} 按 spec 保持 private，测试经反射调用。 */
    private static boolean invokeCompressForRecovery(ReActAgent agent) throws Exception {
        Method m = ReActAgent.class.getDeclaredMethod(
                "compressForRecovery", RuntimeContext.class, FailoverReason.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(agent, CTX, FailoverReason.CONTEXT_OVERFLOW);
    }
}

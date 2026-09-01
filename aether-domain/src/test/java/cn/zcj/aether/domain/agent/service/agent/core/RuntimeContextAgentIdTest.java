package cn.zcj.aether.domain.agent.service.agent.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * O3（对应 D3）：RuntimeContext.getId() 语义修复——
 * getId() 读 agentId（当前 Agent 身份），与 parentAgentId 分离；未显式传入时回退 parentAgentId（旧行为兼容）。
 */
class RuntimeContextAgentIdTest {

    @Test
    void agentIdExplicitlyPassedShouldBeReturnedByGetId() {
        RuntimeContext ctx = new RuntimeContext("u1", "s1", null, "parent-agent",
                "hello", Map.of(), null, "child-agent");

        assertEquals("child-agent", ctx.getId(), "getId() 应返回当前 Agent 身份");
        assertEquals("parent-agent", ctx.parentAgentId(), "父 Agent 单独由 parentAgentId 表达");
    }

    @Test
    void agentIdMissingShouldFallBackToParentAgentId() {
        // 旧 7 参构造：agentId 缺省回退 parentAgentId（兼容存量调用点）
        RuntimeContext ctx = new RuntimeContext("u1", "s1", null, "parent-agent",
                "hello", Map.of(), null);

        assertEquals("parent-agent", ctx.getId(), "缺省 agentId 应回退 parentAgentId（旧行为）");
    }

    @Test
    void topLevelWithoutBothShouldReturnNull() {
        RuntimeContext ctx = new RuntimeContext("u1", "s1", null, null,
                "hello", Map.of(), null, null);

        assertNull(ctx.getId(), "顶级且未传 agentId 时保持 null（不抛异常）");
    }

    @Test
    void forkForChildShouldSetChildAgentIdAndParentFromCurrent() {
        RuntimeContext parent = new RuntimeContext("u1", "s1", null, "grand-parent",
                "hello", Map.of(), null, "parent-agent");

        RuntimeContext child = parent.forkForChild("child-agent", "sub-task");

        assertEquals("parent-agent", child.parentAgentId(), "子上下文的父 = 当前 Agent");
        assertEquals("child-agent", child.getId(), "子上下文的当前 Agent = 子 Agent");
    }
}

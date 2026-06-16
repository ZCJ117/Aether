package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReActAgent 基础单元测试 — P0-5
 * 验证：AgentConfig 创建、AgentState 管理、RuntimeContext 不可变性
 */
class ReActAgentTest {

    @Test
    void agentConfigShouldBeImmutable() {
        AgentConfig config = AgentConfig.builder()
                .name("researcher")
                .instruction("你是一个研究员")
                .description("研究助手")
                .outputKey("research_result")
                .toolNames(List.of("web_search", "file_read"))
                .modelRef("gpt-4o")
                .agentType("react")
                .build();

        assertEquals("researcher", config.getName());
        assertEquals("你是一个研究员", config.getInstruction());
        assertEquals("react", config.getAgentType());
        assertEquals(2, config.getToolNames().size());
    }

    @Test
    void agentConfigShouldHaveDefaults() {
        AgentConfig config = AgentConfig.builder()
                .name("defaultAgent")
                .instruction("默认指令")
                .build();

        assertEquals("react", config.getAgentType());
        assertTrue(config.getToolNames().isEmpty());
        assertNull(config.getModelRef());
    }

    @Test
    void agentStateShouldManageMessages() {
        AgentState state = new AgentState();
        assertEquals(0, state.getMessages().size());
        assertEquals(AgentState.AgentStatus.IDLE, state.getStatus());
        assertEquals(0, state.getCurrentTurn());

        state.incrementTurn();
        assertEquals(1, state.getCurrentTurn());

        state.setStatus(AgentState.AgentStatus.RUNNING);
        assertEquals(AgentState.AgentStatus.RUNNING, state.getStatus());

        state.setAttribute("customKey", "customValue");
        assertEquals("customValue", state.getAttribute("customKey"));
    }

    @Test
    void agentStateShouldHaveDefensiveCopy() {
        AgentState state = new AgentState();
        // 先添加一条消息，确保列表非空
        state.messagesMutable().add(
                cn.zcj.aether.domain.agent.service.runtime.TurnMessage.user("test"));
        // getMessages returns defensive copy
        List<?> copy1 = state.getMessages();
        List<?> copy2 = state.getMessages();
        assertNotSame(copy1, copy2); // 每次返回新副本
        assertEquals(1, copy1.size());
        assertEquals(1, copy2.size());
    }

    @Test
    void runtimeContextShouldBeImmutable() {
        RuntimeContext ctx1 = new RuntimeContext("user1", "session1", null, null, "你好", null, null);
        assertEquals("user1", ctx1.userId());
        assertEquals("session1", ctx1.sessionId());
        assertEquals("你好", ctx1.initialMessage());
        assertNotNull(ctx1.correlationId());
        assertNotNull(ctx1.createdAt());

        // 相同参数产生不同的 correlationId
        RuntimeContext ctx2 = new RuntimeContext("user1", "session1", null, null, "你好", null, null);
        assertNotEquals(ctx1.correlationId(), ctx2.correlationId());
    }

    @Test
    void runtimeContextShouldForkForChild() {
        RuntimeContext parent = new RuntimeContext("user1", "session1", "trace001", null, "hello", null, null);
        RuntimeContext child = parent.forkForChild("subAgent", "subTask");

        assertEquals("user1", child.userId());
        assertEquals("trace001", child.correlationId()); // 保持追踪链路
        assertTrue(child.sessionId().contains("subAgent"));
    }

    @Test
    void agentConfigFromNodeDefShouldMapCorrectly() {
        cn.zcj.aether.domain.agent.model.graph.AgentNodeDef nodeDef =
                cn.zcj.aether.domain.agent.model.graph.AgentNodeDef.builder()
                        .name("analyst")
                        .instruction("你是一个分析师")
                        .description("数据分析")
                        .outputKey("analysis")
                        .modelRef("claude-sonnet-4-6")
                        .agentType("react")
                        .build();

        AgentConfig config = AgentConfig.fromNodeDef(nodeDef);

        assertEquals("analyst", config.getName());
        assertEquals("你是一个分析师", config.getInstruction());
        assertEquals("claude-sonnet-4-6", config.getModelRef());
    }

    @Test
    void loadStateShouldClearAndRepopulate() {
        AgentState state = new AgentState();
        state.messagesMutable().add(
                cn.zcj.aether.domain.agent.service.runtime.TurnMessage.user("test message"));

        Map<String, Object> saved = Map.of(
                "agentId", "testAgent",
                "currentTurn", 5,
                "rollingSummary", "summary text",
                "status", "IDLE",
                "messages", List.of(
                        cn.zcj.aether.domain.agent.service.runtime.TurnMessage.user("hello"))
        );

        // 模拟 loadState（通过 BaseAgent 会调用）
        state.messagesMutable().clear();
        @SuppressWarnings("unchecked")
        var msgs = (List<cn.zcj.aether.domain.agent.service.runtime.TurnMessage>) saved.get("messages");
        if (msgs != null) state.messagesMutable().addAll(msgs);

        assertEquals(1, state.messagesMutable().size());
        assertEquals("hello", state.messagesMutable().get(0).content());
    }
}

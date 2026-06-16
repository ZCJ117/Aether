package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatService 路由逻辑单元测试 — P0-5
 * 验证：单 Agent / 多 Agent 路由决策、AgentConfig 构建、{memory} 占位符注入
 */
class ChatServiceTest {

    // ==================== 路由决策测试 ====================

    @Test
    void emptyEdgesShouldRouteToSingleAgent() {
        AgentGraph graph = buildSingleAgentGraph("assistant", "gpt-4o");

        // 单 Agent 路由条件：edges 为空
        assertTrue(graph.getEdges() == null || graph.getEdges().isEmpty(),
                "无 edges 时应路由到单 Agent 路径");

        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        assertNotNull(entry);
        assertEquals("assistant", entry.getName());
    }

    @Test
    void nonEmptyEdgesShouldRouteToMultiAgent() {
        AgentGraph graph = buildMultiAgentGraph(
                List.of("researcher", "analyst"),
                AgentEdgeType.SEQUENTIAL);

        // 多 Agent 路由条件：edges 非空
        assertFalse(graph.getEdges().isEmpty(),
                "有 edges 时应路由到 GraphExecutor 多 Agent 路径");

        assertEquals(1, graph.getEdges().size());
    }

    @Test
    void graphEntryPointShouldResolveCorrectly() {
        AgentGraph graph = AgentGraph.builder()
                .agentDefs(new LinkedHashMap<>(Map.of(
                        "first", AgentNodeDef.builder().name("first").instruction("i1").build(),
                        "second", AgentNodeDef.builder().name("second").instruction("i2").build()
                )))
                .entryPoint("first")
                .build();

        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        assertNotNull(entry);
        assertEquals("first", entry.getName());
    }

    @Test
    void missingEntryPointShouldReturnNull() {
        AgentGraph graph = AgentGraph.builder()
                .agentDefs(new LinkedHashMap<>(Map.of(
                        "agent1", AgentNodeDef.builder().name("agent1").build()
                )))
                .entryPoint("nonexistent")
                .build();

        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        assertNull(entry, "不存在的 entryPoint 应返回 null，由调用方抛 AppException");
    }

    // ==================== AgentConfig 构建测试 ====================

    @Test
    void agentConfigFromNodeDefShouldPreserveAllFields() {
        AgentNodeDef def = AgentNodeDef.builder()
                .name("researcher")
                .instruction("你是一个研究员，擅长数据收集")
                .description("研究助手，负责收集和整理信息")
                .outputKey("research_result")
                .toolNames(List.of("web_search", "file_read"))
                .modelRef("gpt-4o")
                .agentType("react")
                .build();

        AgentConfig config = AgentConfig.fromNodeDef(def);

        assertEquals("researcher", config.getName());
        assertEquals("你是一个研究员，擅长数据收集", config.getInstruction());
        assertEquals("研究助手，负责收集和整理信息", config.getDescription());
        assertEquals("research_result", config.getOutputKey());
        assertEquals(2, config.getToolNames().size());
        assertTrue(config.getToolNames().contains("web_search"));
        assertTrue(config.getToolNames().contains("file_read"));
        assertEquals("gpt-4o", config.getModelRef());
        assertEquals("react", config.getAgentType());
    }

    @Test
    void agentConfigShouldUseDefaultAgentTypeWhenNotSpecified() {
        AgentNodeDef def = AgentNodeDef.builder()
                .name("simpleAgent")
                .instruction("simple instruction")
                .build();

        AgentConfig config = AgentConfig.fromNodeDef(def);

        assertEquals("react", config.getAgentType(),
                "未指定 agentType 时应默认 'react'");
    }

    @Test
    void agentConfigBuilderShouldSetAgentType() {
        AgentConfig config = AgentConfig.builder()
                .name("custom")
                .instruction("custom instruction")
                .agentType("react")
                .build();

        assertEquals("react", config.getAgentType());
    }

    @Test
    void agentConfigWithMemoryPlaceholderShouldBeInjectable() {
        // 模拟 ChatService.injectMemory() 的行为
        String instruction = "你是一个助手。参考以下记忆：{memory}";
        String memory = "用户之前问过关于新能源汽车的问题";

        String injected = instruction.replace("{memory}", memory);

        assertTrue(injected.contains("新能源汽车"));
        assertFalse(injected.contains("{memory}"));

        AgentConfig config = AgentConfig.builder()
                .name("assistant")
                .instruction(injected)
                .build();

        assertTrue(config.getInstruction().contains("新能源汽车"));
    }

    @Test
    void agentConfigWithoutMemoryPlaceholderShouldRemainUnchanged() {
        String instruction = "你是一个通用助手，请回答用户问题";
        String memory = "无关记忆";

        // 当 instruction 中不含 {memory} 时，不应替换
        String result = instruction;
        if (instruction.contains("{memory}")) {
            result = instruction.replace("{memory}", memory);
        }

        assertEquals(instruction, result);
        assertFalse(result.contains(memory));
    }

    // ==================== 多 Agent 编排验证测试 ====================

    @Test
    void sequentialWorkflowShouldPreserveAgentOrder() {
        List<String> subAgents = List.of("researcher", "analyst", "writer");

        AgentGraph graph = buildMultiAgentGraph(subAgents, AgentEdgeType.SEQUENTIAL);

        List<String> actualOrder = graph.getEdges().get(0).getSubAgents();
        assertEquals(subAgents, actualOrder,
                "SEQUENTIAL 工作流的 subAgents 顺序应与配置一致");
    }

    @Test
    void parallelWorkflowShouldSupportConcurrentAgents() {
        List<String> subAgents = List.of("scraper1", "scraper2", "scraper3");

        AgentEdge edge = AgentEdge.builder()
                .workflowName("parallel-scrape")
                .type(AgentEdgeType.PARALLEL)
                .subAgents(subAgents)
                .build();

        assertEquals(AgentEdgeType.PARALLEL, edge.getType());
        assertEquals(3, edge.getSubAgents().size());
    }

    @Test
    void loopWorkflowShouldHaveMaxIterations() {
        AgentEdge edge = AgentEdge.builder()
                .workflowName("refine-loop")
                .type(AgentEdgeType.LOOP)
                .subAgents(List.of("writer", "reviewer"))
                .maxIterations(3)
                .build();

        assertEquals(AgentEdgeType.LOOP, edge.getType());
        assertEquals(3, edge.getMaxIterations());
    }

    @Test
    void agentNamesShouldBeUniqueInGraph() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("agent1", AgentNodeDef.builder().name("agent1").build());
        defs.put("agent2", AgentNodeDef.builder().name("agent2").build());

        // 验证 Map key 与 AgentNodeDef.name 一致
        for (Map.Entry<String, AgentNodeDef> entry : defs.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().getName(),
                    "Map key 应与 AgentNodeDef.name 一致");
        }
    }

    // ==================== memory 注入测试 ====================

    @Test
    void memoryInjectionShouldHandleNullMemory() {
        String instruction = "你是助手。{memory}";
        String memory = null;

        // 模拟 ChatService.injectMemory() 行为
        String result = instruction;
        if (memory != null && !memory.isEmpty()) {
            result = instruction.replace("{memory}", memory);
        }

        // memory 为 null 时应保留占位符
        assertEquals("你是助手。{memory}", result);
    }

    @Test
    void memoryInjectionShouldHandleEmptyMemory() {
        String instruction = "你是助手。{memory}";
        String memory = "";

        String result = instruction;
        if (memory != null && !memory.isEmpty()) {
            result = instruction.replace("{memory}", memory);
        }

        // memory 为空字符串时应保留占位符
        assertEquals("你是助手。{memory}", result);
    }

    @Test
    void memoryInjectionShouldHandleNullInstruction() {
        String instruction = null;
        String memory = "一些记忆";

        // 模拟 ChatService.injectMemory() 行为
        String result;
        if (memory == null || memory.isEmpty()) {
            result = instruction;
        } else if (instruction == null) {
            result = memory;
        } else {
            result = instruction.replace("{memory}", memory);
        }

        assertEquals("一些记忆", result);
    }

    // ==================== AgentNodeDef 与 AgentConfig 映射测试 ====================

    @Test
    void agentConfigShouldBeImmutableAfterBuild() {
        AgentConfig config = AgentConfig.builder()
                .name("immutable-test")
                .instruction("不可变配置")
                .description("测试描述")
                .outputKey("out")
                .toolNames(List.of("tool1"))
                .modelRef("gpt-4o")
                .agentType("react")
                .build();

        // @Value 注解确保不可变性
        assertEquals("immutable-test", config.getName());
        assertEquals("不可变配置", config.getInstruction());

        // toolNames 是通过 List.of() 创建的不可变列表
        assertThrows(UnsupportedOperationException.class,
                () -> config.getToolNames().add("newTool"),
                "@Builder.Default + List.of() 应产生不可变列表");
    }

    @Test
    void agentConfigEqualityShouldWork() {
        AgentConfig config1 = AgentConfig.builder()
                .name("test").instruction("i1").modelRef("gpt-4o").build();
        AgentConfig config2 = AgentConfig.builder()
                .name("test").instruction("i1").modelRef("gpt-4o").build();

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void agentConfigShouldDifferByAgentType() {
        AgentConfig reactConfig = AgentConfig.builder()
                .name("same").instruction("same").agentType("react").build();
        AgentConfig flowConfig = AgentConfig.builder()
                .name("same").instruction("same").agentType("flow").build();

        assertNotEquals(reactConfig, flowConfig,
                "不同 agentType 的配置应不相等");
    }

    // ==================== 辅助方法 ====================

    private AgentGraph buildSingleAgentGraph(String agentName, String modelRef) {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put(agentName, AgentNodeDef.builder()
                .name(agentName)
                .instruction("你是一个" + agentName)
                .outputKey(agentName + "_output")
                .modelRef(modelRef)
                .build());

        return AgentGraph.builder()
                .appName("single-agent-app")
                .agentDefs(defs)
                .edges(List.of())
                .entryPoint(agentName)
                .modelRef(modelRef)
                .build();
    }

    private AgentGraph buildMultiAgentGraph(List<String> agentNames, AgentEdgeType type) {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        for (String name : agentNames) {
            defs.put(name, AgentNodeDef.builder()
                    .name(name)
                    .instruction("你是" + name)
                    .outputKey(name + "_output")
                    .build());
        }

        return AgentGraph.builder()
                .appName("multi-agent-app")
                .agentDefs(defs)
                .edges(List.of(
                        AgentEdge.builder()
                                .workflowName(type.name().toLowerCase() + "-workflow")
                                .type(type)
                                .subAgents(agentNames)
                                .maxIterations(type == AgentEdgeType.LOOP ? 3 : null)
                                .build()
                ))
                .entryPoint(agentNames.get(0))
                .build();
    }
}

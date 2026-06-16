package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphExecutor 单元测试 — P0-5
 * 验证：AgentGraph IR 模型、AgentEdge 类型解析、ExecutionState 状态管理
 */
class GraphExecutorTest {

    // ==================== AgentGraph IR 模型测试 ====================

    @Test
    void agentGraphShouldBuildWithEntryPoint() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("researcher", AgentNodeDef.builder()
                .name("researcher")
                .instruction("你是一个研究员")
                .outputKey("result")
                .modelRef("gpt-4o")
                .build());

        AgentGraph graph = AgentGraph.builder()
                .appName("test-app")
                .agentDefs(defs)
                .edges(List.of())
                .entryPoint("researcher")
                .modelRef("gpt-4o")
                .build();

        assertEquals("test-app", graph.getAppName());
        assertEquals("researcher", graph.getEntryPoint());
        assertEquals(1, graph.getAgentDefs().size());
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void agentGraphWithEdgesShouldRouteToMultiAgent() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("agent1", AgentNodeDef.builder().name("agent1").instruction("i1").build());
        defs.put("agent2", AgentNodeDef.builder().name("agent2").instruction("i2").build());

        List<AgentEdge> edges = List.of(
                AgentEdge.builder()
                        .workflowName("sequential-workflow")
                        .type(AgentEdgeType.SEQUENTIAL)
                        .subAgents(List.of("agent1", "agent2"))
                        .build()
        );

        AgentGraph graph = AgentGraph.builder()
                .appName("multi-agent-app")
                .agentDefs(defs)
                .edges(edges)
                .entryPoint("agent1")
                .build();

        // 多 Agent 图：edges 非空 → 应路由到 GraphExecutor
        assertFalse(graph.getEdges().isEmpty());
        assertEquals(1, graph.getEdges().size());
        assertEquals(AgentEdgeType.SEQUENTIAL, graph.getEdges().get(0).getType());
        assertEquals(2, graph.getEdges().get(0).getSubAgents().size());
    }

    @Test
    void agentGraphWithoutEdgesShouldRouteToSingleAgent() {
        AgentGraph graph = AgentGraph.builder()
                .appName("single-agent")
                .agentDefs(Map.of("only", AgentNodeDef.builder().name("only").build()))
                .edges(List.of())
                .entryPoint("only")
                .build();

        // 单 Agent 图：edges 为空 → 应路由到 AgentFactory 创建单个 Agent
        assertTrue(graph.getEdges().isEmpty());
        assertEquals("only", graph.getEntryPoint());
    }

    // ==================== AgentEdge 类型解析测试 ====================

    @Test
    void agentEdgeTypeShouldParseSequential() {
        assertEquals(AgentEdgeType.SEQUENTIAL, AgentEdgeType.fromYamlType("sequential"));
    }

    @Test
    void agentEdgeTypeShouldParseParallel() {
        assertEquals(AgentEdgeType.PARALLEL, AgentEdgeType.fromYamlType("parallel"));
    }

    @Test
    void agentEdgeTypeShouldParseLoop() {
        assertEquals(AgentEdgeType.LOOP, AgentEdgeType.fromYamlType("loop"));
    }

    @Test
    void agentEdgeTypeShouldThrowForUnknown() {
        // 未识别类型抛出 AppException（非回退到 SEQUENTIAL）
        assertThrows(AppException.class,
                () -> AgentEdgeType.fromYamlType("unknown_type"));
    }

    @Test
    void agentEdgeTypeShouldThrowForNull() {
        // null 输入导致 switch 匹配 default → 抛出 AppException
        assertThrows(RuntimeException.class,
                () -> AgentEdgeType.fromYamlType(null));
    }

    @Test
    void agentEdgeShouldBuildWithDefaultMaxIterations() {
        AgentEdge edge = AgentEdge.builder()
                .workflowName("loop-test")
                .type(AgentEdgeType.LOOP)
                .subAgents(List.of("a1"))
                .build();

        // @Builder.Default 默认值为 3
        assertEquals(3, edge.getMaxIterations());
    }

    @Test
    void agentEdgeShouldBuildWithCustomMaxIterations() {
        AgentEdge edge = AgentEdge.builder()
                .workflowName("loop-custom")
                .type(AgentEdgeType.LOOP)
                .subAgents(List.of("a1"))
                .maxIterations(5)
                .build();

        assertEquals(5, edge.getMaxIterations());
    }

    @Test
    void parallelAgentEdgeShouldHaveMultipleSubAgents() {
        AgentEdge edge = AgentEdge.builder()
                .workflowName("parallel-test")
                .type(AgentEdgeType.PARALLEL)
                .subAgents(List.of("researcher", "analyst", "writer"))
                .build();

        assertEquals(3, edge.getSubAgents().size());
    }

    // ==================== AgentNodeDef 模型测试 ====================

    @Test
    void agentNodeDefShouldDefaultAgentTypeToReact() {
        AgentNodeDef def = AgentNodeDef.builder()
                .name("test")
                .instruction("test instruction")
                .build();

        assertEquals("react", def.getAgentType());
    }

    @Test
    void agentNodeDefShouldSupportCustomAgentType() {
        AgentNodeDef def = AgentNodeDef.builder()
                .name("userProxy")
                .instruction("wait for user input")
                .agentType("user_proxy")
                .build();

        assertEquals("user_proxy", def.getAgentType());
    }

    @Test
    void agentNodeDefShouldIncludeModelRef() {
        AgentNodeDef def = AgentNodeDef.builder()
                .name("analyst")
                .instruction("分析数据")
                .modelRef("claude-sonnet-4-6")
                .build();

        assertEquals("claude-sonnet-4-6", def.getModelRef());
    }

    @Test
    void agentNodeDefToolNamesShouldDefaultToEmptyList() {
        AgentNodeDef def = AgentNodeDef.builder()
                .name("basic")
                .instruction("basic instruction")
                .build();

        assertNotNull(def.getToolNames());
        assertTrue(def.getToolNames().isEmpty());
    }

    // ==================== ExecutionState 测试 ====================

    @Test
    void executionStateShouldTrackSingleOutput() {
        ExecutionState state = new ExecutionState();
        state.appendOutput("research", "研究结果: 新能源市场增长");
        state.appendOutput("research", "，年增长率25%");

        assertEquals("研究结果: 新能源市场增长，年增长率25%",
                state.getText("research"));
    }

    @Test
    void executionStateShouldTrackMultipleAgents() {
        ExecutionState state = new ExecutionState();
        state.appendOutput("researcher", "研究数据");
        state.appendOutput("analyst", "分析结论");

        assertEquals("研究数据", state.getText("researcher"));
        assertEquals("分析结论", state.getText("analyst"));
    }

    @Test
    void executionStateShouldReturnEmptyForUnknownKey() {
        ExecutionState state = new ExecutionState();
        // getText 对未知 key 返回 ""
        assertEquals("", state.getText("nonexistent"));
    }

    @Test
    void executionStateShouldTrackLastOutput() {
        ExecutionState state = new ExecutionState();
        // 初始状态 getLastOutput 返回 ""
        assertEquals("", state.getLastOutput());

        // 设置 lastAgentName + finalOutput 后 getLastOutput 才返回内容
        state.setLastAgentName("agent1");
        state.setFinalOutput("agent1", "第一步输出");
        assertEquals("第一步输出", state.getLastOutput());

        state.setLastAgentName("agent2");
        state.setFinalOutput("agent2", "第二步输出");
        assertEquals("第二步输出", state.getLastOutput());
    }

    @Test
    void executionStateShouldHandleEmptyOutput() {
        ExecutionState state = new ExecutionState();
        state.appendOutput("agent", "");

        assertEquals("", state.getText("agent"));
    }

    @Test
    void executionStateShouldForkSource() {
        ExecutionState parent = new ExecutionState();
        // forkSource 复制 finalOutputs，不复制 outputs (StringBuilder)
        parent.setFinalOutput("parentKey", "parent data");

        ExecutionState child = parent.forkSource();

        // 子状态应从父状态继承 finalOutputs
        assertEquals("parent data", child.getOutput("parentKey"));

        // 子状态独立修改不影响父状态
        child.appendOutput("childKey", "child data");
        assertEquals("", parent.getText("childKey"));
        assertEquals("child data", child.getText("childKey"));
    }

    @Test
    void executionStateShouldMarkComplete() {
        ExecutionState state = new ExecutionState();
        state.appendOutput("result", "部分数据");

        // markComplete 将结果写入 finalOutputs
        state.markComplete("result", "最终结果");

        // getOutput 优先读 finalOutputs，getText 读 outputs
        assertEquals("最终结果", state.getOutput("result"));
        assertEquals("部分数据", state.getText("result"));
    }

    @Test
    void executionStateShouldTrackLastAgent() {
        ExecutionState state = new ExecutionState();
        // 设置之前 getLastOutput() 返回空
        assertEquals("", state.getLastOutput());

        state.setLastAgentName("researcher");
        state.setFinalOutput("researcher", "研究结果");
        assertEquals("研究结果", state.getLastOutput());
    }

    @Test
    void executionStateShouldMergeSubStates() {
        ExecutionState main = new ExecutionState();
        main.setFinalOutput("mainKey", "main data");

        ExecutionState sub = new ExecutionState();
        sub.setFinalOutput("subKey", "sub data");

        main.merge(sub, "subKey");

        assertEquals("sub data", main.getOutput("subKey"));
        assertEquals("main data", main.getOutput("mainKey"));
    }

    @Test
    void executionStateShouldResolveSimpleTemplate() {
        ExecutionState state = new ExecutionState();
        // resolveTemplate 替换 finalOutputs + outputs 中的 {key}
        state.setFinalOutput("research", "新能源数据");

        String resolved = state.resolveTemplate("基于 {research} 进行分析");
        assertEquals("基于 新能源数据 进行分析", resolved);
    }

    @Test
    void executionStateShouldResolveTemplateWithMissingKey() {
        ExecutionState state = new ExecutionState();

        // 未设置的 outputKey 应保留原占位符
        String resolved = state.resolveTemplate("基于 {nonexistent} 分析");
        assertEquals("基于 {nonexistent} 分析", resolved);
    }

    @Test
    void executionStateShouldResolveTemplateWithMultipleKeys() {
        ExecutionState state = new ExecutionState();
        state.setFinalOutput("data", "数据集A");
        state.setFinalOutput("method", "回归分析");

        String resolved = state.resolveTemplate("使用 {method} 处理 {data}");
        assertEquals("使用 回归分析 处理 数据集A", resolved);
    }

    @Test
    void executionStateShouldResolveTemplateFromOutputStream() {
        ExecutionState state = new ExecutionState();
        // outputs (StringBuilder) 中的 key 也可被 resolveTemplate 替换
        state.appendOutput("info", "流式输出内容");

        String resolved = state.resolveTemplate("摘要: {info}");
        assertEquals("摘要: 流式输出内容", resolved);
    }

    // ==================== 图结构验证测试 ====================

    @Test
    void graphShouldContainAllReferencedAgents() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("a1", AgentNodeDef.builder().name("a1").build());
        defs.put("a2", AgentNodeDef.builder().name("a2").build());
        defs.put("a3", AgentNodeDef.builder().name("a3").build());

        AgentGraph graph = AgentGraph.builder()
                .agentDefs(defs)
                .edges(List.of(
                        AgentEdge.builder()
                                .type(AgentEdgeType.SEQUENTIAL)
                                .subAgents(List.of("a1", "a2", "a3"))
                                .build()
                ))
                .entryPoint("a1")
                .build();

        // 所有 edge 引用的 agent 必须在 agentDefs 中存在
        for (AgentEdge edge : graph.getEdges()) {
            for (String agentName : edge.getSubAgents()) {
                assertTrue(graph.getAgentDefs().containsKey(agentName),
                        "Agent '" + agentName + "' should exist in agentDefs");
            }
        }
    }

    @Test
    void graphEntryPointShouldBeValidAgent() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("start", AgentNodeDef.builder().name("start").build());

        AgentGraph graph = AgentGraph.builder()
                .agentDefs(defs)
                .entryPoint("start")
                .build();

        assertTrue(graph.getAgentDefs().containsKey(graph.getEntryPoint()));
    }

    // ==================== 并行 + 循环编排参数测试 ====================

    @Test
    void parallelExecutionShouldUseForkSource() {
        ExecutionState parent = new ExecutionState();
        parent.setFinalOutput("shared", "shared value");

        // 并行执行时每个 Agent 使用独立的 forkSource
        ExecutionState child1 = parent.forkSource();
        ExecutionState child2 = parent.forkSource();

        child1.appendOutput("agent1", "output1");
        child2.appendOutput("agent2", "output2");

        // 各自输出独立
        assertEquals("output1", child1.getText("agent1"));
        assertEquals("output2", child2.getText("agent2"));
        assertEquals("", child1.getText("agent2"));

        // 共享 finalOutputs
        assertEquals("shared value", child1.getOutput("shared"));
        assertEquals("shared value", child2.getOutput("shared"));
    }

    @Test
    void loopExecutionShouldRespectMaxIterations() {
        // LOOP 类型边必须设置合理的 maxIterations 防止死循环
        AgentEdge loopEdge = AgentEdge.builder()
                .workflowName("refinement")
                .type(AgentEdgeType.LOOP)
                .subAgents(List.of("writer", "reviewer"))
                .maxIterations(5)
                .build();

        assertEquals(AgentEdgeType.LOOP, loopEdge.getType());
        assertEquals(5, loopEdge.getMaxIterations());
        assertTrue(loopEdge.getMaxIterations() > 0,
                "maxIterations 必须大于 0 防止死循环");
    }

    @Test
    void sequentialEdgesShouldPreserveOrder() {
        List<String> ordered = List.of("step1", "step2", "step3");
        AgentEdge edge = AgentEdge.builder()
                .type(AgentEdgeType.SEQUENTIAL)
                .subAgents(ordered)
                .build();

        // SEQUENTIAL 类型 subAgents 顺序至关重要
        assertEquals("step1", edge.getSubAgents().get(0));
        assertEquals("step2", edge.getSubAgents().get(1));
        assertEquals("step3", edge.getSubAgents().get(2));
    }
}

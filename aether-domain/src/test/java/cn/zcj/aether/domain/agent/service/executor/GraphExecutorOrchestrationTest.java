package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionResult;
import cn.zcj.aether.domain.agent.service.executor.orchestration.GraphFlowCoordinator;
import cn.zcj.aether.domain.agent.service.executor.orchestration.GraphOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.OrchestrationServices;
import cn.zcj.aether.domain.agent.service.executor.orchestration.LoopOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.ParallelOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.SubAgentOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.subagent.ResultRefiner;
import cn.zcj.aether.domain.agent.service.subagent.SubAgentOrchestrator;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphExecutorOrchestrationTest {

    private OrchestrationServices services(Agent agent) {
        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        when(factory.create(any(AgentConfig.class))).thenReturn(agent);
        return new OrchestrationServices(factory, null, null, null);
    }

    private OrchestrationServices services(Agent agent, InterventionHandler handler) {
        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        when(factory.create(any(AgentConfig.class))).thenReturn(agent);
        return new OrchestrationServices(factory, handler, null, null);
    }

    @Test
    void loopStopsWhenOutputConverges() {
        AtomicInteger calls = new AtomicInteger();
        Agent agent = mock(Agent.class);
        when(agent.execute(any(RuntimeContext.class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            return Flowable.just(RuntimeEvent.text("stable"));
        });

        AgentGraph graph = singleNode("n1", "n1");
        AgentEdge edge = AgentEdge.builder().workflowName("wf").type(AgentEdgeType.LOOP)
                .subAgents(List.of("n1")).maxIterations(5).build();
        ExecutionState state = new ExecutionState();
        new LoopOrchestrationStrategy(services(agent)).execute(
                graph, edge, "user", "session", state, mockEmitter());

        assertEquals(2, calls.get(), "第二次输出不变时应在下一轮收敛");
    }

    @Test
    void broadcastInterceptionExceptionIsolatesFailingNode() {
        // 2.1-③-①: BROADCAST 通道拦截异常必须静默隔离——单节点审批故障不得扩散到整个图
        AtomicInteger executed = new AtomicInteger();
        Agent agent = mock(Agent.class);
        when(agent.execute(any(RuntimeContext.class))).thenAnswer(inv -> {
            executed.incrementAndGet();
            return Flowable.just(RuntimeEvent.text("ok"));
        });
        InterventionHandler failingForN1 = new InterventionHandler() {
            @Override
            public InterventionResult onPublish(String message, InterventionContext ctx) {
                if ("n1".equals(ctx.agentId())) {
                    throw new RuntimeException("审批组件宕机");
                }
                return InterventionResult.pass();
            }
        };

        Map<String, AgentNodeDef> defs = Map.of(
                "n1", AgentNodeDef.builder().name("n1").instruction("i1").outputKey("n1").build(),
                "n2", AgentNodeDef.builder().name("n2").instruction("i2").outputKey("n2").build());
        AgentGraph graph = AgentGraph.builder().appName("app").agentDefs(defs).edges(List.of()).build();
        AgentEdge edge = AgentEdge.builder().workflowName("wf").type(AgentEdgeType.PARALLEL)
                .subAgents(List.of("n1", "n2")).build();
        ExecutionState state = new ExecutionState();

        assertDoesNotThrow(() -> new ParallelOrchestrationStrategy(services(agent, failingForN1))
                .execute(graph, edge, "user", "session", state, mockEmitter()));
        assertEquals(1, executed.get(), "异常节点被隔离，其余节点应照常执行");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphflowMissingEntryNodeEmitsTopologyErrorAndCompletes() {
        // 2.1-③-①: GRAPHFLOW 拓扑异常（自环 → 无入口节点）必须发出错误事件并正常收束，不得抛异常
        AgentGraph graph = singleNode("n1", "n1");
        AgentEdge selfLoop = AgentEdge.builder().workflowName("wf").type(AgentEdgeType.GRAPHFLOW)
                .from("n1").to("n1").build();
        AgentGraph cyclic = AgentGraph.builder().appName("app")
                .agentDefs(graph.getAgentDefs()).edges(List.of(selfLoop)).build();

        FlowableEmitter<RuntimeEvent> emitter = mockEmitter();
        assertDoesNotThrow(() -> new GraphFlowCoordinator(services(mock(Agent.class)), null, null, null)
                .execute(cyclic, "user", "session", "任务", emitter, null));

        ArgumentCaptor<RuntimeEvent> captor = ArgumentCaptor.forClass(RuntimeEvent.class);
        verify(emitter, times(1)).onNext(captor.capture());
        assertEquals(RuntimeEvent.EventType.error, captor.getValue().getType());
        assertTrue(captor.getValue().getErrorMessage().contains("没有入口节点"));
        verify(emitter, times(1)).onComplete();
    }

    @Test
    void subagentStrategyStoresStructuredResult() {
        SubAgentOrchestrator orchestrator = mock(SubAgentOrchestrator.class);
        when(orchestrator.dispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ResultRefiner.SubAgentResult("成功", "子任务结果", Map.of()));

        AgentGraph graph = singleNode("n1", "sub-node");
        graph.getAgentDefs().get("n1").setInstruction("do work");
        AgentEdge edge = AgentEdge.builder().workflowName("wf").type(AgentEdgeType.SUBAGENT)
                .subAgents(List.of("n1")).build();
        ExecutionState state = new ExecutionState();
        new SubAgentOrchestrationStrategy(new OrchestrationServices(null, null, null, null), orchestrator)
                .execute(graph, edge, "user", "session", state, mockEmitter());

        verify(orchestrator).dispatch(any(), any(), any(), any(), any(), any());
        assertEquals("子任务结果", state.getOutput("sub-node"));
    }

    @Test
    void extractedProductionClassesStayBelowRoadmapLimit() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of(
                "src/main/java/cn/zcj/aether/domain/agent/service/executor"))) {
            long maxLines = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(GraphExecutorOrchestrationTest::lineCount)
                    .max()
                    .orElse(0);
            assertTrue(maxLines < 600, "生产类最大行数必须 <600，实际=" + maxLines);
        }
    }

    private static long lineCount(Path path) {
        try {
            return Files.readAllLines(path).size();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AgentGraph singleNode(String name, String outputKey) {
        Map<String, AgentNodeDef> defs = Map.of(name, AgentNodeDef.builder()
                .name(name).instruction("instruction").outputKey(outputKey).agentType("researcher").build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(List.of()).build();
    }

    @SuppressWarnings("unchecked")
    private static FlowableEmitter<RuntimeEvent> mockEmitter() {
        return mock(FlowableEmitter.class);
    }
}

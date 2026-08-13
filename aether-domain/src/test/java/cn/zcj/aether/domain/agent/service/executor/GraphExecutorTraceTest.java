package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.observability.GraphExecutionRecorder;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GraphExecutorTraceTest {

    private GraphExecutor buildExecutor(GraphExecutionRecorder recorder) throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(RuntimeEvent.text("node output")));
        return buildExecutorWithAgent(recorder, agent);
    }

    private GraphExecutor buildExecutorWithAgent(GraphExecutionRecorder recorder, Agent agent) throws Exception {
        GraphExecutor executor = new GraphExecutor();
        ReflectionTestUtils.setField(executor, "graphExecutionRecorder", recorder);

        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        when(factory.create(any(AgentConfig.class))).thenReturn(agent);
        ReflectionTestUtils.setField(executor, "agentFactory", factory);

        ConditionEvaluator cond = mock(ConditionEvaluator.class);
        when(cond.evaluate(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(executor, "conditionEvaluator", cond);

        ReflectionTestUtils.setField(executor, "subAgentOrchestrator", null);
        ReflectionTestUtils.setField(executor, "hookRegistry", null);
        ReflectionTestUtils.setField(executor, "interventionHandler", null);
        return executor;
    }

    private AgentGraph graphflowGraph() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("n1", AgentNodeDef.builder().name("n1").instruction("i1")
                .outputKey("o1").agentType("researcher").build());
        defs.put("n2", AgentNodeDef.builder().name("n2").instruction("i2")
                .outputKey("o2").agentType("summarizer").build());
        List<AgentEdge> edges = List.of(AgentEdge.builder()
                .workflowName("wf").type(AgentEdgeType.GRAPHFLOW).from("n1").to("n2").build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(edges).build();
    }

    private AgentGraph sequentialGraph() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("n1", AgentNodeDef.builder().name("n1").instruction("i1")
                .outputKey("o1").agentType("researcher").build());
        List<AgentEdge> edges = List.of(AgentEdge.builder()
                .workflowName("wf").type(AgentEdgeType.SEQUENTIAL).subAgents(List.of("n1")).build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(edges).build();
    }

    @Test
    void beginExecutionOnlyForGraphflow() throws Exception {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        GraphExecutor executor = buildExecutor(recorder);

        executor.execute(sequentialGraph(), "u1", "s1", "hi").blockingSubscribe();

        verify(recorder, never()).beginExecution(any());
    }

    @Test
    void beginExecutionCalledForGraphflow() throws Exception {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        when(recorder.beginExecution(any())).thenReturn("gx-test");
        GraphExecutor executor = buildExecutor(recorder);

        executor.execute(graphflowGraph(), "u1", "s1", "hi").blockingSubscribe();

        verify(recorder).beginExecution(any());
    }

    @Test
    void recordsNodeEventsAndCleansMdc() throws Exception {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        GraphExecutor executor = buildExecutor(recorder);

        AtomicReference<String> captured = new AtomicReference<>();
        executor.execute(graphflowGraph(), "u1", "s1", "hi")
                .doOnComplete(() -> captured.set(MDC.get("graphExecutionId")))
                .blockingSubscribe();

        assertNotNull(captured.get(), "执行期间 MDC 应携带 graphExecutionId");
        assertNull(MDC.get("graphExecutionId"), "结束后应清理 MDC");
        assertNull(MDC.get("sessionId"), "结束后应清理 sessionId");

        List<GraphExecutionRecorder.NodeEvent> trace = recorder.getExecutionTrace(captured.get());
        assertFalse(trace.isEmpty(), "应录制节点事件");
        assertEquals(GraphFlowState.NodeStatus.RUNNING, trace.get(0).status());
        assertEquals(GraphFlowState.NodeStatus.COMPLETED, trace.get(trace.size() - 1).status());
    }

    @Test
    void recorderIsAutowiredFieldForGraphTrace() throws Exception {
        Field field = GraphExecutor.class.getDeclaredField("graphExecutionRecorder");
        assertEquals(GraphExecutionRecorder.class, field.getType());
        assertTrue(field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class),
                "graphExecutionRecorder 必须经 @Autowired 注入");
    }

    @Test
    void recorderNullIsSafe() throws Exception {
        GraphExecutor executor = buildExecutor(null);
        assertDoesNotThrow(() -> executor.execute(graphflowGraph(), "u1", "s1", "hi")
                .blockingSubscribe());
    }

    @Test
    void graphflowWorkerThreadInheritsGraphExecutionIdMdc() throws Exception {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        Agent agent = mock(Agent.class);
        AtomicReference<String> workerMdc = new AtomicReference<>();
        when(agent.execute(any(RuntimeContext.class)))
                .thenAnswer(inv -> {
                    workerMdc.set(MDC.get("graphExecutionId"));
                    return Flowable.just(RuntimeEvent.text("node output"));
                });
        GraphExecutor executor = buildExecutorWithAgent(recorder, agent);

        executor.execute(graphflowGraph(), "u1", "s1", "hi").blockingSubscribe();

        assertNotNull(workerMdc.get(), "graphflow 节点工作线程应继承 graphExecutionId MDC");
        assertNull(MDC.get("graphExecutionId"), "执行结束后主线程 MDC 应清理");
    }
}

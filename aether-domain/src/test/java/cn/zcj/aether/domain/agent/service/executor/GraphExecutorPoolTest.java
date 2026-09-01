package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GraphExecutor 线程池有界性测试 — P0-1 统一线程资源管理。
 *
 * <p>验证：无 Spring 上下文时兜底池为有界池（4/8/queue200/CallerRuns）；
 * 50 节点 PARALLEL 图与 50 节点 GRAPHFLOW 图并发执行时线程数均不超过池上限
 * （无 50 线程爆炸）、任务全部完成。</p>
 */
class GraphExecutorPoolTest {

    private GraphExecutor buildExecutor(Agent agent) throws Exception {
        GraphExecutor executor = new GraphExecutor();
        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        when(factory.create(any(AgentConfig.class))).thenReturn(agent);
        ReflectionTestUtils.setField(executor, "agentFactory", factory);

        ConditionEvaluator cond = mock(ConditionEvaluator.class);
        when(cond.evaluate(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(executor, "conditionEvaluator", cond);

        ReflectionTestUtils.setField(executor, "subAgentOrchestrator", null);
        ReflectionTestUtils.setField(executor, "hookRegistry", null);
        ReflectionTestUtils.setField(executor, "interventionHandler", null);
        ReflectionTestUtils.setField(executor, "graphExecutionRecorder", null);
        ReflectionTestUtils.setField(executor, "backgroundReviewer", null);
        return executor;
    }

    private AgentGraph parallelGraph(int nodeCount) {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            String n = "n" + i;
            names.add(n);
            defs.put(n, AgentNodeDef.builder().name(n).instruction("i")
                    .outputKey("o" + i).agentType("researcher").build());
        }
        List<AgentEdge> edges = List.of(AgentEdge.builder()
                .workflowName("wf").type(AgentEdgeType.PARALLEL).subAgents(names).build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(edges).build();
    }

    /**
     * GRAPHFLOW DAG：50 个节点 + 一条 n0→n1 边；其余 48 个节点为无依赖入口，
     * 首批并发 49 个节点（> 池 max 8），验证 GRAPHFLOW 并发批次走有界池而非裸线程。
     */
    private AgentGraph graphFlowGraph(int nodeCount) {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            String n = "n" + i;
            defs.put(n, AgentNodeDef.builder().name(n).instruction("i")
                    .outputKey("o" + i).agentType("researcher").build());
        }
        List<AgentEdge> edges = List.of(AgentEdge.builder()
                .workflowName("wf").type(AgentEdgeType.GRAPHFLOW).from("n0").to("n1").build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(edges).build();
    }

    /** mock agent：进入 execute 后阻塞在 release latch，制造真实并发窗口。 */
    private Agent blockingAgent(AtomicInteger entered, CountDownLatch release) {
        Agent agent = mock(Agent.class);
        when(agent.execute(any(RuntimeContext.class))).thenAnswer(inv -> {
            entered.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Flowable.just(RuntimeEvent.text("out"));
        });
        return agent;
    }

    @Test
    void fallbackPoolIsBounded() throws Exception {
        GraphExecutor executor = buildExecutor(mock(Agent.class));
        ExecutorService pool = (ExecutorService) ReflectionTestUtils.invokeMethod(executor, "graphPoolOrDefault");
        assertNotNull(pool, "兜底池必须存在（无 Spring 上下文时也禁止无界池）");
        ThreadPoolExecutor tpe = assertInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(8, tpe.getMaximumPoolSize());
        assertEquals(200, tpe.getQueue().remainingCapacity() + tpe.getQueue().size());
        assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, tpe.getRejectedExecutionHandler());
    }

    @Test
    void fiftyParallelNodesStayBoundedAndAllComplete() throws Exception {
        AtomicInteger entered = new AtomicInteger(0);
        CountDownLatch release = new CountDownLatch(1);
        GraphExecutor executor = buildExecutor(blockingAgent(entered, release));
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) ReflectionTestUtils.invokeMethod(executor, "graphPoolOrDefault");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
                executor.execute(parallelGraph(50), "u1", "s1", "hi").blockingSubscribe());

        awaitConcurrencyWindow(entered, tpe);

        // 并发窗口断言：活跃线程数与历史峰值均不超过池上限，且远小于 50（无线程爆炸）
        assertTrue(tpe.getActiveCount() <= tpe.getMaximumPoolSize(),
                "activeCount 超限: " + tpe.getActiveCount());
        assertTrue(tpe.getLargestPoolSize() <= tpe.getMaximumPoolSize(),
                "largestPoolSize 超限: " + tpe.getLargestPoolSize());
        assertTrue(tpe.getLargestPoolSize() < 50,
                "不得为 50 个并发各建线程, largest=" + tpe.getLargestPoolSize());

        release.countDown();
        run.get(10, TimeUnit.SECONDS);
        assertEquals(50, tpe.getCompletedTaskCount(), "50 个并行节点应全部完成");
    }

    @Test
    void fiftyGraphFlowNodesStayBoundedAndAllComplete() throws Exception {
        AtomicInteger entered = new AtomicInteger(0);
        CountDownLatch release = new CountDownLatch(1);
        GraphExecutor executor = buildExecutor(blockingAgent(entered, release));
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) ReflectionTestUtils.invokeMethod(executor, "graphPoolOrDefault");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
                executor.execute(graphFlowGraph(50), "u1", "s1", "hi").blockingSubscribe());

        // 首批并发 49 个就绪节点（> max 8），等待核心线程全部进入 execute
        awaitConcurrencyWindow(entered, tpe);

        assertTrue(tpe.getActiveCount() <= tpe.getMaximumPoolSize(),
                "activeCount 超限: " + tpe.getActiveCount());
        assertTrue(tpe.getLargestPoolSize() <= tpe.getMaximumPoolSize(),
                "largestPoolSize 超限: " + tpe.getLargestPoolSize());
        assertTrue(tpe.getLargestPoolSize() < 50,
                "GRAPHFLOW 并发批次不得各建线程, largest=" + tpe.getLargestPoolSize());

        release.countDown();
        run.get(10, TimeUnit.SECONDS);
        assertEquals(50, tpe.getCompletedTaskCount(), "50 个 GRAPHFLOW 节点应全部完成");
    }

    /** 等待核心线程全部进入 execute（真实并发窗口）；超时则失败。 */
    private void awaitConcurrencyWindow(AtomicInteger entered, ThreadPoolExecutor tpe) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (entered.get() < tpe.getCorePoolSize() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(entered.get() >= tpe.getCorePoolSize(),
                "核心线程应全部进入 execute, entered=" + entered.get());
    }
}

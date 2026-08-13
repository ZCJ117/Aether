package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubagentLifecycleLiveLogIntegrationTest {

    private SubAgentBoundary boundary;
    private DefaultAgentFactory agentFactory;
    private ResultRefiner refiner;
    private ExecutorService executor;
    private DelegationLiveLog liveLog;
    private SubagentLifecycleService service;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        boundary = mock(SubAgentBoundary.class);
        agentFactory = mock(DefaultAgentFactory.class);
        refiner = mock(ResultRefiner.class);
        executor = Executors.newCachedThreadPool();
        liveLog = mock(DelegationLiveLog.class);
        service = new SubagentLifecycleService(boundary, agentFactory, refiner, executor, liveLog, 100);
        config = mock(AgentConfig.class);
        when(config.getName()).thenReturn("sub-agent");
        when(config.getCancelToken()).thenReturn(new CancelToken());
        when(boundary.createIsolatedConfig(any(), any(), any(), any(), any(), any())).thenReturn(config);
    }

    private DelegationTask task() {
        return new DelegationTask("分析代码", List.of("code"), null, "u1", "s1", "parent");
    }

    @Test
    void completionAppendsEventsAndClosesWithSummary() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(
                Flowable.just(RuntimeEvent.text("结论")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of("code", 1)));

        String id = "ad-1";
        service.launch(id, task());
        assertTrue(service.wait(id, 5000));

        verify(liveLog).open(eq(id), eq("分析代码"));
        verify(liveLog).append(eq(id), eq("assistant"), eq("结论"));
        verify(liveLog).close(eq(id), contains("COMPLETED"));
    }

    @Test
    void toolResultEventAppendsResultLine() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        RuntimeEvent toolResult = RuntimeEvent.builder()
                .type(RuntimeEvent.EventType.toolResult)
                .toolCallId("c1").toolName("code").toolOutput("done").toolError(false)
                .build();
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.just(toolResult));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[done]", Map.of()));

        String id = "ad-2";
        service.launch(id, task());
        assertTrue(service.wait(id, 5000));

        verify(liveLog).append(eq(id), eq("result"), contains("code ok"));
    }

    @Test
    void cancelClosesWithCancelled() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "", Map.of()));

        String id = "ad-3";
        service.launch(id, task());
        Thread.sleep(100);
        assertTrue(service.cancel(id));
        assertTrue(service.wait(id, 5000));

        verify(liveLog).close(eq(id), contains("CANCELLED"));
    }

    @Test
    void cancelBeforeWorkerStartsDoesNotLeakSubagentIdMdc() throws Exception {
        // 单线程池保证运行 runAgent 的工作线程是已知的同一线程，探测可确定性读取其 MDC
        ExecutorService single = Executors.newSingleThreadExecutor();
        SubagentLifecycleService svc = new SubagentLifecycleService(boundary, agentFactory, refiner, single);

        // 先用阻塞任务占满唯一的工作线程，让 launch 的任务停留在 QUEUED
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        single.submit(() -> {
            blockerStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        String id = "ad-4";
        svc.launch(id, task());
        // 抢占在工作线程消费队列之前取消（QUEUED→CANCELLED CAS 竞争）
        assertTrue(svc.cancel(id));

        release.countDown(); // 放行工作线程，runAgent 命中启动前已取消 → 早退
        assertTrue(svc.wait(id, 5000));

        // 在同一个工作线程上探测 runAgent 之后（FIFO）其 MDC 是否残留 subagentId
        AtomicReference<String> workerMdc = new AtomicReference<>();
        Future<?> probe = single.submit(() ->
                workerMdc.set(org.slf4j.MDC.get("subagentId")));
        probe.get(5, TimeUnit.SECONDS); // 保证 runAgent 已先于探测完成

        // 早退路径不应在池线程 MDC 泄漏 subagentId
        assertEquals(null, workerMdc.get());
    }
}

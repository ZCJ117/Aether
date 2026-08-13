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
    void runAgentEarlyReturnCleansSubagentIdMdc() throws Exception {
        // 白盒：cancel-before-start 公开流不可达（supplyAsync 会跳过已 complete future 的 supplier），
        // 故直接驱动 runAgent 的早退路径，验证防御性修复的 MDC 清理契约。
        ExecutorService single = Executors.newSingleThreadExecutor();
        SubagentLifecycleService svc = new SubagentLifecycleService(boundary, agentFactory, refiner, single);

        AgentConfig cfg = mock(AgentConfig.class);
        when(cfg.getName()).thenReturn("sub-agent");
        when(cfg.getCancelToken()).thenReturn(new CancelToken());
        SubagentRuntime rt = new SubagentRuntime("ad-9", task(), cfg, mock(Agent.class), cfg.getCancelToken());
        assertTrue(rt.toCancelled(), "预置 CANCELLED 状态以触发早退");

        java.lang.reflect.Method m = SubagentLifecycleService.class.getDeclaredMethod("runAgent", SubagentRuntime.class);
        m.setAccessible(true);

        // 早退应发生在反射调用所在线程（worker），该线程无泄漏
        AtomicReference<String> leaked = new AtomicReference<>();
        Future<?> f = single.submit(() -> {
            try {
                m.invoke(svc, rt);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            leaked.set(org.slf4j.MDC.get("subagentId"));
        });
        f.get(5, TimeUnit.SECONDS);
        single.shutdownNow();

        assertEquals(null, leaked.get(), "早退路径不应泄漏 subagentId 到 worker 线程 MDC");
    }
}

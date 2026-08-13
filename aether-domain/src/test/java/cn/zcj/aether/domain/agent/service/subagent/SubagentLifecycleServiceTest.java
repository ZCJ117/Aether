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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubagentLifecycleServiceTest {

    private SubAgentBoundary boundary;
    private DefaultAgentFactory agentFactory;
    private ResultRefiner refiner;
    private ExecutorService executor;
    private SubagentLifecycleService service;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        boundary = mock(SubAgentBoundary.class);
        agentFactory = mock(DefaultAgentFactory.class);
        refiner = mock(ResultRefiner.class);
        executor = Executors.newCachedThreadPool();
        service = new SubagentLifecycleService(boundary, agentFactory, refiner, executor);
        config = mock(AgentConfig.class);
        when(config.getName()).thenReturn("sub-agent");
        when(config.getCancelToken()).thenReturn(new CancelToken());
        when(boundary.createIsolatedConfig(any(), any(), any(), any(), any(), any())).thenReturn(config);
    }

    private static RuntimeEvent textEvent(String text) {
        return RuntimeEvent.text(text); // RuntimeEvent 静态工厂（builder 亦可）
    }

    private DelegationTask task() {
        return new DelegationTask("分析代码", List.of("code"), null, "u1", "s1", "parent");
    }

    @Test
    void launchRunsToCompletion() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(textEvent("结论")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of("code", 1)));

        String id = "ad-1";
        service.launch(id, task()); // launch 返回 CompletableFuture，此处忽略
        assertTrue(service.wait(id, 5000), "wait 应在超时前完成");

        assertEquals(SubagentState.COMPLETED, service.status(id).orElseThrow());
        assertTrue(service.result(id).isPresent());
        assertEquals("成功", service.result(id).orElseThrow().status());
    }

    @Test
    void cancelSetsCancelledAndTerminates() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        // 永不完成的事件流：cancel token 生效前一直阻塞
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("未完成", "[无文本结论]", Map.of()));

        String id = "ad-2";
        service.launch(id, task()); // launch 返回 CompletableFuture，此处忽略
        assertTrue(service.cancel(id), "cancel 应被接受");

        // cancel token 已取消 → takeUntil 终止 → worker 收尾
        assertTrue(service.wait(id, 5000));
        assertEquals(SubagentState.CANCELLED, service.status(id).orElseThrow());
    }

    @Test
    void cancelAfterCompletionIsNoOp() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(textEvent("结论")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of()));

        String id = "ad-3";
        service.launch(id, task()); // launch 返回 CompletableFuture，此处忽略
        assertTrue(service.wait(id, 5000));
        assertFalse(service.cancel(id), "终态后 cancel 应拒绝（对齐 hermes L291 already_terminal）");
    }

    @Test
    void detectStaleMarksTimedOut() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("未完成", "[无文本结论]", Map.of()));

        String id = "ad-4";
        service.launch(id, task()); // launch 返回 CompletableFuture，此处忽略
        // 等 worker 进入 RUNNING
        Thread.sleep(200);
        assertEquals(SubagentState.RUNNING, service.status(id).orElseThrow());

        service.rewindHeartbeatForTest(id, Duration.ofMinutes(31));
        List<String> stale = service.detectStale(Duration.ofMinutes(30));
        assertTrue(stale.contains(id), "RUNNING 且心跳过期应判 TIMED_OUT");
        assertEquals(SubagentState.TIMED_OUT, service.status(id).orElseThrow());
    }

    @Test
    void waitTimesOutOnNeverCompletingAgent() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());

        String id = "ad-5";
        service.launch(id, task()); // launch 返回 CompletableFuture，此处忽略
        assertFalse(service.wait(id, 100), "超时应返回 false（对齐 hermes timed_out 标志）");
        service.cancel(id); // 清理
    }

    @Test
    void launchWithShortTaskDoesNotThrow() {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.just(textEvent("x")));

        // task="" 的 hashCode=0 → Integer.toHexString(0)="0" → substring(0,6) 越界
        assertDoesNotThrow(() -> service.launch("ad-short",
                new DelegationTask("", List.of(), null, "u1", "s1", null)));
    }
}

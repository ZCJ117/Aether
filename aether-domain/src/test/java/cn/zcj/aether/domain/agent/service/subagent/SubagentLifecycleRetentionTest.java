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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubagentLifecycleRetentionTest {

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
        service = new SubagentLifecycleService(boundary, agentFactory, refiner, executor, null, 3);
        config = mock(AgentConfig.class);
        when(config.getName()).thenReturn("sub-agent");
        when(config.getCancelToken()).thenReturn(new CancelToken());
        when(boundary.createIsolatedConfig(any(), any(), any(), any(), any(), any())).thenReturn(config);
    }

    private void launchToCompletion(String id) {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(RuntimeEvent.text("x")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[x]", Map.of()));
        service.launch(id, new DelegationTask("retention-test-task", List.of(), null, "u1", "s1", null));
    }

    @Test
    void terminalRetentionEvictsOldestCompleted() throws Exception {
        launchToCompletion("ad-1");
        launchToCompletion("ad-2");
        launchToCompletion("ad-3");
        launchToCompletion("ad-4"); // 触发逐出，保留最近 3 个

        assertTrue(service.wait("ad-1", 5000));
        assertTrue(service.wait("ad-4", 5000));

        // 最旧的 ad-1 已被逐出 → status 返回 empty；最近 3 个仍在
        assertTrue(service.status("ad-1").isEmpty(), "最旧终态应被逐出");
        assertEquals(SubagentState.COMPLETED, service.status("ad-4").orElseThrow());
    }

    @Test
    void activeRuntimesAreNeverEvicted() throws Exception {
        // 永不完成的事件流（保持 RUNNING）
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "", Map.of()));

        service.launch("ad-keep", new DelegationTask("retention-keep-task", List.of(), null, "u1", "s1", null));
        launchToCompletion("ad-1");
        launchToCompletion("ad-2");
        launchToCompletion("ad-3");
        launchToCompletion("ad-4");

        Thread.sleep(200);
        assertEquals(SubagentState.RUNNING, service.status("ad-keep").orElseThrow(),
                "active 运行时永不逐出");
    }
}

package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.hook.LifecycleHook;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3：验证 SubAgentOrchestrator.dispatch 触发 SUBAGENT_START / SUBAGENT_STOP 生命周期钩子。
 */
class SubAgentOrchestratorHookTest {

    @Test
    void dispatchFiresSubAgentStartAndStop() {
        AtomicInteger start = new AtomicInteger(0);
        AtomicInteger stop = new AtomicInteger(0);
        HookRegistry registry = new HookRegistry();
        registry.registerLifecycle(new LifecycleHook() {
            @Override
            public Set<HookPoint> points() {
                return Set.of(HookPoint.SUBAGENT_START, HookPoint.SUBAGENT_STOP);
            }

            @Override
            public void onHook(HookPoint point, HookContext ctx) {
                if (point == HookPoint.SUBAGENT_START) start.incrementAndGet();
                else if (point == HookPoint.SUBAGENT_STOP) stop.incrementAndGet();
            }
        });

        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        Agent agent = mock(Agent.class);
        when(factory.create(any(AgentConfig.class))).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.empty());

        SubAgentOrchestrator orchestrator = new SubAgentOrchestrator(factory,
                new SubAgentBoundary(), new ResultRefiner());
        orchestrator.setHookRegistryForTest(registry);

        var result = orchestrator.dispatch("do something", List.of(), null, "model-x", "u1", "session-1");

        assertEquals(1, start.get(), "SUBAGENT_START 应触发 1 次");
        assertEquals(1, stop.get(), "SUBAGENT_STOP 应触发 1 次");
        assertNotNull(result);
    }
}

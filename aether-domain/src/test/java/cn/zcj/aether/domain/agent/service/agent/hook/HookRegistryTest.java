package cn.zcj.aether.domain.agent.service.agent.hook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HookRegistryTest {

    private HookRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new HookRegistry();
    }

    @Test
    void invokesInOrder() {
        AtomicInteger seq = new AtomicInteger(0);
        AtomicInteger first = new AtomicInteger(-1);
        AtomicInteger second = new AtomicInteger(-1);
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() { return Set.of(HookPoint.ON_SESSION_START); }
            @Override public int order() { return 10; }
            @Override public void onHook(HookPoint point, HookContext ctx) { first.set(seq.getAndIncrement()); }
        });
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() { return Set.of(HookPoint.ON_SESSION_START); }
            @Override public int order() { return 20; }
            @Override public void onHook(HookPoint point, HookContext ctx) { second.set(seq.getAndIncrement()); }
        });

        registry.invokeAll(HookPoint.ON_SESSION_START, HookContext.builder().sessionId("s1").build());

        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void exceptionInOneHookDoesNotBlockOthers() {
        AtomicInteger reached = new AtomicInteger(0);
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() { return Set.of(HookPoint.SUBAGENT_START); }
            @Override public void onHook(HookPoint point, HookContext ctx) { throw new RuntimeException("boom"); }
        });
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() { return Set.of(HookPoint.SUBAGENT_START); }
            @Override public void onHook(HookPoint point, HookContext ctx) { reached.incrementAndGet(); }
        });

        registry.invokeAll(HookPoint.SUBAGENT_START, HookContext.builder().agentId("a1").build());

        assertEquals(1, reached.get(), "异常 hook 不应阻断后续 hook");
    }

    @Test
    void emptyHookListShortCircuits() {
        registry.invokeAll(HookPoint.ON_GRAPH_FINALIZE, HookContext.builder().sessionId("s1").build());
        assertTrue(registry.hooksFor(HookPoint.ON_GRAPH_FINALIZE).isEmpty());
    }

    @Test
    void hookOnlyFiresOnItsPoint() {
        AtomicInteger fired = new AtomicInteger(0);
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() { return Set.of(HookPoint.ON_SESSION_END); }
            @Override public void onHook(HookPoint point, HookContext ctx) { fired.incrementAndGet(); }
        });
        registry.invokeAll(HookPoint.ON_SESSION_START, HookContext.builder().build());
        assertEquals(0, fired.get());
        registry.invokeAll(HookPoint.ON_SESSION_END, HookContext.builder().build());
        assertEquals(1, fired.get());
    }

    @Test
    void hooksForReturnsUnmodifiableSortedList() {
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() { return Set.of(HookPoint.PRE_API_REQUEST); }
            @Override public int order() { return 5; }
            @Override public void onHook(HookPoint point, HookContext ctx) {}
        });
        var list = registry.hooksFor(HookPoint.PRE_API_REQUEST);
        assertEquals(1, list.size());
        assertThrows(UnsupportedOperationException.class, () -> list.add(null));
    }
}

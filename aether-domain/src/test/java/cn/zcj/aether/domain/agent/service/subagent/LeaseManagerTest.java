package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeaseManagerTest {

    @Test
    void acquiresUpToMaxPerSession() {
        LeaseManager mgr = new LeaseManager(3);
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertEquals(3, mgr.activeLeases("s1"));
    }

    @Test
    void rejectsBeyondMaxPerSession() {
        LeaseManager mgr = new LeaseManager(2);
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertFalse(mgr.acquireLease("s1"), "超出每 session 上限应拒绝（不排队，对齐 hermes L678）");
    }

    @Test
    void releaseFreesSlot() {
        LeaseManager mgr = new LeaseManager(2);
        mgr.acquireLease("s1");
        mgr.acquireLease("s1");
        mgr.releaseLease("s1");
        assertTrue(mgr.acquireLease("s1"), "释放后应重新获得槽位");
        assertEquals(2, mgr.activeLeases("s1"));
    }

    @Test
    void sessionsAreIndependent() {
        LeaseManager mgr = new LeaseManager(1);
        mgr.acquireLease("s1");
        assertTrue(mgr.acquireLease("s2"), "不同 session 互不影响");
        assertFalse(mgr.acquireLease("s1"));
    }

    @Test
    void concurrentAcquireNeverExceedsMax() throws Exception {
        LeaseManager mgr = new LeaseManager(3);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (mgr.acquireLease("s1")) {
                    successes.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(3, successes.get(), "并发下成功获取数不得超上限");
        assertEquals(3, mgr.activeLeases("s1"));
    }

    @Test
    void releaseIsIdempotentAndUnknownSessionSafe() {
        LeaseManager mgr = new LeaseManager(2);
        mgr.acquireLease("s1");
        mgr.releaseLease("s1");
        mgr.releaseLease("s1");       // 已归零再释放 → 幂等不塌陷
        mgr.releaseLease("unknown");  // 未获取过 → 无操作
        assertEquals(0, mgr.activeLeases("s1"));
        assertEquals(0, mgr.activeLeases("unknown"));
    }
}

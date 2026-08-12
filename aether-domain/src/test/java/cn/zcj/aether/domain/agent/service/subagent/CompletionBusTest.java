package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CompletionBusTest {

    private static final DelegationCompletion COMPLETION = new DelegationCompletion(
            "ad-1", "s1", "a1", "task", SubagentState.COMPLETED, "ok", Instant.now());

    @Test
    void publishNotifiesSubscribers() {
        CompletionBus bus = new CompletionBus();
        AtomicReference<DelegationCompletion> received = new AtomicReference<>();
        bus.subscribe(received::set);
        bus.publish(COMPLETION);
        assertEquals("ad-1", received.get().delegationId());
    }

    @Test
    void subscriberExceptionDoesNotBlockOthers() {
        CompletionBus bus = new CompletionBus();
        AtomicInteger reached = new AtomicInteger(0);
        bus.subscribe(c -> { throw new RuntimeException("boom"); });
        bus.subscribe(c -> reached.incrementAndGet());
        bus.publish(COMPLETION);
        assertEquals(1, reached.get(), "异常订阅者不应阻断其它订阅者");
    }

    @Test
    void subscribeFromPersistenceReplaysUndeliveredAndMarksDelivered() {
        DelegationRecord rec = DelegationRecord.builder()
                .id("ad-9").parentSessionId("s1").parentAgentId("a1")
                .taskPayload("task").state(SubagentState.COMPLETED).resultSummary("ok")
                .updatedAt(Instant.now()).build();
        AsyncDelegationStore store = new AsyncDelegationStore() {
            @Override public void save(DelegationRecord record) {}
            @Override public List<DelegationRecord> listBySession(String sessionId) { return List.of(); }
            @Override public List<DelegationRecord> findPendingStale(Instant staleBefore, int limit) { return List.of(); }
            @Override public List<DelegationRecord> findUndeliveredTerminal(int limit) { return List.of(rec); }
            @Override public void markTerminal(String id, SubagentState terminal, String resultSummary) {}
            @Override public void markQueuedForRetry(String id, int newAttemptCount) {}
            @Override public void updateHeartbeat(String id, Instant at) {}
            @Override public void markCompletionDelivered(String id) { deliveredCount.incrementAndGet(); }
        };
        CompletionBus bus = new CompletionBus();
        AtomicInteger published = new AtomicInteger(0);
        bus.subscribe(c -> published.incrementAndGet());

        int replayed = bus.subscribeFromPersistence(store);

        assertEquals(1, replayed);
        assertEquals(1, published.get(), "回灌事件应发布给订阅者");
        assertEquals(1, deliveredCount.get(), "投递后应标记 delivered 去重");
    }

    @Test
    void subscribeFromPersistenceSkipsNullStore() {
        CompletionBus bus = new CompletionBus();
        assertEquals(0, bus.subscribeFromPersistence(null));
    }

    // 匿名 store 的 deliveredCount 捕获
    private static final java.util.concurrent.atomic.AtomicInteger deliveredCount = new java.util.concurrent.atomic.AtomicInteger(0);
}

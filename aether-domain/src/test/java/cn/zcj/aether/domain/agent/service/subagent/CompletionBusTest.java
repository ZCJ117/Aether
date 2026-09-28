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

    /** T5-1：publish 返回实际投递成功的订阅者数（唯一的"是否真正投递"判据）。 */
    @Test
    void publishReturnsDeliveredSubscriberCount() {
        CompletionBus bus = new CompletionBus();
        assertEquals(0, bus.publish(COMPLETION), "无订阅者 → 投递数 0");

        bus.subscribe(c -> { });
        bus.subscribe(c -> { });
        assertEquals(2, bus.publish(COMPLETION), "2 个订阅者 → 投递数 2");
    }

    /** T5-1：抛异常的订阅者不计入返回值。 */
    @Test
    void publishExcludesThrowingSubscribersFromCount() {
        CompletionBus bus = new CompletionBus();
        AtomicInteger reached = new AtomicInteger(0);
        bus.subscribe(c -> { throw new RuntimeException("boom"); });
        bus.subscribe(c -> reached.incrementAndGet());

        assertEquals(1, bus.publish(COMPLETION), "抛异常的订阅者不应计入投递数");
        assertEquals(1, reached.get());
    }

    /**
     * 报告型订阅者：{@code deliver} 返回 false 不计入投递数。
     * 这是"投递失败可回灌"的基础 —— 若错误计入，调用方会置位 completion_delivered 而事件已丢失。
     */
    @Test
    void publishExcludesSubscribersReportingFailure() {
        CompletionBus bus = new CompletionBus();
        bus.subscribeReporting(c -> false);
        bus.subscribeReporting(c -> true);

        assertEquals(1, bus.publish(COMPLETION), "报告 false 的订阅者不应计入投递数");
    }

    /** 报告型订阅者抛异常同样不计入（热路径安全：异常与返回 false 等价，均不阻断其他订阅者）。 */
    @Test
    void publishExcludesThrowingReportingSubscriber() {
        CompletionBus bus = new CompletionBus();
        bus.subscribeReporting(c -> { throw new RuntimeException("boom"); });
        bus.subscribeReporting(c -> true);

        assertEquals(1, bus.publish(COMPLETION));
    }

    /** subscribe(Consumer) 语义不变：正常返回即视为投递成功（向后兼容）。 */
    @Test
    void subscribeConsumerStillCountsAsDelivered() {
        CompletionBus bus = new CompletionBus();
        AtomicInteger reached = new AtomicInteger(0);
        bus.subscribe(c -> reached.incrementAndGet());

        assertEquals(1, bus.publish(COMPLETION));
        assertEquals(1, reached.get());
    }

    /**
     * T5-9：无订阅者时回灌<b>不得</b>置位投递标记。
     * 若误标，回灌依赖的 {@code completion_delivered = FALSE} 查询将永远捞不到数据 —— 静默数据丢失。
     */
    @Test
    void subscribeFromPersistenceDoesNotMarkWhenNoSubscriber() {
        AtomicInteger marked = new AtomicInteger(0);
        CompletionBus bus = new CompletionBus();

        int replayed = bus.subscribeFromPersistence(storeWithOneUndelivered(marked));

        assertEquals(0, replayed, "无订阅者 → 成功投递条数为 0");
        assertEquals(0, marked.get(), "投递失败不得置位 completion_delivered（否则回灌永久失效）");
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

    /** 含一条未投递终态记录的 store；markCompletionDelivered 计入 marked。 */
    private static AsyncDelegationStore storeWithOneUndelivered(AtomicInteger marked) {
        DelegationRecord rec = DelegationRecord.builder()
                .id("ad-9").parentSessionId("s1").parentAgentId("a1")
                .taskPayload("task").state(SubagentState.COMPLETED).resultSummary("ok")
                .updatedAt(Instant.now()).build();
        return new AsyncDelegationStore() {
            @Override public void save(DelegationRecord record) {}
            @Override public List<DelegationRecord> listBySession(String sessionId) { return List.of(); }
            @Override public List<DelegationRecord> findPendingStale(Instant staleBefore, int limit) { return List.of(); }
            @Override public List<DelegationRecord> findUndeliveredTerminal(int limit) { return List.of(rec); }
            @Override public void markTerminal(String id, SubagentState terminal, String resultSummary) {}
            @Override public void markQueuedForRetry(String id, int newAttemptCount) {}
            @Override public void updateHeartbeat(String id, Instant at) {}
            @Override public void markCompletionDelivered(String id) { marked.incrementAndGet(); }
        };
    }
}

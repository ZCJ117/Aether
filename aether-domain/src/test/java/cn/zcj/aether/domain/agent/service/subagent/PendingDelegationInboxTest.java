package cn.zcj.aether.domain.agent.service.subagent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3/VUL-05 F5-3：父会话委派结果收件箱的 offer/drain 语义、边界与溢出行为。
 * 对应验收用例 T5-3 / T5-4 / T5-5。
 */
class PendingDelegationInboxTest {

    private static DelegationCompletion completion(String id) {
        return new DelegationCompletion(id, "s1", "a1", "task",
                SubagentState.COMPLETED, "ok", Instant.now());
    }

    /** T5-3：offer 3 条 → drain 返回 3 条且 FIFO；再次 drain 为空；size 归零。 */
    @Test
    void offerThenDrainReturnsFifoAndEmptiesBucket() {
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);

        inbox.offer("s1", completion("ad-1"));
        inbox.offer("s1", completion("ad-2"));
        inbox.offer("s1", completion("ad-3"));
        assertEquals(3, inbox.size());

        List<DelegationCompletion> drained = inbox.drain("s1");
        assertEquals(List.of("ad-1", "ad-2", "ad-3"),
                drained.stream().map(DelegationCompletion::delegationId).toList(),
                "drain 必须按 FIFO 返回全部待处理项");

        assertTrue(inbox.drain("s1").isEmpty(), "已 drain 的条目不得重复返回");
        assertEquals(0, inbox.size());
    }

    /** 分桶隔离：一个会话的 drain 不影响另一个会话。 */
    @Test
    void bucketsAreIsolatedBySession() {
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        inbox.offer("s1", completion("ad-1"));
        inbox.offer("s2", completion("ad-2"));

        assertEquals(1, inbox.drain("s1").size());
        assertEquals(0, inbox.drain("s1").size());
        assertEquals(1, inbox.drain("s2").size(), "s1 的 drain 不应影响 s2");
    }

    /** T5-4：null/空白 sessionId 被忽略且不抛 NPE。 */
    @Test
    void nullOrBlankSessionIdIsIgnored() {
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);

        assertDoesNotThrow(() -> inbox.offer(null, completion("ad-1")));
        assertDoesNotThrow(() -> inbox.offer("   ", completion("ad-2")));

        assertEquals(0, inbox.size(), "空白 sessionId 不得写入桶（ConcurrentHashMap 不允许 null 键）");
        assertTrue(inbox.drain(null).isEmpty());
        assertTrue(inbox.drain("   ").isEmpty());
    }

    /** T5-5：offer 51 条 → size==50，最旧一条不在结果中，dropped 计数 +1。 */
    @Test
    void overflowDropsOldestAndCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingDelegationInbox inbox = new PendingDelegationInbox(providerOf(registry));

        for (int i = 0; i < PendingDelegationInbox.MAX_PER_SESSION + 1; i++) {
            inbox.offer("s1", completion("ad-" + i));
        }

        assertEquals(PendingDelegationInbox.MAX_PER_SESSION, inbox.size());

        List<String> ids = inbox.drain("s1").stream()
                .map(DelegationCompletion::delegationId).toList();
        assertEquals(PendingDelegationInbox.MAX_PER_SESSION, ids.size());
        assertFalse(ids.contains("ad-0"), "溢出时应丢弃队首最旧一条");
        assertEquals("ad-1", ids.get(0), "保留的应当是次旧的一条");

        assertEquals(1.0, registry.get("aether.delegation.inbox.dropped").counter().count(),
                "丢弃最旧一条应计入 aether.delegation.inbox.dropped");
    }

    /** T5-5 加强：连续溢出按"被丢弃的条数"累加，而非每次只计 1。 */
    @Test
    void overflowCountsEveryDroppedItem() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingDelegationInbox inbox = new PendingDelegationInbox(providerOf(registry));

        int overflow = 5;
        for (int i = 0; i < PendingDelegationInbox.MAX_PER_SESSION + overflow; i++) {
            inbox.offer("s1", completion("ad-" + i));
        }

        assertEquals(PendingDelegationInbox.MAX_PER_SESSION, inbox.size());
        assertEquals((double) overflow,
                registry.get("aether.delegation.inbox.dropped").counter().count(),
                "丢弃计数应按被丢弃条数累加");

        List<String> ids = inbox.drain("s1").stream()
                .map(DelegationCompletion::delegationId).toList();
        assertEquals("ad-5", ids.get(0), "应保留最新的 50 条（队首为 ad-5）");
    }

    /** §7.1.8：{@code aether.delegation.inbox.size} Gauge 反映跨会话待处理总数。 */
    @Test
    void sizeGaugeTracksPendingTotalAcrossSessions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingDelegationInbox inbox = new PendingDelegationInbox(providerOf(registry));

        assertEquals(0.0, registry.get("aether.delegation.inbox.size").gauge().value());

        inbox.offer("s1", completion("ad-1"));
        inbox.offer("s1", completion("ad-2"));
        inbox.offer("s2", completion("ad-3"));
        assertEquals(3.0, registry.get("aether.delegation.inbox.size").gauge().value(),
                "Gauge 应反映所有会话的待处理总数");

        inbox.drain("s1");
        assertEquals(1.0, registry.get("aether.delegation.inbox.size").gauge().value(),
                "drain 后 Gauge 应随之下降");
    }

    /** drain 后桶被移除，避免 map 随会话数无界增长。 */
    @Test
    void drainRemovesEmptyBucket() throws Exception {
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        inbox.offer("s1", completion("ad-1"));
        assertEquals(1, bucketsOf(inbox).size());

        inbox.drain("s1");

        assertTrue(bucketsOf(inbox).isEmpty(), "drain 清空后应移除该会话的桶");
    }

    /** 无 MeterRegistry 时不打点也不抛异常（actuator 未装配的部署形态）。 */
    @Test
    void worksWithoutMeterRegistry() {
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        for (int i = 0; i < PendingDelegationInbox.MAX_PER_SESSION + 5; i++) {
            inbox.offer("s1", completion("ad-" + i));
        }
        assertEquals(PendingDelegationInbox.MAX_PER_SESSION, inbox.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> bucketsOf(PendingDelegationInbox inbox) throws Exception {
        Field f = PendingDelegationInbox.class.getDeclaredField("buckets");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(inbox);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}

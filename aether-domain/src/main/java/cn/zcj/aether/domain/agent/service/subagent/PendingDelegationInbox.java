package cn.zcj.aether.domain.agent.service.subagent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 父会话委派结果收件箱 — 按 parentSessionId 分桶的短期内存队列。
 *
 * <p><b>为什么用内存收件箱而不是直接把结果写进消息历史？</b><br>
 * 异步委派的完成时刻，父 Agent 可能已经完全结束（会话空闲）。写消息历史需要会话仍然活着；
 * 而收件箱按 sessionId 分桶，父会话下次启动时（{@code ReActAgent.queryLoop} Phase 1）消费即可。
 * 容器重启后丢失的部分由 {@code CompletionBus.subscribeFromPersistence} 回灌补齐
 * ——这正是"投递标记必须以真正投递成功为条件"（见 {@code CompletionBus.publish}）必须正确的原因。</p>
 *
 * <p>桶上限 {@value #MAX_PER_SESSION} 条：父会话永不恢复时属可接受的有界泄漏（驻留内存），
 * 容器重启后由回灌补齐。</p>
 */
@Slf4j
@Component
public class PendingDelegationInbox {

    /** 每会话桶上限；超出丢弃队首最旧一条并计数，不阻塞发布方。 */
    public static final int MAX_PER_SESSION = 50;

    /** sessionId -> 待父会话消费的完成事件（FIFO）。 */
    private final Map<String, ConcurrentLinkedDeque<DelegationCompletion>> buckets = new ConcurrentHashMap<>();

    /** 超限丢弃计数（无 MeterRegistry 时为 null）。 */
    private final Counter dropped;

    /**
     * Spring 构造：{@code MeterRegistry} 可选（actuator 未装配时为 null → 跳过打点，不影响正确性）。
     * 测试可直接 {@code new PendingDelegationInbox(null)}。
     */
    public PendingDelegationInbox(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry registry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
        if (registry == null) {
            this.dropped = null;
            return;
        }
        this.dropped = Counter.builder("aether.delegation.inbox.dropped")
                .description("因超出每会话上限被丢弃的委派完成事件数")
                .register(registry);
        Gauge.builder("aether.delegation.inbox.size", this, PendingDelegationInbox::size)
                .description("当前待处理的委派完成事件总数")
                .register(registry);
    }

    /**
     * 入队到父会话桶；超过上限丢弃最旧一条并计数。
     *
     * <p>sessionId 为 null/空白时忽略该条并 log.warn —— 不得写入 null 键
     * （{@code ConcurrentHashMap} 不允许 null key，会抛 NPE）。</p>
     */
    public void offer(String sessionId, DelegationCompletion completion) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("PendingDelegationInbox: 忽略 parentSessionId 为空的完成事件 id={}",
                    completion != null ? completion.delegationId() : null);
            return;
        }
        if (completion == null) {
            return;
        }
        // compute 而非 computeIfAbsent：与 drain 的 computeIfPresent 在同一键上互斥，
        // 避免"drain 清空后移除键"与"并发 offer 新建条目"交错导致条目被误删。
        buckets.compute(sessionId, (k, bucket) -> {
            ConcurrentLinkedDeque<DelegationCompletion> b =
                    bucket != null ? bucket : new ConcurrentLinkedDeque<>();
            b.addLast(completion);
            while (b.size() > MAX_PER_SESSION) {
                if (b.pollFirst() != null && dropped != null) {
                    dropped.increment();
                }
            }
            return b;
        });
    }

    /**
     * 取出并清空该会话的全部待处理项（FIFO）；sessionId 空白时返回空列表。
     *
     * <p>用 {@code pollFirst()} 循环直到 null，天然无重复消费：并发调用中只有一方能取到条目。</p>
     */
    public List<DelegationCompletion> drain(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<DelegationCompletion> drained = new ArrayList<>();
        // 清空后返回 null 移除键，避免 map 无界增长（父会话永不恢复时的有界收敛）
        buckets.computeIfPresent(sessionId, (k, bucket) -> {
            DelegationCompletion c;
            while ((c = bucket.pollFirst()) != null) {
                drained.add(c);
            }
            return null;
        });
        return drained;
    }

    /** 当前待处理总数（观测用）。 */
    public int size() {
        int total = 0;
        for (ConcurrentLinkedDeque<DelegationCompletion> bucket : buckets.values()) {
            total += bucket.size();
        }
        return total;
    }

    /** 清空所有桶（仅为资源释放）。 */
    @PreDestroy
    public void clear() {
        buckets.clear();
    }
}

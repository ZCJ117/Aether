package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 委派完成总线 — 对齐 hermes process_registry.completion_queue（L173）。
 * <p>hermes 用无界 queue.Queue + drain_notifications（L1235）；Aether 等价为
 * 无界 ConcurrentLinkedQueue + 订阅者回调（多消费者，无背压）。
 * subscribeFromPersistence 对齐 restore_undelivered_completions（L344）：
 * 回灌终态未投递 completion，投递后经 markCompletionDelivered 去重。</p>
 */
@Slf4j
@Component
public class CompletionBus {

    /**
     * 可报告投递结果的订阅者。
     *
     * <p>存在的理由：热路径要求订阅者<b>不得抛异常</b>，但"吞掉异常"与"投递失败不得计入投递数"
     * 无法同时用异常表达——所以失败经返回值上报，而非异常。</p>
     */
    @FunctionalInterface
    public interface DeliveryAwareSubscriber {
        /** @return true 表示本次投递成功（计入 publish 返回值）；false 表示未送达（不计入，可被回灌重放） */
        boolean deliver(DelegationCompletion completion);
    }

    private final List<DeliveryAwareSubscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * 发布 completion：通知当前订阅者（订阅者异常隔离，不阻断）。
     *
     * <p>返回值是"是否真正投递"的唯一判据：调用方据此决定能否置位投递标记。
     * 若在无订阅者时无条件置位，回灌依赖的 {@code completion_delivered = FALSE} 查询将永远捞不到数据
     * （静默数据丢失）。</p>
     *
     * <p>订阅者抛异常或返回 false 均不计入；其余订阅者继续。</p>
     *
     * @return 成功接收的订阅者数量（0 = 无订阅者 / 全部抛异常 / 全部报告未送达）
     */
    public int publish(DelegationCompletion completion) {
        int delivered = 0;
        for (DeliveryAwareSubscriber sub : subscribers) {
            try {
                if (sub.deliver(completion)) {
                    delivered++;
                }
            } catch (Exception e) {
                log.warn("CompletionBus 订阅者异常（已隔离）: {}", e.getMessage());
            }
        }
        return delivered;
    }

    /**
     * 注册订阅者（签名与语义不变：正常返回即视为投递成功）。
     *
     * @see #subscribeReporting(DeliveryAwareSubscriber) 需要上报投递失败的订阅者用这个
     */
    public void subscribe(Consumer<DelegationCompletion> subscriber) {
        subscribers.add(c -> {
            subscriber.accept(c);
            return true;
        });
    }

    /**
     * 注册"可报告投递结果"的订阅者：返回 false 时不计入 {@link #publish} 的返回值，
     * 使调用方得以保留 {@code completion_delivered = FALSE} 供下次启动回灌重放。
     *
     * <p>实现方仍不得抛异常（热路径安全）；异常与返回 false 等价。</p>
     */
    public void subscribeReporting(DeliveryAwareSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    /**
     * 启动回灌：扫描 store 终态未投递记录 → 发布 → <b>投递成功才</b> markCompletionDelivered。
     *
     * <p>无订阅者时投递数为 0 → 不置位 → 下次启动继续重放（幂等，不丢）。</p>
     *
     * @return 成功投递（至少一个订阅者接收）的条数
     */
    public int subscribeFromPersistence(AsyncDelegationStore store) {
        if (store == null) {
            return 0;
        }
        int replayed = 0;
        for (DelegationRecord rec : store.findUndeliveredTerminal(100)) {
            DelegationCompletion completion = new DelegationCompletion(
                    rec.getId(), rec.getParentSessionId(), rec.getParentAgentId(),
                    rec.getTaskPayload(), rec.getState(), rec.getResultSummary(), rec.getUpdatedAt());
            if (publish(completion) > 0) {
                store.markCompletionDelivered(rec.getId());
                replayed++;
            }
        }
        return replayed;
    }
}

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

    private final List<Consumer<DelegationCompletion>> subscribers = new CopyOnWriteArrayList<>();

    /** 发布 completion：通知当前订阅者（订阅者异常隔离，不阻断）。 */
    public void publish(DelegationCompletion completion) {
        for (Consumer<DelegationCompletion> sub : subscribers) {
            try {
                sub.accept(completion);
            } catch (Exception e) {
                log.warn("CompletionBus 订阅者异常（已隔离）: {}", e.getMessage());
            }
        }
    }

    /** 注册订阅者。 */
    public void subscribe(Consumer<DelegationCompletion> subscriber) {
        subscribers.add(subscriber);
    }

    /**
     * 启动回灌：扫描 store 终态未投递记录 → 发布 → markCompletionDelivered（去重）。
     * @return 回灌条数
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
            publish(completion);
            store.markCompletionDelivered(rec.getId());
            replayed++;
        }
        return replayed;
    }
}

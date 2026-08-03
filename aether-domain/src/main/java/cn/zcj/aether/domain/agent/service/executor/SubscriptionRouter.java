package cn.zcj.aether.domain.agent.service.executor;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 订阅路由器 — M1 消息路由引擎。
 *
 * <p>评估 Agent 的 watch 订阅声明，将匹配的 MessageEnvelope 投递到订阅者邮箱。
 * 对齐 MetaGPT 的双重过滤语义（订阅 Action 产物 + @我），
 * 以及 AutoGen Topic 发布订阅的去中心化投递。
 *
 * <h3>匹配规则</h3>
 * <ol>
 *   <li>订阅者声明了 {@code watch=[topicA, topicB]}，消息的 {@code topic} 匹配任一即投递</li>
 *   <li>订阅者声明了 {@code watch=["*"]}，接收所有消息</li>
 *   <li>消息的 {@code causeBy} 匹配订阅者 watch 列表中的 Agent 名称即投递</li>
 *   <li>未声明 watch 或 watch 为空的订阅者不接收任何消息</li>
 * </ol>
 *
 * <p>线程安全：无状态，所有状态存于外部 mailbox Map。
 */
@Slf4j
public class SubscriptionRouter {

    /** 通配符 — 匹配所有消息 */
    public static final String WILDCARD = "*";

    /**
     * 将消息路由到匹配的订阅者邮箱。
     *
     * @param message   待路由的消息信封
     * @param mailboxes 订阅者邮箱映射（agentName → BlockingQueue），会被修改
     * @param watches   订阅声明映射（agentName → watch 主题列表）
     * @return 实际投递到的订阅者数量
     */
    public int route(MessageEnvelope message,
            Map<String, BlockingQueue<MessageEnvelope>> mailboxes,
            Map<String, List<String>> watches) {

        int delivered = 0;
        for (Map.Entry<String, List<String>> entry : watches.entrySet()) {
            String agentName = entry.getKey();
            List<String> topics = entry.getValue();

            if (topics == null || topics.isEmpty()) {
                continue; // 无订阅声明 → 不接收
            }

            BlockingQueue<MessageEnvelope> mailbox = mailboxes.get(agentName);
            if (mailbox == null) {
                log.debug("订阅者 [{}] 无邮箱，跳过", agentName);
                continue;
            }

            if (matches(message, topics)) {
                boolean offered = mailbox.offer(message);
                if (offered) {
                    delivered++;
                    log.debug("消息已投递: topic={} → subscriber={}", message.topic(), agentName);
                } else {
                    log.warn("订阅者 [{}] 邮箱已满(容量={})，消息丢弃: topic={}",
                            agentName, mailbox.remainingCapacity() + mailbox.size(), message.topic());
                }
            }
        }

        if (delivered == 0 && message.topic() != null) {
            log.debug("消息无订阅者: topic={}, causeBy={}", message.topic(), message.causeBy());
        }
        return delivered;
    }

    /**
     * 判断消息是否匹配订阅声明。
     *
     * <p>匹配逻辑（短路）：
     * <ol>
     *   <li>通配符 "*" → 匹配所有</li>
     *   <li>topic 精确匹配</li>
     *   <li>causeBy 匹配（接收方 watch 列表中包含发送方名称）</li>
     * </ol>
     */
    private boolean matches(MessageEnvelope message, List<String> watchTopics) {
        String topic = message.topic();
        String causeBy = message.causeBy();

        for (String w : watchTopics) {
            if (WILDCARD.equals(w)) {
                return true;
            }
            // topic 匹配
            if (w.equals(topic)) {
                return true;
            }
            // causeBy 匹配
            if (w.equals(causeBy)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从邮箱中排出所有消息（清空邮箱）。
     *
     * @param mailbox 订阅者邮箱
     * @param timeoutMs 等待超时毫秒
     * @return 排出后的消息列表（按接收顺序）
     */
    public List<MessageEnvelope> drain(BlockingQueue<MessageEnvelope> mailbox, long timeoutMs) {
        List<MessageEnvelope> messages = new ArrayList<>();
        if (mailbox == null) return messages;

        // 先排空已有消息
        mailbox.drainTo(messages);

        // 如果邮箱为空，等待指定时间看是否有新消息到达
        if (messages.isEmpty() && timeoutMs > 0) {
            try {
                MessageEnvelope msg = mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    messages.add(msg);
                    // 继续排空
                    mailbox.drainTo(messages);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 尝试最后一次排空
                mailbox.drainTo(messages);
            }
        }

        return messages;
    }
}

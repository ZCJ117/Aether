package cn.zcj.aether.domain.agent.service.executor;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息信封 — M1 消息路由协议的核心数据结构。
 *
 * <p>对齐 MetaGPT 的 Message 路由三要素（cause_by/sent_from/send_to）和
 * AutoGen 的 Topic 发布订阅，提供声明式 Agent 间消息路由能力。
 *
 * <p>每个 MessageEnvelope 携带完整的路由元数据，
 * SubscriptionRouter 根据 watch 订阅规则将消息投递到订阅者邮箱。
 *
 * @param messageId      消息唯一标识
 * @param content        消息文本内容
 * @param causeBy        产生此消息的 Agent 名称（对齐 MetaGPT cause_by）
 * @param topic          消息主题（用于 watch 订阅匹配），可为 null 表示无主题
 * @param timestamp      消息创建时间
 * @param correlationId  跨会话关联 ID
 * @param senderAgentId  发送方 Agent 标识
 */
public record MessageEnvelope(
        String messageId,
        String content,
        String causeBy,
        String topic,
        Instant timestamp,
        String correlationId,
        String senderAgentId
) {

    /** 创建一个新的消息信封 */
    public static MessageEnvelope create(String content, String causeBy, String topic,
            String correlationId, String senderAgentId) {
        return new MessageEnvelope(
                UUID.randomUUID().toString().substring(0, 8),
                content,
                causeBy,
                topic,
                Instant.now(),
                correlationId,
                senderAgentId
        );
    }

    /** 创建不带 topic 的消息（广播给所有订阅者） */
    public static MessageEnvelope broadcast(String content, String causeBy,
            String correlationId, String senderAgentId) {
        return create(content, causeBy, null, correlationId, senderAgentId);
    }
}

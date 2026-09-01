package cn.zcj.aether.messaging;

import cn.zcj.aether.types.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P1(1.3): Kafka 主题声明（aether.kafka.enabled=true 时装配）。
 *
 * <p>业务主题 1 分区保序（会话/用户内有序，吞吐足够）；DLT 同分区数。
 * Kafka 端 auto-create 关闭时由 KafkaAdmin 据此创建。</p>
 */
@Configuration
@ConditionalOnProperty(name = "aether.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic auditEventsTopic() {
        return new NewTopic(KafkaTopics.AUDIT_EVENTS, 1, (short) 1);
    }

    @Bean
    public NewTopic auditEventsDlt() {
        return new NewTopic(KafkaTopics.AUDIT_EVENTS_DLT, 1, (short) 1);
    }

    @Bean
    public NewTopic agentEventsTopic() {
        return new NewTopic(KafkaTopics.AGENT_EVENTS, 1, (short) 1);
    }

    @Bean
    public NewTopic agentEventsDlt() {
        return new NewTopic(KafkaTopics.AGENT_EVENTS_DLT, 1, (short) 1);
    }
}

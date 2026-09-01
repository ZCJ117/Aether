package cn.zcj.aether.trigger.listener;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 毒消息 DLT 投递共用辅助 —— 批量消费者共享同一转发契约：
 * key/value 原样转字符串发送到 {@code <topic>.DLT}，人工/离线排查后处理。
 */
@Slf4j
final class DltSupport {

    private DltSupport() {
    }

    static void sendToDlt(KafkaTemplate<Object, Object> kafkaTemplate, String dltTopic,
                          ConsumerRecord<Object, Object> record, String label, String reason) {
        log.warn("{} 毒消息路由 DLT: reason={}, offset={}", label, reason, record.offset());
        kafkaTemplate.send(dltTopic,
                record.key() == null ? null : record.key().toString(),
                record.value() == null ? null : record.value().toString());
    }
}

package cn.zcj.aether.trigger.listener;

import cn.zcj.aether.infrastructure.persistence.AuditLogRepository;
import cn.zcj.aether.types.messaging.AuditEventMessage;
import cn.zcj.aether.types.messaging.KafkaMessageCodec;
import cn.zcj.aether.types.messaging.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * P1(1.3): 审计事件消费端 —— 批量解析 → 批量落 {@code t_audit_log}（削峰 + 批量写）。
 *
 * <p>幂等：单条 {@code ON CONFLICT (event_id) DO NOTHING}，Kafka 至少一次投递下重放不重复；
 * 毒消息（无法解析）原样路由 {@code aether.audit.events.DLT}，不阻断整批；
 * 业务异常（DB 故障）抛出交给 DefaultErrorHandler 重试 → DLT；手动 ack 在处理后提交。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.kafka.enabled", havingValue = "true")
public class AuditEventConsumer {

    private final AuditLogRepository auditLogRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public AuditEventConsumer(AuditLogRepository auditLogRepository,
                              KafkaTemplate<Object, Object> kafkaTemplate) {
        this.auditLogRepository = auditLogRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = KafkaTopics.AUDIT_EVENTS,
            groupId = "aether-audit-consumer",
            containerFactory = "batchListenerContainerFactory")
    public void onBatch(List<ConsumerRecord<Object, Object>> records, Acknowledgment ack) {
        List<AuditLogRepository.AuditLogEntity> batch = new ArrayList<>(records.size());
        for (ConsumerRecord<Object, Object> record : records) {
            String json = record.value() instanceof String s ? s : null;
            AuditEventMessage msg = KafkaMessageCodec.fromJson(json, AuditEventMessage.class);
            if (msg == null) {
                DltSupport.sendToDlt(kafkaTemplate, KafkaTopics.AUDIT_EVENTS_DLT,
                        record, "audit", "消息解析失败");
                continue;
            }
            AuditLogRepository.AuditLogEntity entity = new AuditLogRepository.AuditLogEntity();
            entity.setEventId(msg.eventId());
            entity.setUserId(msg.userId());
            entity.setUsername(msg.username());
            entity.setAction(msg.action());
            entity.setResource(msg.resource());
            entity.setDetail(msg.detail());
            entity.setIpAddress(msg.ip());
            entity.setSuccess(msg.success());
            entity.setErrorMessage(msg.errorMessage());
            entity.setCreatedAt(msg.occurredAt() != null ? msg.occurredAt() : Instant.now());
            batch.add(entity);
        }

        int written = auditLogRepository.saveBatch(batch);
        log.debug("审计批量落库: batch={}, written={}（差值=重复投递去重）", records.size(), written);
        ack.acknowledge();
    }
}

package cn.zcj.aether.messaging;

import cn.zcj.aether.types.messaging.AuditEventMessage;
import cn.zcj.aether.types.messaging.KafkaTopics;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1(1.3): 审计生产者测试 —— 同步确认成功 / 超时降级（调用方据此直写，审计不丢）。
 */
class AuditEventProducerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);

    private AuditEventMessage message() {
        return new AuditEventMessage("evt-1", "LOGIN", "auth", "{}",
                1L, "u", "ip", true, null, "c", Instant.now());
    }

    @Test
    void sendSuccessReturnsTrue() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(KafkaTopics.AUDIT_EVENTS, 0), 0, 0, 0, 0, 0);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new org.springframework.kafka.support.SendResult<>(null, metadata)));

        AuditEventProducer producer = new AuditEventProducer(template, null, 200);
        assertTrue(producer.send(message()));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq(KafkaTopics.AUDIT_EVENTS), key.capture(), value.capture());
        assertTrue(key.getValue().contains("1"), "key=userId（单用户有序）");
        assertTrue(value.getValue().contains("\"eventId\":\"evt-1\""));
    }

    @Test
    void sendTimeoutFallsBackReturnsFalse() {
        // 永不完成的 future → 触发有界等待超时 → 返回 false（调用方降级直写）
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        AuditEventProducer producer = new AuditEventProducer(template, null, 50);
        assertFalse(producer.send(message()));
    }

    @Test
    void sendExceptionFallsBackReturnsFalse() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("broker down"));

        AuditEventProducer producer = new AuditEventProducer(template, null, 50);
        assertFalse(producer.send(message()));
    }
}

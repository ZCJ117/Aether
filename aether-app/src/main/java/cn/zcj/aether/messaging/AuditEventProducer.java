package cn.zcj.aether.messaging;

import cn.zcj.aether.types.messaging.AuditEventMessage;
import cn.zcj.aether.types.messaging.KafkaMessageCodec;
import cn.zcj.aether.types.messaging.KafkaTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * P1(1.3): 审计事件生产者 —— {@code AuditAspect} 的优先通道。
 *
 * <p><b>不丢</b>：{@code send()} 同步确认（acks=all + 有界等待），
 * 任何失败（超时/序列化/未启用）返回 false，调用方降级既有异步直写路径，
 * 审计永不静默丢失。<b>有序</b>：key=userId，单用户审计落同一分区。</p>
 *
 * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
 * 面试举证点：审计事件走<b>强一致</b>通道——{@code send()}（行49）同步确认（acks=all + 有界等待，
 * 行52-53），任何失败返回 false 触发调用方降级直写（行56-61，永不静默丢失）；key=userId（行51）
 * 单用户有序落同一分区；{@code produce} 指标（行64-74）按 success/fallback 计数可观测。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.kafka.enabled", havingValue = "true")
public class AuditEventProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> sendCounters = new ConcurrentHashMap<>();
    private final long sendTimeoutMs;

    public AuditEventProducer(KafkaTemplate<Object, Object> kafkaTemplate,
                              @Autowired(required = false) MeterRegistry meterRegistry,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${aether.kafka.produce-timeout-ms:2000}") long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * 发送审计事件到 {@code aether.audit.events}（key=userId，单用户有序）。
     *
     * @return true=已确认入队；false=失败（调用方必须降级直写，保证不丢）
     */
    // 【事件驱动】强一致审计通道：同步确认 + 失败降级直写，保证不丢
    public boolean send(AuditEventMessage message) {
        try {
            // 【事件驱动】key=userId 单用户有序；acks=all 同步确认（有界等待）
            String key = message.userId() != null ? message.userId().toString() : "anonymous";
            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS, key, KafkaMessageCodec.toJson(message))
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            increment("success");
            return true;
        } catch (Exception e) {
            increment("fallback");
            log.warn("审计事件入 Kafka 失败，调用方将降级直写: eventId={}, error={}",
                    message.eventId(), e.getMessage());
            return false;
        }
    }

    // 【事件驱动】produce 指标：success/fallback 计数，可观测审计投递健康度
    private void increment(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        sendCounters.computeIfAbsent(outcome, o ->
                        Counter.builder("aether.kafka.produce.total")
                                .tag("topic", KafkaTopics.AUDIT_EVENTS)
                                .tag("outcome", o)
                                .description("Audit events produced to kafka (success/fallback)")
                                .register(meterRegistry))
                .increment();
    }
}

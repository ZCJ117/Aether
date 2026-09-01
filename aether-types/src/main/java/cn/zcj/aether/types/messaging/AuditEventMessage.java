package cn.zcj.aether.types.messaging;

import java.time.Instant;

/**
 * P1(1.3): 审计事件消息 —— AuditAspect 生产、AuditEventConsumer 批量消费落 t_audit_log。
 *
 * <p>不丢：发送失败降级直写；不重：{@code eventId} 幂等键（消费端
 * {@code ON CONFLICT (event_id) DO NOTHING}）；有序：按 userId 分区。</p>
 */
public record AuditEventMessage(
        String eventId,
        String action,
        String resource,
        String detail,
        Long userId,
        String username,
        String ip,
        boolean success,
        String errorMessage,
        String correlationId,
        Instant occurredAt) {
}

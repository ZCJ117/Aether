package cn.zcj.aether.types.messaging;

import java.time.Instant;

/**
 * P1(1.3): Agent 事件消息 —— AgentEventKafkaBridge 订阅内存事件总线后生产、
 * DashboardStatsConsumer 消费聚合 {@code dashboard_stats}。
 *
 * <p>载荷为 turn.completed / tool.call.completed / agent.completed 三类事件的并集投影，
 * 未命中字段置零；{@code eventId} 继承源事件（消费端 aether_processed_event 幂等去重）。</p>
 */
public record AgentEventMessage(
        String eventId,
        String eventType,
        String agentId,
        String sessionId,
        String correlationId,
        int turnNumber,
        boolean hasToolCalls,
        int toolCallCount,
        long durationMs,
        int inputTokens,
        int outputTokens,
        double costUsd,
        String toolName,
        boolean toolSuccess,
        String status,
        Instant occurredAt) {
}

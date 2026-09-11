package cn.zcj.aether.trigger.listener;

import cn.zcj.aether.infrastructure.persistence.DashboardStatsRepository;
import cn.zcj.aether.infrastructure.persistence.ProcessedEventRepository;
import cn.zcj.aether.types.messaging.AgentEventMessage;
import cn.zcj.aether.types.messaging.KafkaMessageCodec;
import cn.zcj.aether.types.messaging.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P1(1.3): Agent 事件统计消费端 —— 聚合 {@code dashboard_stats}（替代实时 GROUP BY 查询）。
 *
 * <p>幂等：{@code aether_processed_event} 先占位（ON CONFLICT DO NOTHING 原子判定），
 * 首次出现才应用增量——至少一次投递下重放不重复计数。
 * 聚合规则：turn.completed → turns/toolCalls；tool.call.completed → toolCalls/toolErrors；
 * agent.completed → sessions；model.call.completed → tokens/cost。
 * key=sessionId 分区，消费侧并发 2×分区数按 key 分组，聚合行级竞争由 upsert 原子增量化解。</p>
 *
 * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
 * 面试举证点：消费端<b>幂等去重</b>——批量 {@code onBatch()}（行44）先以 {@code aether_processed_event}
 * 占位（行63，ON CONFLICT 原子判定）实现至少一次投递下幂等；解析失败（缺 eventId/agentId 或格式错）
 * 送 DLT（行53-60）隔离毒消息；聚合增量写入 dashboard_stats（行71-85）替代实时 GROUP BY。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.kafka.enabled", havingValue = "true")
public class DashboardStatsConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final DashboardStatsRepository dashboardStatsRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public DashboardStatsConsumer(ProcessedEventRepository processedEventRepository,
                                  DashboardStatsRepository dashboardStatsRepository,
                                  KafkaTemplate<Object, Object> kafkaTemplate) {
        this.processedEventRepository = processedEventRepository;
        this.dashboardStatsRepository = dashboardStatsRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = KafkaTopics.AGENT_EVENTS,
            groupId = "aether-dashboard-consumer",
            containerFactory = "batchListenerContainerFactory")
    // 【事件驱动】批量消费 + 手动 ack：处理完整批才提交 offset
    public void onBatch(List<ConsumerRecord<Object, Object>> records, Acknowledgment ack) {
        for (ConsumerRecord<Object, Object> record : records) {
            String json = record.value() instanceof String s ? s : null;
            AgentEventMessage msg = KafkaMessageCodec.fromJson(json, AgentEventMessage.class);
            if (msg == null) {
                // 【事件驱动】解析失败送 DLT，隔离毒消息
                DltSupport.sendToDlt(kafkaTemplate, KafkaTopics.AGENT_EVENTS_DLT,
                        record, "agent 事件", "消息解析失败");
                continue;
            }
            if (msg.eventId() == null || msg.agentId() == null) {
                // 【事件驱动】缺幂等键送 DLT，避免无法去重
                DltSupport.sendToDlt(kafkaTemplate, KafkaTopics.AGENT_EVENTS_DLT,
                        record, "agent 事件", "缺幂等键/agentId");
                continue;
            }
            // 【事件驱动】幂等占位：返回 false = 重复投递，跳过（至少一次下不重复计数）
            // 幂等占位：返回 false = 重复投递，跳过
            if (!processedEventRepository.markProcessed(msg.eventId(), msg.eventType())) {
                continue;
            }
            applyDelta(msg);
        }
        ack.acknowledge();
    }

    private void applyDelta(AgentEventMessage msg) {
        switch (msg.eventType() == null ? "" : msg.eventType()) {
            case "turn.completed" -> dashboardStatsRepository.applyDelta(
                    msg.agentId(), 0, 1, Math.max(0, msg.toolCallCount()), 0, 0, 0);
            case "tool.call.completed" -> dashboardStatsRepository.applyDelta(
                    msg.agentId(), 0, 0, 1, msg.toolSuccess() ? 0 : 1, 0, 0);
            case "agent.completed" -> dashboardStatsRepository.applyDelta(
                    msg.agentId(), 1, 0, 0, 0, 0, 0);
            case "model.call.completed" -> dashboardStatsRepository.applyDelta(
                    msg.agentId(), 0, 0, 0, 0,
                    Math.max(0, msg.inputTokens()) + Math.max(0, msg.outputTokens()),
                    msg.costUsd());
            default -> log.debug("未聚合的事件类型: {}", msg.eventType());
        }
    }
}

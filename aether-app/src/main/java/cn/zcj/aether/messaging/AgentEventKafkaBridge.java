package cn.zcj.aether.messaging;

import cn.zcj.aether.domain.agent.service.event.AgentEvent;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.support.DaemonThreads;
import cn.zcj.aether.types.messaging.AgentEventMessage;
import cn.zcj.aether.types.messaging.KafkaMessageCodec;
import cn.zcj.aether.types.messaging.KafkaTopics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1(1.3): Agent 事件 → Kafka 桥 —— 订阅既有内存事件总线
 * （{@link AgentEventPublisher#subscribe} 的第一个真实消费者），
 * 将 turn.completed / tool.call.completed / agent.completed 投递到
 * {@code aether.agent.events}（key=sessionId，会话内有序），供统计聚合消费。
 *
 * <p>事件发射在 Agent 调用线程上同步遍历订阅者，桥接端<b>绝不阻塞</b>：
 * 有界队列（1000）+ 后台单线程发送；队列满丢弃并计数（统计事件可容忍少量丢失）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.kafka.enabled", havingValue = "true")
public class AgentEventKafkaBridge {

    private final AgentEventPublisher publisher;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    private final BlockingQueue<AgentEventMessage> queue = new ArrayBlockingQueue<>(1000);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService sender;
    private AutoCloseable subscription;
    private long dropped;

    public AgentEventKafkaBridge(AgentEventPublisher publisher,
                                 KafkaTemplate<Object, Object> kafkaTemplate) {
        this.publisher = publisher;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    void start() {
        running.set(true);
        subscription = publisher.subscribe(this::onEvent);
        sender = DaemonThreads.singleThreadExecutor("agent-event-kafka-bridge");
        sender.submit(this::drainLoop);
        log.info("AgentEventKafkaBridge 已启动: topic={}", KafkaTopics.AGENT_EVENTS);
    }

    /** 总线回调（Agent 调用线程）：只做映射 + 入队，零阻塞。 */
    void onEvent(AgentEvent event) {
        AgentEventMessage message = map(event);
        if (message == null) {
            return;
        }
        if (!queue.offer(message)) {
            dropped++;
            if (dropped % 100 == 1) {
                log.warn("事件桥队列已满（统计消息丢弃计数）: dropped={}", dropped);
            }
        }
    }

    private void drainLoop() {
        while (running.get()) {
            try {
                AgentEventMessage message = queue.poll(500, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                kafkaTemplate.send(KafkaTopics.AGENT_EVENTS, message.sessionId(),
                        KafkaMessageCodec.toJson(message)).get(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 单条发送失败仅告警（统计链路容忍丢失，不重投避免会话内乱序）
                log.warn("Agent 事件发送失败: {}", e.getMessage());
            }
        }
    }

    /** 事件映射为聚合投影（turn/tool/agent/model 四类）；其余类型不产生消息。 */
    static AgentEventMessage map(AgentEvent event) {
        if (event instanceof AgentEvent.TurnCompleted e) {
            return new AgentEventMessage(e.eventId(), "turn.completed", e.agentId(), e.sessionId(),
                    e.correlationId(), e.turnNumber(), e.hasToolCalls(), e.toolCallCount(),
                    e.durationMs(), 0, 0, 0, null, false, null, Instant.now());
        }
        if (event instanceof AgentEvent.ToolCallCompleted e) {
            return new AgentEventMessage(e.eventId(), "tool.call.completed", e.agentId(), e.sessionId(),
                    e.correlationId(), 0, false, 0, e.durationMs(), 0, 0, 0,
                    e.toolName(), e.success(), null, Instant.now());
        }
        if (event instanceof AgentEvent.AgentCompleted e) {
            return new AgentEventMessage(e.eventId(), "agent.completed", e.agentId(), e.sessionId(),
                    e.correlationId(), e.totalTurns(), false, 0, e.durationMs(), 0, 0, 0,
                    null, false, e.status(), Instant.now());
        }
        if (event instanceof AgentEvent.ModelCallCompleted e) {
            return new AgentEventMessage(e.eventId(), "model.call.completed", e.agentId(), e.sessionId(),
                    e.correlationId(), 0, false, 0, e.durationMs(),
                    e.inputTokens(), e.outputTokens(), e.costUsd(), null, false, null, Instant.now());
        }
        return null;
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
        if (sender != null) {
            sender.shutdown();
            try {
                if (!sender.awaitTermination(5, TimeUnit.SECONDS)) {
                    sender.shutdownNow();
                }
            } catch (InterruptedException e) {
                sender.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("AgentEventKafkaBridge 已停止: dropped={}", dropped);
    }
}

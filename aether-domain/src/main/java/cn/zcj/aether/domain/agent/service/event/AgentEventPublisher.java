package cn.zcj.aether.domain.agent.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 领域事件发布器 — P0-6。
 * 统一发布结构化日志事件，供 LoggingHook 和外部监听器使用。
 *
 * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
 * 面试举证点：本类是进程内<b>内存事件总线</b>——{@code CopyOnWriteArrayList} 订阅（行29-53）是
 * AgentEventKafkaBridge 的首次真实消费者；15 个 {@code publish*} 方法（行66-218）统一发射，
 * 订阅者异常逐个隔离（行46-52）不阻断发布；{@code toJsonWithMdc}（行243-252）把
 * graphExecutionId/sessionId/subagentId 并入事件，保证跨进程链路可 join 对齐。</p>
 */
@Slf4j
@Component
public class AgentEventPublisher {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * O18（对应 D18）：内存事件总线——事件的真实订阅面（此前"事件总线"仅是 log.* 字符串）。
     * 线程安全：CopyOnWriteArrayList 订阅隔离；订阅者异常逐个隔离不阻断发布与日志 sink。
     * 订阅为可选能力：无订阅者时行为与纯日志一致（回滚安全）。
     */
    // 【事件驱动】内存事件总线：CopyOnWriteArrayList 订阅隔离，AgentEventKafkaBridge 由此接入
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<AgentEvent>> subscribers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 订阅事件总线；返回 AutoCloseable 用于取消订阅。 */
    public AutoCloseable subscribe(java.util.function.Consumer<AgentEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /** 统一发射：log 为默认 sink（不丢），订阅者为可选增强。 */
    private void emit(String logKey, AgentEvent event, boolean isError) {
        String json = toJson(event);
        if (isError) {
            log.error("{}: {}", logKey, json);
        } else {
            log.info("{}: {}", logKey, json);
        }
        // 【事件驱动】遍历订阅者逐个发射；异常隔离不阻断发布与日志 sink
        for (java.util.function.Consumer<AgentEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("事件订阅者执行异常（已隔离）: {}", e.getMessage());
            }
        }
    }

    /**
     * 发布 Agent 启动事件
     */
    public void publishAgentStarted(String agentId, String sessionId, String correlationId,
                                     String agentType, String modelRef) {
        AgentEvent.AgentStarted event = new AgentEvent.AgentStarted(
                agentId, sessionId, correlationId, agentType, modelRef);
        emit("agent_started", event, false);
    }

    /**
     * 发布 Agent 完成事件
     */
    public void publishAgentCompleted(String agentId, String sessionId, String correlationId,
                                       int totalTurns, long durationMs, String status) {
        AgentEvent.AgentCompleted event = new AgentEvent.AgentCompleted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, totalTurns, durationMs, status);
        emit("agent_completed", event, false);
    }

    /**
     * 发布 Turn 开始事件
     */
    public void publishTurnStarted(String agentId, String sessionId, String correlationId, int turnNumber) {
        AgentEvent.TurnStarted event = new AgentEvent.TurnStarted(
                agentId, sessionId, correlationId, turnNumber);
        emit("turn_started", event, false);
    }

    /**
     * 发布 Turn 完成事件
     */
    public void publishTurnCompleted(String agentId, String sessionId, String correlationId,
                                      int turnNumber, boolean hasToolCalls, int toolCallCount, long durationMs) {
        AgentEvent.TurnCompleted event = new AgentEvent.TurnCompleted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, turnNumber, hasToolCalls, toolCallCount, durationMs);
        emit("turn_completed", event, false);
    }

    /**
     * 发布模型调用开始事件
     */
    public void publishModelCallStarted(String agentId, String sessionId, String correlationId,
                                         String modelName, int messageCount) {
        AgentEvent.ModelCallStarted event = new AgentEvent.ModelCallStarted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, modelName, messageCount);
        emit("model_call_started", event, false);
    }

    /**
     * 发布模型调用完成事件
     */
    public void publishModelCallCompleted(String agentId, String sessionId, String correlationId,
                                           String modelName, long durationMs,
                                           int inputTokens, int outputTokens, double costUsd) {
        AgentEvent.ModelCallCompleted event = new AgentEvent.ModelCallCompleted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, modelName, durationMs,
                inputTokens, outputTokens, costUsd);
        emit("model_call_completed", event, false);
    }

    /**
     * 发布工具调用开始事件
     */
    public void publishToolCallStarted(String agentId, String sessionId, String correlationId,
                                        String toolName, String toolCallId) {
        AgentEvent.ToolCallStarted event = new AgentEvent.ToolCallStarted(
                agentId, sessionId, correlationId, toolName, toolCallId);
        emit("tool_call_started", event, false);
    }

    /**
     * 发布工具调用完成事件
     */
    public void publishToolCallCompleted(String agentId, String sessionId, String correlationId,
                                          String toolName, String toolCallId,
                                          boolean success, long durationMs) {
        AgentEvent.ToolCallCompleted event = new AgentEvent.ToolCallCompleted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, toolName, toolCallId, success, durationMs);
        emit("tool_call_completed", event, false);
    }

    /**
     * 发布压缩事件
     */
    public void publishCompactTriggered(String agentId, String sessionId, String correlationId,
                                         int beforeTokens, int afterTokens, int messagesCompacted) {
        AgentEvent.CompactTriggered event = new AgentEvent.CompactTriggered(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, beforeTokens, afterTokens, messagesCompacted);
        emit("compact_triggered", event, false);
    }

    /**
     * 发布错误事件
     */
    public void publishError(String agentId, String sessionId, String correlationId,
                              String errorType, String errorMessage, int turnNumber) {
        AgentEvent.ErrorOccurred event = new AgentEvent.ErrorOccurred(
                agentId, sessionId, correlationId, errorType, errorMessage, turnNumber);
        emit("error_occurred", event, true);
    }

    /**
     * P0-#8: 发布检查点事件（WAL 日志）
     */
    public void publishCheckpoint(String agentId, String sessionId, String correlationId,
                                   int turnNumber, int messageCount) {
        AgentEvent.CheckpointCreated event = new AgentEvent.CheckpointCreated(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, turnNumber, messageCount);
        emit("checkpoint_created", event, false);
    }

    /**
     * H4: 发布权限挂起事件 —— 工具调用等待用户确认。
     */
    public void publishPermissionAsking(String agentId, String sessionId, String correlationId,
                                        String replyId, int pendingCount, String toolNames) {
        AgentEvent.PermissionAsking event = new AgentEvent.PermissionAsking(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, replyId, pendingCount, toolNames);
        emit("permission_asking", event, false);
    }

    /**
     * H4: 发布权限解决事件 —— 用户确认或拒绝完成。
     */
    public void publishPermissionResolved(String agentId, String sessionId, String correlationId,
                                          String replyId, int approvedCount, int deniedCount) {
        AgentEvent.PermissionResolved event = new AgentEvent.PermissionResolved(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, replyId, approvedCount, deniedCount);
        emit("permission_resolved", event, false);
    }

    /**
     * M3: 发布子Agent委派事件 —— 审计委派操作。
     */
    public void publishDelegation(String agentId, String sessionId, String correlationId,
                                 String taskId, int toolCount, String status) {
        AgentEvent.DelegationDispatched event = new AgentEvent.DelegationDispatched(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, taskId, toolCount, status);
        emit("delegation_dispatched", event, false);
    }

    /**
     * D4: 发布后台自评审事件（BackgroundReviewer 专用）。
     */
    public void publishBackgroundReview(String graphExecutionId, String sessionId,
                                        String goal, String review) {
        AgentEvent.BackgroundReview event = new AgentEvent.BackgroundReview(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                graphExecutionId, sessionId, goal, review);
        emit("background_review", event, false);
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(toJsonWithMdc(obj));
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /** 把 MDC 上下文（graphExecutionId/sessionId/subagentId）并入 JSON 顶层，不改任何事件签名。 */
    static Object toJsonWithMdc(Object obj) {
        Map<String, String> mdc = mdcFields();
        if (mdc.isEmpty()) {
            return obj;
        }
        // 事件对象序列化为 Map 后合并 MDC 键
        try {
            Map<String, Object> base = new java.util.LinkedHashMap<>(MAPPER.convertValue(obj, Map.class));
            base.putAll(mdc);
            return base;
        } catch (Exception e) {
            return obj;
        }
    }

    /** 当前 MDC 中的结构化上下文字段（供日志 grep/join 与测试）。 */
    // 【事件驱动】MDC 上下文字段并入事件 JSON，跨进程链路可 grep/join
    public static Map<String, String> mdcFields() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"graphExecutionId", "sessionId", "subagentId"}) {
            String v = MDC.get(key);
            if (v != null && !v.isEmpty()) {
                result.put(key, v);
            }
        }
        return result;
    }
}

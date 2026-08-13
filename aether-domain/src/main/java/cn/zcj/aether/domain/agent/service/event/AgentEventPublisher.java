package cn.zcj.aether.domain.agent.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 领域事件发布器 — P0-6。
 * 统一发布结构化日志事件，供 LoggingHook 和外部监听器使用。
 */
@Slf4j
@Component
public class AgentEventPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 发布 Agent 启动事件
     */
    public void publishAgentStarted(String agentId, String sessionId, String correlationId,
                                     String agentType, String modelRef) {
        AgentEvent.AgentStarted event = new AgentEvent.AgentStarted(
                agentId, sessionId, correlationId, agentType, modelRef);
        log.info("agent_started: {}", toJson(event));
    }

    /**
     * 发布 Agent 完成事件
     */
    public void publishAgentCompleted(String agentId, String sessionId, String correlationId,
                                       int totalTurns, long durationMs, String status) {
        AgentEvent.AgentCompleted event = new AgentEvent.AgentCompleted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, totalTurns, durationMs, status);
        log.info("agent_completed: {}", toJson(event));
    }

    /**
     * 发布 Turn 开始事件
     */
    public void publishTurnStarted(String agentId, String sessionId, String correlationId, int turnNumber) {
        AgentEvent.TurnStarted event = new AgentEvent.TurnStarted(
                agentId, sessionId, correlationId, turnNumber);
        log.info("turn_started: {}", toJson(event));
    }

    /**
     * 发布 Turn 完成事件
     */
    public void publishTurnCompleted(String agentId, String sessionId, String correlationId,
                                      int turnNumber, boolean hasToolCalls, int toolCallCount, long durationMs) {
        AgentEvent.TurnCompleted event = new AgentEvent.TurnCompleted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, turnNumber, hasToolCalls, toolCallCount, durationMs);
        log.info("turn_completed: {}", toJson(event));
    }

    /**
     * 发布模型调用开始事件
     */
    public void publishModelCallStarted(String agentId, String sessionId, String correlationId,
                                         String modelName, int messageCount) {
        AgentEvent.ModelCallStarted event = new AgentEvent.ModelCallStarted(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, modelName, messageCount);
        log.info("model_call_started: {}", toJson(event));
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
        log.info("model_call_completed: {}", toJson(event));
    }

    /**
     * 发布工具调用开始事件
     */
    public void publishToolCallStarted(String agentId, String sessionId, String correlationId,
                                        String toolName, String toolCallId) {
        AgentEvent.ToolCallStarted event = new AgentEvent.ToolCallStarted(
                agentId, sessionId, correlationId, toolName, toolCallId);
        log.info("tool_call_started: {}", toJson(event));
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
        log.info("tool_call_completed: {}", toJson(event));
    }

    /**
     * 发布压缩事件
     */
    public void publishCompactTriggered(String agentId, String sessionId, String correlationId,
                                         int beforeTokens, int afterTokens, int messagesCompacted) {
        AgentEvent.CompactTriggered event = new AgentEvent.CompactTriggered(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, beforeTokens, afterTokens, messagesCompacted);
        log.info("compact_triggered: {}", toJson(event));
    }

    /**
     * 发布错误事件
     */
    public void publishError(String agentId, String sessionId, String correlationId,
                              String errorType, String errorMessage, int turnNumber) {
        AgentEvent.ErrorOccurred event = new AgentEvent.ErrorOccurred(
                agentId, sessionId, correlationId, errorType, errorMessage, turnNumber);
        log.error("error_occurred: {}", toJson(event));
    }

    /**
     * P0-#8: 发布检查点事件（WAL 日志）
     */
    public void publishCheckpoint(String agentId, String sessionId, String correlationId,
                                   int turnNumber, int messageCount) {
        AgentEvent.CheckpointCreated event = new AgentEvent.CheckpointCreated(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, turnNumber, messageCount);
        log.info("checkpoint_created: {}", toJson(event));
    }

    /**
     * H4: 发布权限挂起事件 —— 工具调用等待用户确认。
     */
    public void publishPermissionAsking(String agentId, String sessionId, String correlationId,
                                        String replyId, int pendingCount, String toolNames) {
        AgentEvent.PermissionAsking event = new AgentEvent.PermissionAsking(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, replyId, pendingCount, toolNames);
        log.info("permission_asking: {}", toJson(event));
    }

    /**
     * H4: 发布权限解决事件 —— 用户确认或拒绝完成。
     */
    public void publishPermissionResolved(String agentId, String sessionId, String correlationId,
                                          String replyId, int approvedCount, int deniedCount) {
        AgentEvent.PermissionResolved event = new AgentEvent.PermissionResolved(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, replyId, approvedCount, deniedCount);
        log.info("permission_resolved: {}", toJson(event));
    }

    /**
     * M3: 发布子Agent委派事件 —— 审计委派操作。
     */
    public void publishDelegation(String agentId, String sessionId, String correlationId,
                                 String taskId, int toolCount, String status) {
        AgentEvent.DelegationDispatched event = new AgentEvent.DelegationDispatched(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                agentId, sessionId, correlationId, taskId, toolCount, status);
        log.info("delegation_dispatched: {}", toJson(event));
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(toJsonWithMdc(obj));
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /** 把 MDC 上下文（graphExecutionId/sessionId/subagentId）并入 JSON 顶层，不改任何事件签名。 */
    private static Object toJsonWithMdc(Object obj) {
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

package cn.zcj.aether.domain.agent.service.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent 领域事件基类 — P0-6 新增。
 * 灵感来源：AgentScope v2 28 种事件类型 + CrewAI Event Bus。
 * 使用 Jackson 多态序列化，支持事件溯源和审计。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentEvent.AgentStarted.class, name = "agent.started"),
    @JsonSubTypes.Type(value = AgentEvent.AgentCompleted.class, name = "agent.completed"),
    @JsonSubTypes.Type(value = AgentEvent.TurnStarted.class, name = "turn.started"),
    @JsonSubTypes.Type(value = AgentEvent.TurnCompleted.class, name = "turn.completed"),
    @JsonSubTypes.Type(value = AgentEvent.ModelCallStarted.class, name = "model.call.started"),
    @JsonSubTypes.Type(value = AgentEvent.ModelCallCompleted.class, name = "model.call.completed"),
    @JsonSubTypes.Type(value = AgentEvent.ToolCallStarted.class, name = "tool.call.started"),
    @JsonSubTypes.Type(value = AgentEvent.ToolCallCompleted.class, name = "tool.call.completed"),
    @JsonSubTypes.Type(value = AgentEvent.CompactTriggered.class, name = "compact.triggered"),
    @JsonSubTypes.Type(value = AgentEvent.ErrorOccurred.class, name = "error.occurred"),
    @JsonSubTypes.Type(value = AgentEvent.CheckpointCreated.class, name = "checkpoint.created"),
    @JsonSubTypes.Type(value = AgentEvent.PermissionAsking.class, name = "permission.asking"),
    @JsonSubTypes.Type(value = AgentEvent.PermissionResolved.class, name = "permission.resolved"),
    @JsonSubTypes.Type(value = AgentEvent.DelegationDispatched.class, name = "delegation.dispatched"),
})
public interface AgentEvent {

    String eventId();
    Instant timestamp();
    String agentId();
    String sessionId();
    String correlationId();

    // ====== 10 种事件类型 ======

    record AgentStarted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        String agentType, String modelRef
    ) implements AgentEvent {
        public AgentStarted(String agentId, String sessionId, String correlationId,
                            String agentType, String modelRef) {
            this(UUID.randomUUID().toString(), Instant.now(),
                 agentId, sessionId, correlationId, agentType, modelRef);
        }
    }

    record AgentCompleted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        int totalTurns, long durationMs, String status
    ) implements AgentEvent {}

    record TurnStarted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId, int turnNumber
    ) implements AgentEvent {
        public TurnStarted(String agentId, String sessionId,
                           String correlationId, int turnNumber) {
            this(UUID.randomUUID().toString(), Instant.now(),
                 agentId, sessionId, correlationId, turnNumber);
        }
    }

    record TurnCompleted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId, int turnNumber,
        boolean hasToolCalls, int toolCallCount, long durationMs
    ) implements AgentEvent {}

    record ModelCallStarted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        String modelName, int messageCount
    ) implements AgentEvent {}

    record ModelCallCompleted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        String modelName, long durationMs,
        int inputTokens, int outputTokens, double costUsd
    ) implements AgentEvent {}

    record ToolCallStarted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        String toolName, String toolCallId
    ) implements AgentEvent {
        public ToolCallStarted(String agentId, String sessionId,
                               String correlationId, String toolName, String toolCallId) {
            this(UUID.randomUUID().toString(), Instant.now(),
                 agentId, sessionId, correlationId, toolName, toolCallId);
        }
    }

    record ToolCallCompleted(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        String toolName, String toolCallId,
        boolean success, long durationMs
    ) implements AgentEvent {}

    record CompactTriggered(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        int beforeTokens, int afterTokens, int messagesCompacted
    ) implements AgentEvent {}

    record ErrorOccurred(
        String eventId, Instant timestamp, String agentId,
        String sessionId, String correlationId,
        String errorType, String errorMessage, int turnNumber
    ) implements AgentEvent {
        public ErrorOccurred(String agentId, String sessionId, String correlationId,
                            String errorType, String errorMessage, int turnNumber) {
            this(UUID.randomUUID().toString(), Instant.now(),
                 agentId, sessionId, correlationId, errorType, errorMessage, turnNumber);
        }
    }

    /** P0-#8: 检查点创建事件（WAL 日志） */
    record CheckpointCreated(
        String eventId,
        java.time.Instant timestamp,
        String agentId,
        String sessionId,
        String correlationId,
        int turnNumber,
        int messageCount
    ) implements AgentEvent {}

    /** H4: 权限挂起事件 —— 工具调用等待用户确认 */
    record PermissionAsking(
        String eventId,
        java.time.Instant timestamp,
        String agentId,
        String sessionId,
        String correlationId,
        String replyId,
        int pendingCount,
        String toolNames
    ) implements AgentEvent {}

    /** H4: 权限解决事件 —— 用户确认或拒绝完成 */
    record PermissionResolved(
        String eventId,
        java.time.Instant timestamp,
        String agentId,
        String sessionId,
        String correlationId,
        String replyId,
        int approvedCount,
        int deniedCount
    ) implements AgentEvent {}

    /** M3: 子Agent委派事件 —— 审计委派操作 */
    record DelegationDispatched(
        String eventId,
        java.time.Instant timestamp,
        String agentId,
        String sessionId,
        String correlationId,
        String taskId,
        int toolCount,
        String status
    ) implements AgentEvent {}
}

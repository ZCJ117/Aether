package cn.zcj.aether.domain.agent.service.runtime;

import lombok.Builder;
import lombok.Getter;

/**
 * 运行时事件类型
 */
@Getter
@Builder
public class RuntimeEvent {

    private EventType type;
    private String text;                  // textDelta
    private String toolCallId;            // toolCall / toolResult
    private String toolName;              // toolCall / toolResult
    private String toolInput;             // toolCall
    private String toolOutput;            // toolResult
    private boolean toolError;            // toolResult
    private String compactSummary;        // compactBoundary
    private int turnCount;                // turnComplete
    private String errorMessage;          // error
    private String internalLlmSource;     // C2: "context-compaction" | "memory-encoding"
    private String internalLlmModel;      // C2: 使用的模型名
    private long internalLlmDurationMs;   // C2: 耗时（毫秒）
    private boolean internalLlmSuccess;   // C2: 是否成功
    private String checkpointSessionId;  // P0-#8
    private int checkpointTurnNumber;    // P0-#8
    private int budgetUsed;          // tokenBudget: 已用弹性预算
    private int budgetTotal;         // tokenBudget: 弹性预算总额
    private double budgetPercent;    // tokenBudget: 使用比例 (0-1)
    // H4: 权限挂起事件字段
    private String pendingToolCallsJson;  // permission_asking: 挂起的工具调用列表 JSON
    private String confirmReplyId;        // permission_asking: 确认回执 ID

    public enum EventType {
        turnStarted,        // 新一轮思考开始（在模型调用前发出，避免前端长时间空白）
        textDelta,
        toolCall,
        toolResult,
        compactBoundary,
        turnComplete,
        done,
        maxTurnsReached,
        error,
        internalLlmCall,   // C2: 非主循环 LLM 调用
        checkpoint,         // P0-#8: Agent 检查点事件
        tokenBudget,        // Token 预算监控事件
        permissionAsking,   // H4: 权限挂起等待用户确认
        agentPaused,        // H4: Agent 已暂停
        delegation,          // M3: 子Agent委派审计事件
        costExceeded         // M7: 成本熔断触发
    }

    public static RuntimeEvent turnStarted(int turnNumber) {
        return RuntimeEvent.builder().type(EventType.turnStarted).turnCount(turnNumber).build();
    }

    public static RuntimeEvent text(String delta) {
        return RuntimeEvent.builder().type(EventType.textDelta).text(delta).build();
    }

    public static RuntimeEvent done() {
        return RuntimeEvent.builder().type(EventType.done).build();
    }

    public static RuntimeEvent error(String msg) {
        return RuntimeEvent.builder().type(EventType.error).errorMessage(msg).build();
    }

    public static RuntimeEvent internalLlmCall(String source, String model,
                                                long durationMs, boolean success) {
        return RuntimeEvent.builder()
                .type(EventType.internalLlmCall)
                .internalLlmSource(source)
                .internalLlmModel(model)
                .internalLlmDurationMs(durationMs)
                .internalLlmSuccess(success)
                .build();
    }

    public static RuntimeEvent checkpoint(String sessionId, int turnNumber) {
        return RuntimeEvent.builder()
                .type(EventType.checkpoint)
                .checkpointSessionId(sessionId)
                .checkpointTurnNumber(turnNumber)
                .build();
    }

    public static RuntimeEvent tokenBudget(int used, int total) {
        return RuntimeEvent.builder()
                .type(EventType.tokenBudget)
                .budgetUsed(used)
                .budgetTotal(total)
                .budgetPercent(total > 0 ? (double) used / total : 0)
                .build();
    }

    /** H4: 权限挂起事件 —— 等待用户确认工具调用 */
    public static RuntimeEvent permissionAsking(String replyId, String pendingToolCallsJson) {
        return RuntimeEvent.builder()
                .type(EventType.permissionAsking)
                .confirmReplyId(replyId)
                .pendingToolCallsJson(pendingToolCallsJson)
                .build();
    }

    /** H4: Agent 已暂停事件 */
    public static RuntimeEvent agentPaused(String reason) {
        return RuntimeEvent.builder()
                .type(EventType.agentPaused)
                .errorMessage(reason)
                .build();
    }

    /** M3: 子Agent委派审计事件 */
    public static RuntimeEvent delegation(String taskId, int toolCount, String status) {
        return RuntimeEvent.builder()
                .type(EventType.delegation)
                .toolCallId(taskId)
                .turnCount(toolCount)
                .toolOutput(status)
                .build();
    }

    /** M7: 成本熔断触发事件 */
    public static RuntimeEvent costExceeded(double currentCost, double maxCost) {
        return RuntimeEvent.builder()
                .type(EventType.costExceeded)
                .budgetPercent(currentCost)
                .errorMessage(String.format("成本超限: $%.4f >= $%.4f", currentCost, maxCost))
                .build();
    }
}

package cn.zcj.aether.domain.agent.service.agent.core;

/**
 * Agent 执行结果。
 */
public record AgentResult(
    String agentId,
    String status,        // "success" | "error" | "max_turns_reached"
    String summary,       // 可选的执行摘要
    int totalTurns,
    long durationMs
) {
    public static AgentResult success(String agentId, String summary) {
        return new AgentResult(agentId, "success", summary, 0, 0);
    }

    public static AgentResult success(String agentId, String summary, int totalTurns, long durationMs) {
        return new AgentResult(agentId, "success", summary, totalTurns, durationMs);
    }

    public static AgentResult error(String agentId, String errorSummary) {
        return new AgentResult(agentId, "error", errorSummary, 0, 0);
    }

    public static AgentResult maxTurnsReached(String agentId, int totalTurns) {
        return new AgentResult(agentId, "max_turns_reached", "达到最大轮次限制", totalTurns, 0);
    }

    public boolean isSuccess() {
        return "success".equals(status);
    }
}

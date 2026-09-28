package cn.zcj.aether.domain.agent.service.subagent;

import java.time.Instant;

/**
 * 委派完成事件 — 对齐 hermes async_delegation.py _push_completion_event（L780）。
 */
public record DelegationCompletion(
        String delegationId,
        String parentSessionId,
        String parentAgentId,
        String task,
        SubagentState status,
        String summary,
        Instant completedAt
) {
    /**
     * 终态名；status 缺失时为 {@code UNKNOWN}。
     * 单一来源，避免各调用方各自判空（status 可空：回灌自 DB 的 safeState 会把未知状态映射为 null）。
     */
    public String statusName() {
        return status != null ? status.name() : "UNKNOWN";
    }
}

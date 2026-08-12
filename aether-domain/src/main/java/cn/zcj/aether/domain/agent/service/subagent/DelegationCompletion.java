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
) {}

package cn.zcj.aether.domain.agent.service.subagent;

import java.util.List;
import java.util.Objects;

/**
 * 异步委派输入 — 对齐 hermes SubagentLaunchRequest（subagent_lifecycle.py L49，简化）。
 */
public record DelegationTask(
        String task,
        List<String> toolNames,
        String modelRef,
        String userId,
        String parentSessionId,
        String parentAgentId
) {
    public DelegationTask {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parentSessionId, "parentSessionId");
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }
}

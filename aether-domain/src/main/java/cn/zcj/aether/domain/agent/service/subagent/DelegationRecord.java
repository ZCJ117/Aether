package cn.zcj.aether.domain.agent.service.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 异步委派持久化记录 — 对齐 hermes async_delegations 表（async_delegation.py L142）。
 * <p>toolNames 以逗号拼接 TEXT 存储（字段内不含逗号，简化序列化）。</p>
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DelegationRecord {
    private String id;
    private String parentSessionId;
    private String parentAgentId;
    private String taskPayload;
    private List<String> toolNames;
    private SubagentState state;
    private int attemptCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastHeartbeatAt;
    private String resultSummary;
    private boolean completionDelivered;
}

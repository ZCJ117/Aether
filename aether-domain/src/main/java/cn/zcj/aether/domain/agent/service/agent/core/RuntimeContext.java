package cn.zcj.aether.domain.agent.service.agent.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 per-call 运行时上下文。
 * 通过 RxJava Flowable 的 composing 机制或方法参数传递。
 * 灵感来源：AgentScope RuntimeContext（userId + sessionId + 追踪链）。
 */
public record RuntimeContext(
    String userId,
    String sessionId,
    String correlationId,     // 分布式追踪 ID
    String parentAgentId,     // 父 Agent ID（子 Agent 场景，null 表示顶级）
    String initialMessage,    // 本轮用户输入
    Map<String, Object> metadata, // 扩展元数据
    Instant createdAt
) {
    public RuntimeContext {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /** 为子 Agent 创建派生上下文 */
    public RuntimeContext forkForChild(String childAgentId, String childMessage) {
        return new RuntimeContext(
            userId,
            sessionId + "-" + childAgentId.substring(0, Math.min(childAgentId.length(), 8)),
            correlationId,    // 保持追踪链路
            this.getId(),     // 当前 Agent 作为父 Agent
            childMessage,
            Map.of(),
            Instant.now()
        );
    }

    /** 获取当前 Agent ID（即 parentAgentId 的反向引用，顶级时为 null） */
    public String getId() {
        return parentAgentId;
    }
}

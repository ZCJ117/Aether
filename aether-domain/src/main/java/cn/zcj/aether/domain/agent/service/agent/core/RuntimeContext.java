package cn.zcj.aether.domain.agent.service.agent.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 per-call 运行时上下文。
 * 通过 RxJava Flowable 的 composing 机制或方法参数传递。
 * 灵感来源：AgentScope RuntimeContext（userId + sessionId + 追踪链）。
 *
 * <p>O3（对应 D3）：{@code agentId} 表示"当前执行 Agent 的身份"，与
 * {@code parentAgentId}（父 Agent，顶级为 null）语义分离；{@link #getId()} 返回
 * {@code agentId}（缺省回退 {@code parentAgentId}，兼容旧构造点）。此前 getId() 返回
 * parentAgentId，导致子 Agent 场景下日志/审计/trace 的 agent 归属错位。</p>
 */
public record RuntimeContext(
    String userId,
    String sessionId,
    String correlationId,     // 分布式追踪 ID
    String parentAgentId,     // 父 Agent ID（子 Agent 场景，null 表示顶级）
    String initialMessage,    // 本轮用户输入
    Map<String, Object> metadata, // 扩展元数据
    Instant createdAt,
    String agentId            // O3: 当前 Agent 身份（缺省回退 parentAgentId）
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
        if (agentId == null || agentId.isBlank()) {
            agentId = parentAgentId; // O3: 兼容回退——未显式传入时沿用父 ID（旧行为）
        }
    }

    /** 旧 7 参构造（兼容既有调用点）：agentId 走回退语义。 */
    public RuntimeContext(String userId, String sessionId, String correlationId,
            String parentAgentId, String initialMessage, Map<String, Object> metadata,
            Instant createdAt) {
        this(userId, sessionId, correlationId, parentAgentId, initialMessage,
                metadata, createdAt, null);
    }

    /** 为子 Agent 创建派生上下文 */
    public RuntimeContext forkForChild(String childAgentId, String childMessage) {
        return new RuntimeContext(
            userId,
            sessionId + "-" + childAgentId.substring(0, Math.min(childAgentId.length(), 8)),
            correlationId,    // 保持追踪链路
            this.getId(),     // 当前 Agent 作为父 Agent（O3: agentId 语义）
            childMessage,
            Map.of(),
            Instant.now(),
            childAgentId      // O3: 子上下文的当前 Agent = 子 Agent
        );
    }

    /** 获取当前 Agent ID（O3: 读 agentId，"当前 Agent 身份"语义单一） */
    public String getId() {
        return agentId;
    }
}

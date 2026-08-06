package cn.zcj.aether.domain.agent.service.memory.core;

import java.util.Map;

/**
 * MemoryProvider.initialize 上下文 —— 对齐 hermes MemoryProvider.initialize(**kwargs)
 * 中的关键键（agent_context / agent_identity / user_id / parent_session_id）。
 */
public record MemoryInitContext(
        /** "primary" | "subagent" | "cron" | "flush" */
        String agentContext,
        /** 配置档名（如 "coder"） */
        String agentIdentity,
        /** 平台用户标识 */
        String userId,
        /** 子 Agent 场景的父会话 ID */
        String parentSessionId,
        /** 其他扩展上下文 */
        Map<String, Object> extras
) {
    public static MemoryInitContext empty() {
        return new MemoryInitContext(null, null, null, null, Map.of());
    }
}

package cn.zcj.aether.domain.agent.service.agent.hook;

import lombok.Builder;

/**
 * 生命周期钩子统一载荷 — 对齐 hermes invoke_hook 的 kwargs。
 * 各挂点按需填充字段，未用字段为 null。
 */
@Builder
public record HookContext(
        String agentId,
        String sessionId,
        String graphNodeId,
        Integer turnNumber,
        String request,
        String response,
        String error,
        Long durationMs) {
}

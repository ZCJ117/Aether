package cn.zcj.aether.domain.agent.service.agent.permission;

import java.util.List;

/**
 * 用户确认回执 —— 前端通过确认 API 提交的批准/拒绝结果。
 * 灵感来源：AgentScope ReActAgent 恢复协议中的 ConfirmResult 列表。
 *
 * @param toolCallId 工具调用 ID（对应 SuspendedToolCall.toolCallId）
 * @param approved   是否批准
 * @param grantRules 批准时携带的持久化授权规则（"本次会话始终允许"语义），可为空
 */
public record ConfirmResult(
        String toolCallId,
        boolean approved,
        List<PermissionRule> grantRules
) {
    /** 快速构造批准回执（无持久化规则） */
    public static ConfirmResult approve(String toolCallId) {
        return new ConfirmResult(toolCallId, true, List.of());
    }

    /** 快速构造批准回执（带持久化规则） */
    public static ConfirmResult approve(String toolCallId, List<PermissionRule> grantRules) {
        return new ConfirmResult(toolCallId, true,
                grantRules != null ? List.copyOf(grantRules) : List.of());
    }

    /** 快速构造拒绝回执 */
    public static ConfirmResult deny(String toolCallId) {
        return new ConfirmResult(toolCallId, false, List.of());
    }
}

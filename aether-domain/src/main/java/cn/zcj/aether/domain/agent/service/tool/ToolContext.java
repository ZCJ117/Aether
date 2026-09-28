package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;

/**
 * 工具执行上下文。
 *
 * @param userId         用户标识
 * @param sessionId      会话标识
 * @param toolCallId     工具调用标识
 * @param permissionMode 本次调用生效的权限模式（非空）
 * @param preAuthorized  该次工具调用是否已经过 {@code PermissionMiddleware} + {@code PermissionEngine}
 *                       的完整评估；{@code false} 表示未经预检，由 {@code ToolExecutor} 关卡④ 兜底评估
 */
public record ToolContext(String userId, String sessionId, String toolCallId,
                          PermissionMode permissionMode, boolean preAuthorized) {

    /** 兼容构造：默认 DEFAULT 模式 + 未预检。 */
    public ToolContext(String userId, String sessionId, String toolCallId) {
        this(userId, sessionId, toolCallId, PermissionMode.DEFAULT, false);
    }
}

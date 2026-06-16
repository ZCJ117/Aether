package cn.zcj.aether.domain.agent.service.agent.permission;

/**
 * 权限模式 —— 灵感来源：cc-haha PermissionMode。
 */
public enum PermissionMode {
    /** 默认：读写工具都经权限检查，写入工具可能要求确认 */
    DEFAULT,
    /** 计划模式：只允许只读工具，写入工具禁止 */
    PLAN,
    /** 信任模式：读写工具都允许，不询问 */
    ACCEPT_EDITS,
    /** 绕过模式：跳过所有权限检查（仅限信任的开发者会话） */
    BYPASS
}

package cn.zcj.aether.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * P1: 审计操作枚举。
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum AuditAction {
    LOGIN("LOGIN", "用户登录"),
    LOGOUT("LOGOUT", "用户登出"),
    TOKEN_REFRESH("TOKEN_REFRESH", "令牌刷新"),
    AGENT_CHAT("AGENT_CHAT", "Agent 对话"),
    AGENT_CREATE("AGENT_CREATE", "Agent 创建"),
    AGENT_DELETE("AGENT_DELETE", "Agent 删除"),
    TOOL_CALL("TOOL_CALL", "工具调用"),
    TOOL_DENY("TOOL_DENY", "工具调用被拒绝"),
    TOOL_CONFIRM("TOOL_CONFIRM", "工具调用经用户确认"),
    USER_CREATE("USER_CREATE", "用户创建"),
    USER_DELETE("USER_DELETE", "用户删除"),
    USER_UPDATE("USER_UPDATE", "用户信息更新"),
    CONFIG_CHANGE("CONFIG_CHANGE", "系统配置变更"),
    PERMISSION_CHANGE("PERMISSION_CHANGE", "权限配置变更"),
    // M3 新增：子Agent委派审计
    DELEGATION("DELEGATION", "子Agent委派操作");

    private String code;
    private String desc;
}

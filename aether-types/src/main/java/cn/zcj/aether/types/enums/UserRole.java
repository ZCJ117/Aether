package cn.zcj.aether.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * P0: 用户角色枚举。
 *
 * <p>角色层级（从低到高）：
 * <ul>
 *   <li><b>VIEWER</b> — 只读：查看 Agent 状态、会话历史、指标</li>
 *   <li><b>OPERATOR</b> — 操作：创建/运行 Agent 会话、管理工具</li>
 *   <li><b>ADMIN</b> — 管理：用户管理、系统配置、审计日志查询</li>
 * </ul>
 *
 * <p>Spring Security 中通过 {@code ROLE_} 前缀使用：
 * {@code hasRole('ADMIN')} 匹配 {@code ROLE_ADMIN}。
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum UserRole {
    VIEWER("VIEWER", "只读用户"),
    OPERATOR("OPERATOR", "操作员"),
    ADMIN("ADMIN", "管理员");

    private String code;
    private String desc;

    /**
     * 转换为 Spring Security 角色名（带 ROLE_ 前缀）。
     */
    public String toSecurityRole() {
        return "ROLE_" + this.code;
    }
}

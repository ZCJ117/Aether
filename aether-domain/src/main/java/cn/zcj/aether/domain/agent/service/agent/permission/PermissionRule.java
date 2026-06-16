package cn.zcj.aether.domain.agent.service.agent.permission;

/**
 * 权限规则接口。
 * 灵感来源：AgentScope PermissionEngine 的规则链。
 */
public interface PermissionRule {
    /** 规则名称 */
    String name();

    /** 优先级（越小越先评估） */
    int priority();

    /**
     * 评估权限。
     * @return null 表示此规则不适用（交由下一条规则处理）；
     *         ALLOW/DENY/ASK_USER 直接作为最终决策
     */
    PermissionDecision evaluate(PermissionContext ctx);
}

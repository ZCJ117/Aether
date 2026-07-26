package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 权限引擎。
 * 灵感来源：AgentScope PermissionEngine（规则链 + 模式切换）。
 *
 * 规则评估顺序：
 * 1. BYPASS 模式 → 直接 ALLOW
 * 2. 遍历 PermissionRule 列表，第一个返回非 null 的结果作为最终决策
 * 3. 无规则匹配 → 默认 DENY（安全优先）
 */
@Slf4j
@Component
public class PermissionEngine {

    private final List<PermissionRule> rules = new CopyOnWriteArrayList<>();

    public PermissionEngine() {
        // 内置默认规则
        registerRule(new SensitiveArgMaskRule());      // p=5: 参数脱敏最先
        registerRule(new ReadOnlyAllowRule());          // p=10
        registerRule(new ToolAllowlistRule());          // p=15: 白名单/黑名单
        registerRule(new PlanModeDenyWriteRule());      // p=20
    }

    public void registerRule(PermissionRule rule) {
        rules.add(rule);
        rules.sort(Comparator.comparingInt(PermissionRule::priority));
    }

    /**
     * 检查权限。
     * @param ctx  权限上下文
     * @param mode 当前权限模式
     * @return 权限决策
     */
    public PermissionDecision check(PermissionContext ctx, PermissionMode mode) {
        // BYPASS 模式跳过所有检查
        if (mode == PermissionMode.BYPASS) {
            log.debug("权限绕过: tool={}, userId={}", ctx.getToolName(), ctx.getUserId());
            return PermissionDecision.ALLOW;
        }

        // 遍历规则链
        for (PermissionRule rule : rules) {
            PermissionDecision decision = rule.evaluate(ctx);
            if (decision != null) {
                log.debug("权限规则 [{}] 决策: {} for tool={}, userId={}",
                    rule.name(), decision, ctx.getToolName(), ctx.getUserId());
                return decision;
            }
        }

        // 默认 DENY
        log.warn("无权限规则匹配，默认拒绝: tool={}, userId={}",
            ctx.getToolName(), ctx.getUserId());
        return PermissionDecision.DENY;
    }

    // =========================================================
    // 内置规则
    // =========================================================

    /** 只读工具始终允许 */
    static class ReadOnlyAllowRule implements PermissionRule {
        @Override public String name() { return "read-only-allow"; }
        @Override public int priority() { return 10; }

        @Override
        public PermissionDecision evaluate(PermissionContext ctx) {
            if (ctx.isReadOnly()) return PermissionDecision.ALLOW;
            return null; // 不是只读工具，交给下一条规则
        }
    }

    /** 计划模式下禁止写入工具 */
    static class PlanModeDenyWriteRule implements PermissionRule {
        @Override public String name() { return "plan-mode-deny-write"; }
        @Override public int priority() { return 20; }

        @Override
        public PermissionDecision evaluate(PermissionContext ctx) {
            if (ctx.getMode() == PermissionMode.PLAN && !ctx.isReadOnly()) {
                log.info("计划模式拒绝写入工具: tool={}", ctx.getToolName());
                return PermissionDecision.DENY;
            }
            return null;
        }
    }
}

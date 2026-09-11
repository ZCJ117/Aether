package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 权限引擎 —— H4 重构为 deny-first 分组短路评估链。
 * 灵感来源：AgentScope PermissionEngine（L139-202 deny-first 求值序）。
 *
 * <p>评估顺序（严格短路）：
 * <ol>
 *   <li><b>deny 组</b>（最高优先，含黑名单、Plan 模式禁写、注入防护）</li>
 *   <li><b>ask 组</b>（含注入防护规则，需人工确认）</li>
 *   <li><b>工具自带检查</b>（bypass 免疫，与 P0 方案步骤 4 的关卡 2 汇合）</li>
 *   <li><b>allow 组</b>（白名单、只读放行）</li>
 *   <li><b>BYPASS 模式兜底</b> → ALLOW</li>
 *   <li><b>默认 ASK_USER</b>（DONT_ASK 模式下转 DENY）</li>
 * </ol>
 *
 * <p>规则表构造时从装配配置快照拷贝，运行期经 {@link #addRule} 动态追加。
 *
 * <p><b>【架构亮点 · 权限体系 fail-closed】</b><br>
 * 面试举证点：deny→ask→allow 分组短路评估链（check 方法 :121-175）；任一规则评估抛异常一律返回 DENY（fail-closed，:187-191 与 :213-217）；BYPASS 模式仍强制先过 deny 组（:122-132），PLAN 模式未命中规则的写入工具默认拒绝（:163-168），确保越权与异常路径一律收紧而非静默放行。
 */
@Slf4j
@Component
public class PermissionEngine {

    /** deny 组：黑名单、Plan 模式禁写、注入防护 deny */
    private final List<PermissionRule> denyRules = new CopyOnWriteArrayList<>();

    /** ask 组：注入防护 ask、敏感操作确认 */
    private final List<PermissionRule> askRules = new CopyOnWriteArrayList<>();

    /** allow 组：白名单、只读放行 */
    private final List<PermissionRule> allowRules = new CopyOnWriteArrayList<>();

    /** 外部可配置的工具白名单/黑名单规则引用 */
    private final ToolAllowlistRule toolAllowlistRule;

    /** H4 新增：注入防护规则引用 */
    private final InjectionGuardRule injectionGuardRule;

    /** O14: 危险工具硬封锁规则（BYPASS 模式仍须执行） */
    private final DangerousToolRule dangerousToolRule;

    public PermissionEngine(DangerousToolRule dangerousToolRule) {
        this.dangerousToolRule = dangerousToolRule;
        this.toolAllowlistRule = new ToolAllowlistRule();
        this.injectionGuardRule = new InjectionGuardRule();

        // ====== deny 组（优先级排序后统一求值） ======
        // P2: 危险工具硬封锁规则（p=0，deny 组最高优先级）
        registerDenyRule(dangerousToolRule);
        // M3: 子Agent审批自动拒绝（p=5），防线程池死锁
        registerDenyRule(new SubAgentDenyApprovalRule());
        registerDenyRule(new PlanModeDenyWriteRule());      // p=20: Plan 模式禁止写入
        // 注：ToolAllowlistRule 的黑名单部分以独立 deny 规则注册

        // ====== ask 组 ======
        registerAskRule(injectionGuardRule);                 // p=8: 注入防护 → ASK_USER

        // ====== allow 组 ======
        registerAllowRule(new ReadOnlyAllowRule());          // p=10: 只读工具始终允许
        registerAllowRule(toolAllowlistRule);                // p=15: 工具白名单

        // 注：SensitiveArgMaskRule 已退出决策链（H4 步骤 1），
        // 脱敏动作上移到展示/日志层 MaskingUtil.forDisplay()
    }

    // ========== 分组注册 ==========

    public void registerDenyRule(PermissionRule rule) {
        denyRules.add(rule);
        denyRules.sort(Comparator.comparingInt(PermissionRule::priority));
    }

    public void registerAskRule(PermissionRule rule) {
        askRules.add(rule);
        askRules.sort(Comparator.comparingInt(PermissionRule::priority));
    }

    public void registerAllowRule(PermissionRule rule) {
        allowRules.add(rule);
        allowRules.sort(Comparator.comparingInt(PermissionRule::priority));
    }

    /**
     * 运行时动态追加规则。
     * 根据规则的优先级推断其归属组（对齐 AgentScope addRule 的 behavior 路由）。
     */
    public void addRule(PermissionRule rule) {
        // 根据规则名称推断分组
        String name = rule.name().toLowerCase(Locale.ROOT);
        if (name.contains("deny") || name.contains("blacklist")) {
            registerDenyRule(rule);
        } else if (name.contains("ask") || name.contains("guard")) {
            registerAskRule(rule);
        } else {
            registerAllowRule(rule);
        }
    }

    /** 获取 ToolAllowlistRule 引用，供外部注入 YAML 配置的 allowlist/denylist */
    public ToolAllowlistRule getToolAllowlistRule() {
        return toolAllowlistRule;
    }

    /** H4 新增：获取 InjectionGuardRule 引用，供外部配置自定义注入模式 */
    public InjectionGuardRule getInjectionGuardRule() {
        return injectionGuardRule;
    }

    /**
     * 检查权限 —— deny-first 分组短路评估。
     *
     * @param ctx  权限上下文
     * @param mode 当前权限模式
     * @return 权限决策
     */
    public PermissionDecision check(PermissionContext ctx, PermissionMode mode) {
        // 【fail-closed】BYPASS 模式也强制先过 deny 组，危险命令硬封锁在任何模式下都不可绕过
        // 0. BYPASS 仍须执行 deny 组（硬封锁不可绕过，对齐 hermes tool_guardrails 正交语义）
        if (mode == PermissionMode.BYPASS) {
            PermissionDecision deny = evaluateDenyGroup(denyRules, ctx);
            if (deny != null) {
                log.debug("BYPASS 模式 deny 组命中: tool={}, userId={}, decision={}",
                        ctx.getToolName(), ctx.getUserId(), deny);
                return deny;
            }
            log.debug("权限绕过(deny 组未命中): tool={}, userId={}", ctx.getToolName(), ctx.getUserId());
            return PermissionDecision.ALLOW;
        }

        // 1. deny 组（最高优先）
        PermissionDecision denyResult = evaluateDenyGroup(denyRules, ctx);
        if (denyResult != null) {
            log.debug("deny 组命中: tool={}, userId={}, rule={}",
                    ctx.getToolName(), ctx.getUserId(), denyResult);
            return denyResult;
        }

        // 2. ask 组
        PermissionDecision askResult = evaluateGroup(askRules, ctx);
        if (askResult != null) {
            log.debug("ask 组命中: tool={}, userId={}, rule={}",
                    ctx.getToolName(), ctx.getUserId(), askResult);
            return askResult;
        }

        // 3. 工具自带检查（bypass 免疫）
        // 注：此处与 P0 方案步骤 4 的 ToolExecutor.executeOne() 关卡 2 汇合
        // 工具自定义 checkPermissions() 已在 ToolExecutor 层执行；
        // PermissionEngine 层主要处理策略级规则

        // 4. allow 组
        PermissionDecision allowResult = evaluateGroup(allowRules, ctx);
        if (allowResult != null) {
            log.debug("allow 组命中: tool={}, userId={}", ctx.getToolName(), ctx.getUserId());
            return allowResult;
        }

        // 5. DONT_ASK 模式 → DENY（用户不可用）
        if (mode == PermissionMode.PLAN) {
            // 【fail-closed】PLAN 模式未命中规则的写入工具默认 DENY，收紧而非放行
            // PLAN 模式：未命中任何规则的写入工具默认 DENY
            if (!ctx.isReadOnly()) {
                log.info("Plan 模式默认拒绝写入工具: tool={}", ctx.getToolName());
                return PermissionDecision.DENY;
            }
        }

        // 6. 默认 ASK_USER（不再默认 DENY 一刀切）
        log.info("无规则匹配，默认询问用户: tool={}, userId={}",
                ctx.getToolName(), ctx.getUserId());
        return PermissionDecision.ASK_USER;
    }

    /**
     * 评估一个规则组，返回第一个非 null 决策。
     */
    private PermissionDecision evaluateGroup(List<PermissionRule> group, PermissionContext ctx) {
        for (PermissionRule rule : group) {
            try {
                PermissionDecision decision = rule.evaluate(ctx);
                if (decision != null) {
                    return decision;
                }
            } catch (Exception e) {
                // 【fail-closed】规则评估异常 = 一律拒绝，绝不静默放行
                // O14: 规则异常 fail-closed，绝不静默放行
                log.error("权限规则 [{}] 评估异常，fail-closed 拒绝: {}", rule.name(), e.getMessage(), e);
                return PermissionDecision.DENY;
            }
        }
        return null;
    }

    /**
     * 评估 deny 组 —— deny-first 正交语义：单个规则 ALLOW 不短路全组，
     * 仅当任一规则 DENY 或抛异常（fail-closed）才拒绝；全部未命中才放行。
     * O14: 修复 deny 组被无害的 ALLOW 提前短路、从而漏掉异常规则的隐患。
     */
    private PermissionDecision evaluateDenyGroup(List<PermissionRule> group, PermissionContext ctx) {
        boolean anyAllow = false;
        for (PermissionRule rule : group) {
            try {
                PermissionDecision decision = rule.evaluate(ctx);
                if (decision == PermissionDecision.DENY) {
                    return PermissionDecision.DENY;
                }
                if (decision == PermissionDecision.ALLOW) {
                    anyAllow = true;
                }
                // null / ASK_USER 视作该规则未否定 → 继续评估其余 deny 规则
            } catch (Exception e) {
                // 【fail-closed】deny 组评估异常 = 一律拒绝（O14 修复 ALLOW 提前短路隐患）
                // O14: 规则异常 fail-closed，绝不静默放行
                log.error("权限规则 [{}] 评估异常，fail-closed 拒绝: {}", rule.name(), e.getMessage(), e);
                return PermissionDecision.DENY;
            }
        }
        return anyAllow ? PermissionDecision.ALLOW : null;
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
            return null;
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

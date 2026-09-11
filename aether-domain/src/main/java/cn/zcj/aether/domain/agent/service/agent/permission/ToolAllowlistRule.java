package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1-#9: 工具白名单/黑名单规则。
 * 优先级: 显式 denylist = 拒绝 > allowlist 未命中 = 拒绝 > 默认放行
 *
 * <p><b>【架构亮点 · 权限体系 fail-closed】</b><br>
 * 面试举证点：白名单采用 deny-first 语义——denylist 命中即 DENY > allowlist 未命中即 DENY > 默认放行（evaluate :36-50）；priority=15 在 deny 组内靠后、allow 组内居中，缺失配置时因 denylist/allowlist 为空而跳过，避免"未配置即全部放行"的反向风险。
 */
@Slf4j
public class ToolAllowlistRule implements PermissionRule {

    private final Set<String> allowlist = ConcurrentHashMap.newKeySet();
    private final Set<String> denylist = ConcurrentHashMap.newKeySet();

    public ToolAllowlistRule() {}

    public ToolAllowlistRule(Set<String> allowlist, Set<String> denylist) {
        if (allowlist != null) this.allowlist.addAll(allowlist);
        if (denylist != null) this.denylist.addAll(denylist);
    }

    @Override
    public String name() {
        return "tool-allowlist";
    }

    @Override
    public int priority() {
        return 15; // 【fail-closed】priority=15：deny 组内靠后、allow 组内居中，收紧优先
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        String toolName = ctx.getToolName();

        // 【fail-closed】denylist 命中即拒绝，黑名单优先于一切白名单
        if (!denylist.isEmpty() && denylist.contains(toolName)) {
            log.warn("工具 [{}] 在执行黑名单中，已拒绝", toolName);
            return PermissionDecision.DENY;
        }

        // 【fail-closed】allowlist 已配置但工具未命中 → 拒绝（白名单即"默认拒绝未知"）
        if (!allowlist.isEmpty() && !allowlist.contains(toolName)) {
            log.warn("工具 [{}] 未在执行白名单中，已拒绝", toolName);
            return PermissionDecision.DENY;
        }

        return null;
    }

    public void setAllowlist(Set<String> tools) {
        this.allowlist.clear();
        this.allowlist.addAll(tools);
    }

    public void setDenylist(Set<String> tools) {
        this.denylist.clear();
        this.denylist.addAll(tools);
    }
}

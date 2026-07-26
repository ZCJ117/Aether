package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1-#9: 工具白名单/黑名单规则。
 * 优先级: 显式 denylist = 拒绝 > allowlist 未命中 = 拒绝 > 默认放行
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
        return 15;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        String toolName = ctx.getToolName();

        if (!denylist.isEmpty() && denylist.contains(toolName)) {
            log.warn("工具 [{}] 在执行黑名单中，已拒绝", toolName);
            return PermissionDecision.DENY;
        }

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

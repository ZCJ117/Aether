package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;

/**
 * 子Agent审批自动拒绝规则 — M3 子Agent上下文隔离增强。
 *
 * <p>在并发子Agent执行场景中，子Agent不得阻塞等待用户审批——
 * 审批类工具调用应被自动拒绝，避免多个子Agent同时等待审批造成死锁。
 *
 * <p>对齐 Hermes 优势4 的 auto-deny 审批陷阱修复：
 * 线程池 worker 中不可交互式等待审批；安全失败策略（auto-deny）确保
 * 子Agent不会因为审批需求而无限期阻塞。
 *
 * <p>注册在 PermissionEngine deny 组，priority=5（晚于 DangerousToolRule 的 p=0，
 * 早于 InjectionGuardRule 的 p=8）。
 *
 * <p>判定条件：PermissionContext.isSubAgentContext == true → 自动 DENY
 */
@Slf4j
public class SubAgentDenyApprovalRule implements PermissionRule {

    @Override
    public String name() {
        return "subagent-deny-approval";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        // 仅在子Agent上下文中生效
        if (!ctx.isSubAgentContext()) {
            return null; // 不参与决策
        }

        // 子Agent上下文中，需要审批的工具一律自动拒绝
        // 原因：子Agent运行在线程池worker中，交互式审批会导致死锁
        // 对齐 Hermes auto-deny 安全失败语义
        log.info("子Agent审批自动拒绝: tool={}, agentId={}, userId={}",
                ctx.getToolName(), ctx.getAgentId(), ctx.getUserId());
        return PermissionDecision.DENY;
    }
}

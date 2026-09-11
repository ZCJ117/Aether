package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * P2: 危险工具命令审批规则。
 *
 * <p>三层模型：
 * <ol>
 *   <li><b>硬封锁</b> — 永远拒绝（rm -rf /、mkfs、dd、fork bomb…）</li>
 *   <li><b>危险模式</b> — 需用户审批（rm -r、curl|sh、chmod 777…）</li>
 *   <li><b>会话白名单</b> — 用户批准"本会话始终允许"</li>
 * </ol>
 *
 * <p><b>【架构亮点 · 权限体系 fail-closed】</b><br>
 * 面试举证点：HARDBLOCK 正则集（:23-36）对任意危险命令（rm -rf /、dd、DROP TABLE 等）永远 DENY；priority=0（:58）置于 deny 组最高优先；即便上层 BYPASS 模式也拒绝（:79-82），构成不可绕过的"最后一道墙"。
 */
@Slf4j
@Component
public class DangerousToolRule implements PermissionRule {

    // 【fail-closed】危险命令硬封锁正则集：命中即 DENY，任何权限模式（含 BYPASS）均不可绕过
    private static final Set<Pattern> HARDBLOCK = Set.of(
        Pattern.compile("rm\\s+-rf\\s+/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("rm\\s+-rf\\s+--no-preserve-root", Pattern.CASE_INSENSITIVE),
        Pattern.compile("mkfs\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+if=", Pattern.CASE_INSENSITIVE),
        Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:", Pattern.CASE_INSENSITIVE),
        Pattern.compile(">\\s*/dev/sd", Pattern.CASE_INSENSITIVE),
        Pattern.compile("chmod\\s+777\\s+/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("DROP\\s+TABLE", Pattern.CASE_INSENSITIVE),
        Pattern.compile("DELETE\\s+FROM\\s+\\w+\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("TRUNCATE\\s+TABLE", Pattern.CASE_INSENSITIVE),
        Pattern.compile(">/etc/sudoers"),
        Pattern.compile("kill\\s+-9\\s+-1")
    );

    private static final Set<Pattern> DANGEROUS = Set.of(
        Pattern.compile("rm\\s+-r", Pattern.CASE_INSENSITIVE),
        Pattern.compile("curl.*\\|\\s*(ba)?sh", Pattern.CASE_INSENSITIVE),
        Pattern.compile("wget.*\\|\\s*(ba)?sh", Pattern.CASE_INSENSITIVE),
        Pattern.compile("chmod\\s+777", Pattern.CASE_INSENSITIVE),
        Pattern.compile("chmod\\s+-R", Pattern.CASE_INSENSITIVE),
        Pattern.compile("sed\\s+-i.*/etc/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("systemctl\\s+stop", Pattern.CASE_INSENSITIVE),
        Pattern.compile("git\\s+push\\s+--force", Pattern.CASE_INSENSITIVE),
        Pattern.compile("git\\s+push\\s+-f", Pattern.CASE_INSENSITIVE),
        Pattern.compile("docker\\s+rm\\s+-f", Pattern.CASE_INSENSITIVE),
        Pattern.compile("shutdown|reboot|halt|poweroff", Pattern.CASE_INSENSITIVE)
    );

    private final Set<String> sessionAllowlist = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String name() { return "dangerous-tool"; }

    @Override
    public int priority() { return 0; } // 【fail-closed】priority=0 最高优先，确保硬封锁先于一切规则求值

    /**
     * 仅判断是否命中硬封锁（deny-first 最高层）。
     * O14: 独立入口，供 ToolExecutor.executeOne 关卡 2 二次强制校验。
     */
    public boolean isHardblocked(PermissionContext ctx) {
        String toolCall = ctx.getToolName() + " " + ctx.getToolInput();
        for (Pattern p : HARDBLOCK) {
            if (p.matcher(toolCall).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        String toolCall = ctx.getToolName() + " " + ctx.getToolInput();

        // 【fail-closed】Layer 1 硬封锁：即使上层 BYPASS 模式也强制拒绝，是不可绕过的最后防线
        // Layer 1: 硬封锁（即使 BYPASS 也拒绝）—— 复用 isHardblocked
        if (isHardblocked(ctx)) {
            log.warn("硬封锁触发: tool={}, user={}", ctx.getToolName(), ctx.getUserId());
            return PermissionDecision.DENY;
        }

        // Layer 2: 会话白名单
        String key = ctx.getUserId() + ":" + ctx.getToolName();
        if (sessionAllowlist.contains(key)) {
            return PermissionDecision.ALLOW;
        }

        // Layer 3: 危险模式 → 请求用户确认
        for (Pattern p : DANGEROUS) {
            if (p.matcher(toolCall).find()) {
                log.info("危险模式触发: tool={}, user={}",
                        ctx.getToolName(), ctx.getUserId());
                return PermissionDecision.ASK_USER;
            }
        }

        return PermissionDecision.ALLOW;
    }

    public void allowForSession(Long userId, String toolName) {
        sessionAllowlist.add(userId + ":" + toolName);
    }
}

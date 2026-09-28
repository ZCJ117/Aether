package cn.zcj.aether.domain.agent.service.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D2 / T1-8、T1-9：{@link PermissionEngine} 在 {@link PermissionMode#ACCEPT_EDITS} 下的语义分支。
 *
 * <p>与 {@code DEFAULT} 的可观测差异：ask 组命中被降级跳过、兜底返回 {@code ALLOW}；
 * 但 deny 组仍照常评估且可返回 {@code DENY}（这正是它与 {@code BYPASS} 的差异）。
 */
class PermissionEngineAcceptEditsTest {

    /**
     * 让 deny 组"不表态"的 {@link DangerousToolRule} 替身，用于隔离 ask 组语义。
     *
     * <p>真实的 {@code DangerousToolRule.evaluate} 对非危险命令恒返回 {@code ALLOW}（绝不返回
     * {@code null}），而 {@code evaluateDenyGroup} 的语义是"任一规则 ALLOW 即整组 ALLOW"，
     * 于是 {@code check} 会在 deny 组直接短路返回——干净的输入根本走不到 ask 组。
     * 本替身返回 {@code null}（不表态），使 ask 组可达，从而能单独验证其降级语义。
     */
    private static PermissionEngine engineWithSilentDangerousRule() {
        return new PermissionEngine(new DangerousToolRule() {
            @Override
            public PermissionDecision evaluate(PermissionContext ctx) {
                return null;
            }
        });
    }

    /** 非只读、无白名单配置 → allow 组不命中，用于观察 ask 组与兜底行为。 */
    private PermissionContext writeCtx() {
        return PermissionContext.builder()
                .toolName("write_file")
                .toolInput(Map.of())
                .userId("u1")
                .build();
    }

    private static PermissionRule fixed(String name, int priority, PermissionDecision decision) {
        return new PermissionRule() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public PermissionDecision evaluate(PermissionContext ctx) { return decision; }
        };
    }

    @Test
    void t1_8AcceptEditsDowngradesAskGroupToAllow() {
        PermissionEngine engine = engineWithSilentDangerousRule();
        engine.registerAskRule(fixed("test-ask", 100, PermissionDecision.ASK_USER));

        assertEquals(PermissionDecision.ASK_USER, engine.check(writeCtx(), PermissionMode.DEFAULT),
                "DEFAULT 下 ask 组命中 → 询问用户");
        assertEquals(PermissionDecision.ALLOW, engine.check(writeCtx(), PermissionMode.ACCEPT_EDITS),
                "ACCEPT_EDITS 下 ask 组被降级跳过 → 走 allow 组与兜底，最终放行");
    }

    @Test
    void t1_9AcceptEditsStillHonoursDenyGroup() {
        PermissionEngine engine = engineWithSilentDangerousRule();
        engine.registerAskRule(fixed("test-ask", 100, PermissionDecision.ASK_USER));
        engine.registerDenyRule(fixed("test-deny", 50, PermissionDecision.DENY));

        assertEquals(PermissionDecision.DENY, engine.check(writeCtx(), PermissionMode.ACCEPT_EDITS),
                "ACCEPT_EDITS 仍须评估 deny 组（与 BYPASS 跳过 ask/allow 的差异）");
        assertEquals(PermissionDecision.DENY, engine.check(writeCtx(), PermissionMode.DEFAULT));
    }

    @Test
    void acceptEditsDowngradesAllowGroupAskUser() {
        PermissionEngine engine = engineWithSilentDangerousRule();
        engine.registerAllowRule(fixed("test-allow-ask", 100, PermissionDecision.ASK_USER));

        assertEquals(PermissionDecision.ASK_USER, engine.check(writeCtx(), PermissionMode.DEFAULT),
                "DEFAULT 下 allow 组的 ASK_USER 照常返回");
        assertEquals(PermissionDecision.ALLOW, engine.check(writeCtx(), PermissionMode.ACCEPT_EDITS),
                "ACCEPT_EDITS 下 allow 组的 ASK_USER 同样降级 —— §7.1.8 对照表承诺"
                        + "「ACCEPT_EDITS + ASK_USER → 放行」对任何规则来源都成立");
    }

    @Test
    void acceptEditsStillHonoursAllowGroupDeny() {
        PermissionEngine engine = engineWithSilentDangerousRule();
        engine.registerAllowRule(fixed("test-allow-deny", 100, PermissionDecision.DENY));

        assertEquals(PermissionDecision.DENY, engine.check(writeCtx(), PermissionMode.ACCEPT_EDITS),
                "降级只针对 ASK_USER；allow 组的 DENY（如白名单未命中）不得被吞掉");
    }

    @Test
    void acceptEditsAutoAllowsWhatDefaultWouldAskOnRealEngine() {
        // 真实引擎 + 真实危险模式（git push --force 命中 DANGEROUS 层，非硬封锁）：
        // DEFAULT 询问用户，ACCEPT_EDITS 自动放行 —— 两种模式在生产输入上的可观测差异
        PermissionEngine engine = new PermissionEngine(new DangerousToolRule());
        PermissionContext ctx = PermissionContext.builder()
                .toolName("Bash")
                .toolInput(Map.of("command", "git push --force"))
                .userId("u1")
                .build();

        assertEquals(PermissionDecision.ASK_USER, engine.check(ctx, PermissionMode.DEFAULT));
        assertEquals(PermissionDecision.ALLOW, engine.check(ctx, PermissionMode.ACCEPT_EDITS));
    }
}

package cn.zcj.aether.domain.agent.service.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineBypassTest {

    private PermissionEngine engine() {
        return new PermissionEngine(new DangerousToolRule());
    }

    private PermissionContext ctx(String tool, String cmd) {
        return PermissionContext.builder()
                .toolName(tool)
                .toolInput(Map.of("command", cmd))
                .userId("u1")
                .build();
    }

    @Test
    void bypassModeStillDeniesHardblockedCommands() {
        PermissionEngine engine = engine();
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "rm -rf /"), PermissionMode.BYPASS));
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "mkfs.ext4 /dev/sda1"), PermissionMode.BYPASS));
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "dd if=/dev/zero of=/dev/sda"), PermissionMode.BYPASS));
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "DROP TABLE users"), PermissionMode.BYPASS));
    }

    @Test
    void bypassModeAllowsNonHardblockedCommand() {
        PermissionEngine engine = engine();
        assertEquals(PermissionDecision.ALLOW,
                engine.check(ctx("Bash", "ls -la"), PermissionMode.BYPASS));
    }

    @Test
    void ruleExceptionFailsClosed() {
        PermissionEngine engine = engine();
        engine.registerDenyRule(new PermissionRule() {
            @Override public String name() { return "boom-deny"; }
            @Override public int priority() { return 0; }
            @Override public PermissionDecision evaluate(PermissionContext c) {
                throw new RuntimeException("rule boom");
            }
        });
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "ls -la"), PermissionMode.DEFAULT));
    }

    @Test
    void dangerousToolRuleIsHardblockedDirect() {
        DangerousToolRule rule = new DangerousToolRule();
        assertTrue(rule.isHardblocked(ctx("Bash", "rm -rf /")));
        assertFalse(rule.isHardblocked(ctx("Bash", "ls -la")));
    }
}

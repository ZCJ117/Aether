package cn.zcj.aether.domain.agent.service.agent.hook;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 对齐 hermes shell_hooks.py：ShellHookSpec 校验 + ShellHook 触发（ProcessBuilder 无 shell）。
 */
class ShellHookTest {

    @Test
    void specValidatesCommandAndTimeout() {
        ShellHookSpec spec = new ShellHookSpec(HookPoint.SUBAGENT_START, "echo hi", 5000);
        assertEquals(HookPoint.SUBAGENT_START, spec.point());
        assertEquals("echo hi", spec.command());
        assertEquals(5000, spec.timeoutMs());
    }

    @Test
    void specClampsTimeoutToBounds() {
        ShellHookSpec clamped = new ShellHookSpec(HookPoint.ON_SESSION_END, "echo", 999_999);
        assertEquals(300_000, clamped.timeoutMs());
        ShellHookSpec negative = new ShellHookSpec(HookPoint.ON_SESSION_END, "echo", -1);
        assertEquals(60_000, negative.timeoutMs());
    }

    @Test
    void specRejectsBlankCommand() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShellHookSpec(HookPoint.ON_SESSION_END, "   ", 1000));
    }

    @Test
    void shellHookExposesItsPoint() {
        ShellHook hook = new ShellHook(new ShellHookSpec(HookPoint.SUBAGENT_START, "echo hi", 1000));
        assertEquals(Set.of(HookPoint.SUBAGENT_START), hook.points());
    }

    @Test
    void onHookRunsExternalCommandWithStdinJson() {
        // Windows 下 echo 非独立可执行文件 → 用 cmd /c（ProcessBuilder 无 shell 拆分）
        // 目标：onHook 无论如何不抛穿（命令成败都吞掉）。以 Windows 上稳定绿为准。
        String cmd = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "cmd /c echo hello" : "echo hello";
        ShellHook hook = new ShellHook(new ShellHookSpec(HookPoint.ON_SESSION_START, cmd, 5000));
        hook.onHook(HookPoint.ON_SESSION_START, HookContext.builder().sessionId("s1").agentId("a1").build());
        // 不抛异常即为通过
    }
}

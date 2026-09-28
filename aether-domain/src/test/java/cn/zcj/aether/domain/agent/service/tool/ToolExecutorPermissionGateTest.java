package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D2 / T1-1 ~ T1-6：{@link ToolExecutor} 关卡④（工具级权限）两级判定。
 *
 * <p>覆盖：未预检通道的工具自检拒绝 / 引擎 DENY / 引擎 ASK_USER（fail-closed）/ 引擎 ALLOW；
 * 预检通道跳过第二级；{@code permissionEngine == null} 时与改造前行为等价。
 */
class ToolExecutorPermissionGateTest {

    private ToolRegistry registry;
    private ToolExecutor executor;
    private PermissionEngine engine;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        executor = new ToolExecutor();
        ReflectionTestUtils.setField(executor, "toolRegistry", registry);

        engine = mock(PermissionEngine.class);
        ReflectionTestUtils.setField(executor, "permissionEngine", engine);
    }

    /** 未预检的直调通道上下文（关卡④ 第二级生效）。 */
    private static ToolContext unPreAuthorized() {
        return new ToolContext("u1", "s1", "", PermissionMode.DEFAULT, false);
    }

    /** 已经过 PermissionMiddleware 预检的通道上下文。 */
    private static ToolContext preAuthorized() {
        return new ToolContext("u1", "s1", "", PermissionMode.DEFAULT, true);
    }

    private static List<ToolExecutor.ToolCallRequest> oneCall() {
        return List.of(new ToolExecutor.ToolCallRequest("call-1", "write_file", Map.of()));
    }

    @Test
    void t1_1UnPreAuthorizedAndToolSelfCheckDenied() {
        AtomicBoolean called = new AtomicBoolean(false);
        registry.register(recordingTool("write_file", false, called, false));

        List<ToolResult> results = executor.executeBatch(oneCall(), unPreAuthorized());

        assertEquals(1, results.size());
        ToolResult result = results.get(0);
        assertTrue(result.isError());
        assertEquals(ToolResult.ErrorType.PERMISSION, result.getErrorType());
        assertTrue(result.getContent().contains("self-check denied"),
                "文案应含 self-check denied，实际: " + result.getContent());
        assertEquals("call-1", result.getToolCallId(), "拒绝结果必须回填原 toolCallId");
        assertEquals("write_file", result.getToolName(), "拒绝结果必须回填原 toolName");
        assertFalse(called.get(), "工具自检拒绝后不得执行工具");
        // 第一级拒绝即返回，不再进入第二级评估
        verify(engine, never()).check(any(), any());
    }

    @Test
    void t1_2UnPreAuthorizedAndEngineDenies() {
        AtomicBoolean called = new AtomicBoolean(false);
        registry.register(recordingTool("write_file", false, called, true));
        when(engine.check(any(), any())).thenReturn(PermissionDecision.DENY);

        List<ToolResult> results = executor.executeBatch(oneCall(), unPreAuthorized());

        ToolResult result = results.get(0);
        assertTrue(result.isError());
        assertEquals(ToolResult.ErrorType.PERMISSION, result.getErrorType());
        assertTrue(result.getContent().contains("(channel: direct)"),
                "文案应含 (channel: direct)，实际: " + result.getContent());
        assertFalse(called.get(), "引擎 DENY 后工具不得执行");
    }

    @Test
    void t1_3UnPreAuthorizedAndEngineAsksUserFailsClosed() {
        AtomicBoolean called = new AtomicBoolean(false);
        registry.register(recordingTool("write_file", false, called, true));
        when(engine.check(any(), any())).thenReturn(PermissionDecision.ASK_USER);

        List<ToolResult> results = executor.executeBatch(oneCall(), unPreAuthorized());

        ToolResult result = results.get(0);
        assertTrue(result.isError());
        assertEquals(ToolResult.ErrorType.PERMISSION, result.getErrorType());
        assertTrue(result.getContent().contains("does not support suspension"),
                "文案应含 does not support suspension，实际: " + result.getContent());
        assertFalse(called.get(), "直调通道无挂起能力，ASK_USER 必须 fail-closed 拒绝而非静默放行");
    }

    @Test
    void t1_4UnPreAuthorizedAndEngineAllows() {
        AtomicBoolean called = new AtomicBoolean(false);
        registry.register(recordingTool("write_file", false, called, true));
        when(engine.check(any(), any())).thenReturn(PermissionDecision.ALLOW);

        List<ToolResult> results = executor.executeBatch(oneCall(), unPreAuthorized());

        assertFalse(results.get(0).isError());
        assertEquals("executed", results.get(0).getContent());
        assertTrue(called.get(), "引擎 ALLOW 后应正常执行工具");
    }

    @Test
    void t1_5PreAuthorizedSkipsEngineEvenWhenEngineWouldDeny() {
        AtomicBoolean called = new AtomicBoolean(false);
        registry.register(recordingTool("write_file", false, called, true));
        when(engine.check(any(), any())).thenReturn(PermissionDecision.DENY);

        List<ToolResult> results = executor.executeBatch(oneCall(), preAuthorized());

        assertTrue(called.get(), "预检通道不得被关卡④ 重复拦截");
        assertFalse(results.get(0).isError());
        verify(engine, never()).check(any(), any());
    }

    @Test
    void t1_6NullPermissionEngineBehavesAsBeforeRefactor() {
        ReflectionTestUtils.setField(executor, "permissionEngine", null);

        AtomicBoolean calledOk = new AtomicBoolean(false);
        registry.register(recordingTool("write_file", false, calledOk, true));
        List<ToolResult> allowed = executor.executeBatch(oneCall(), unPreAuthorized());
        assertFalse(allowed.get(0).isError(), "无引擎时不受模式影响，与改造前一致");
        assertTrue(calledOk.get());

        AtomicBoolean calledDenied = new AtomicBoolean(false);
        registry.register(recordingTool("self_denied", false, calledDenied, false));
        List<ToolResult> denied = executor.executeBatch(
                List.of(new ToolExecutor.ToolCallRequest("call-2", "self_denied", Map.of())),
                unPreAuthorized());
        assertEquals(ToolResult.ErrorType.PERMISSION, denied.get(0).getErrorType());
        assertTrue(denied.get(0).getContent().contains("self-check denied"));
        assertFalse(calledDenied.get());
    }

    /**
     * §7.1.8 对照表末列「未预检通道 + `ASK_USER`」的端到端对照：
     * `DEFAULT` → 拒绝（fail-closed）；`ACCEPT_EDITS` → 放行。
     *
     * <p>用**真实** {@link PermissionEngine} + 真实 {@link DangerousToolRule}，并以命令
     * {@code git push --force} 使 deny 组"不表态"，从而触达 allow 组返回 `ASK_USER` 的路径 ——
     * 内置 allow 规则不会返回 `ASK_USER`，故注册一条自定义规则补齐该单元格。
     */
    @Test
    void acceptEditsAllowsOnDirectChannelWhatDefaultRejectsFailClosed() {
        PermissionEngine realEngine = new PermissionEngine(new DangerousToolRule());
        realEngine.registerAllowRule(new PermissionRule() {
            @Override public String name() { return "test-allow-ask"; }
            @Override public int priority() { return 100; }
            @Override public PermissionDecision evaluate(PermissionContext ctx) {
                return PermissionDecision.ASK_USER;
            }
        });
        ReflectionTestUtils.setField(executor, "permissionEngine", realEngine);

        AtomicBoolean called = new AtomicBoolean(false);
        registry.register(recordingTool("Bash", false, called, true));
        List<ToolExecutor.ToolCallRequest> calls = List.of(
                new ToolExecutor.ToolCallRequest("call-1", "Bash", Map.of("command", "git push --force")));

        ToolResult denied = executor.executeBatch(calls,
                new ToolContext("u1", "s1", "", PermissionMode.DEFAULT, false)).get(0);
        assertEquals(ToolResult.ErrorType.PERMISSION, denied.getErrorType());
        assertTrue(denied.getContent().contains("does not support suspension"),
                "实际: " + denied.getContent());
        assertFalse(called.get(), "DEFAULT 下直调通道的 ASK_USER 必须 fail-closed 拒绝");

        ToolResult allowed = executor.executeBatch(calls,
                new ToolContext("u1", "s1", "", PermissionMode.ACCEPT_EDITS, false)).get(0);
        assertFalse(allowed.isError(), "ACCEPT_EDITS 下该单元格应放行，实际: " + allowed.getContent());
        assertTrue(called.get(), "ACCEPT_EDITS 降级后工具应被执行");
    }

    /** 记录是否被真实调用的工具桩；{@code selfCheck} 为 {@code checkPermissions} 的返回值。 */
    private static Tool recordingTool(String name, boolean readOnly, AtomicBoolean called, boolean selfCheck) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly() { return readOnly; }
            @Override public boolean checkPermissions(Map<String, Object> input) { return selfCheck; }

            @Override
            public ToolResult call(Map<String, Object> input, ToolContext ctx) {
                called.set(true);
                return ToolResult.success(ctx.toolCallId(), name, "executed");
            }
        };
    }
}

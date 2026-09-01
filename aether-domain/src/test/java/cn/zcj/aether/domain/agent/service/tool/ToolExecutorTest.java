package cn.zcj.aether.domain.agent.service.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutor 单元测试 — P0-5
 * 验证：并发安全分区、工具注册查找、错误处理
 */
class ToolExecutorTest {

    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        executor = new ToolExecutor();
        // 手动注入 toolRegistry（测试环境不走 Spring）
        injectField(executor, "toolRegistry", registry);
    }

    @Test
    void shouldExecuteSafeToolsConcurrently() {
        Tool safeTool = createTool("echo", true);
        registry.register(safeTool);

        List<ToolExecutor.ToolCallRequest> requests = List.of(
            new ToolExecutor.ToolCallRequest("c1", "echo", Map.of()),
            new ToolExecutor.ToolCallRequest("c2", "echo", Map.of())
        );

        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");

        assertEquals(2, results.size());
        assertFalse(results.get(0).isError());
        assertFalse(results.get(1).isError());
    }

    @Test
    void shouldReturnErrorForUnknownTool() {
        List<ToolExecutor.ToolCallRequest> requests = List.of(
            new ToolExecutor.ToolCallRequest("c1", "nonexistent", Map.of())
        );

        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).getContent().contains("Tool not found"));
    }

    @Test
    void shouldPartitionSafeAndUnsafeTools() {
        Tool safeTool = createTool("safe", true);
        Tool unsafeTool = createTool("unsafe", false);
        registry.register(safeTool);
        registry.register(unsafeTool);

        List<ToolExecutor.ToolCallRequest> requests = List.of(
            new ToolExecutor.ToolCallRequest("c1", "safe", Map.of()),
            new ToolExecutor.ToolCallRequest("c2", "unsafe", Map.of())
        );

        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");

        assertEquals(2, results.size());
    }

    @Test
    void shouldHandleToolException() {
        Tool brokenTool = new Tool() {
            public String name() { return "broken"; }
            public String description() { return "broken tool"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public ToolResult call(Map<String, Object> input, ToolContext ctx) {
                throw new RuntimeException("boom");
            }
            public boolean isConcurrencySafe() { return true; }
        };
        registry.register(brokenTool);

        List<ToolExecutor.ToolCallRequest> requests = List.of(
            new ToolExecutor.ToolCallRequest("c1", "broken", Map.of())
        );

        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).getContent().contains("boom"));
    }

    @Test
    void shouldReturnEmptyListForEmptyBatch() {
        List<ToolResult> results = executor.executeBatch(List.of(), "user1", "session1");
        assertTrue(results.isEmpty());
    }

    @Test
    void toolResultFactoryMethodsShouldWork() {
        ToolResult success = ToolResult.success("id1", "echo", "ok");
        assertFalse(success.isError());
        assertEquals("ok", success.getContent());

        ToolResult error = ToolResult.error("id2", "bad", "failed");
        assertTrue(error.isError());
        assertEquals("failed", error.getContent());
    }

    @Test
    void shouldHardblockDangerousCommandInGateTwo() {
        injectField(executor, "dangerousToolRule",
                new cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule());
        registry.register(createTool("Bash", false));
        List<ToolExecutor.ToolCallRequest> requests = List.of(
                new ToolExecutor.ToolCallRequest("c1", "Bash", Map.of("command", "rm -rf /")));
        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");
        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertEquals(ToolResult.ErrorType.PERMISSION, results.get(0).getErrorType());
    }

    @Test
    void shouldNotHardblockSafeCommand() {
        injectField(executor, "dangerousToolRule",
                new cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule());
        registry.register(createTool("Bash", false));
        List<ToolExecutor.ToolCallRequest> requests = List.of(
                new ToolExecutor.ToolCallRequest("c1", "Bash", Map.of("command", "ls -la")));
        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");
        assertFalse(results.get(0).isError());
    }

    // ----- helpers -----

    private Tool createTool(String name, boolean concurrencySafe) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name + " tool"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public ToolResult call(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.success(ctx.toolCallId(), name, "done");
            }
            public boolean isConcurrencySafe() { return concurrencySafe; }
        };
    }

    @SuppressWarnings("unchecked")
    private void injectField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            // ToolExecutor 的 toolRegistry 字段可能有 @Resource
            // 也可能需要遍历父类字段
            field.set(target, value);
        } catch (Exception e) {
            // 尝试父类字段
            try {
                java.lang.reflect.Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e2) {
                throw new RuntimeException("Cannot inject field: " + fieldName, e2);
            }
        }
    }
}

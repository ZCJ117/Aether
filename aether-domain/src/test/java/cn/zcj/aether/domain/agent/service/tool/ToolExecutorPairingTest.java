package cn.zcj.aether.domain.agent.service.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O10 — 工具执行超时配对修复单元测试。
 *
 * 验证：并发执行超时 / 异常分支返回的 ToolResult 携带原 toolCallId / toolName
 * （修复前为空串，回注后与 assistant 的 tool_call 无法配对）。
 */
class ToolExecutorPairingTest {

    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        executor = new ToolExecutor();
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "toolRegistry", registry);
    }

    /** 并发安全但执行缓慢的工具（sleep 3s）。 */
    private static Tool slowSafeTool(String name) {
        return new Tool() {
            @Override
            public String name() { return name; }

            @Override
            public String description() { return "slow safe tool"; }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResult call(Map<String, Object> input, ToolContext context) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(context.toolCallId(), name, "done");
            }

            @Override
            public boolean isConcurrencySafe() { return true; }
        };
    }

    @Test
    void concurrentTimeoutResultCarriesOriginalCallIdAndName() {
        registry.register(slowSafeTool("slow"));

        List<ToolResult> results = executor.executeConcurrently(
                List.of(new ToolExecutor.ToolCallRequest("call-42", "slow", Map.of())),
                new ToolContext("user1", "session1", "call-42"),
                0L); // 立即超时（测试专用短超时）

        assertEquals(1, results.size());
        ToolResult r = results.get(0);
        assertTrue(r.isError());
        assertEquals(ToolResult.ErrorType.TIMEOUT, r.getErrorType());
        // O10 核心：不再返回空串 toolCallId/toolName
        assertEquals("call-42", r.getToolCallId());
        assertEquals("slow", r.getToolName());
    }

    @Test
    void concurrentExceptionResultCarriesOriginalCallIdAndName() {
        registry.register(new Tool() {
            @Override
            public String name() { return "boom"; }

            @Override
            public String description() { return "throws"; }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResult call(Map<String, Object> input, ToolContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean isConcurrencySafe() { return true; }
        });

        List<ToolResult> results = executor.executeConcurrently(
                List.of(new ToolExecutor.ToolCallRequest("call-7", "boom", Map.of())),
                new ToolContext("user1", "session1", "call-7"),
                5L);

        assertEquals(1, results.size());
        ToolResult r = results.get(0);
        assertTrue(r.isError());
        assertEquals("call-7", r.getToolCallId());
        assertEquals("boom", r.getToolName());
    }

    @Test
    void guardrailErrorTypeExists() {
        // O10: GUARDRAIL 枚举扩展向后兼容（新值可用且不影响既有四类）
        assertEquals(5, ToolResult.ErrorType.values().length);
        assertNotNull(ToolResult.ErrorType.valueOf("GUARDRAIL"));
        ToolResult guarded = ToolResult.error("c1", "t1", "blocked", ToolResult.ErrorType.GUARDRAIL);
        assertTrue(guarded.isError());
        assertEquals(ToolResult.ErrorType.GUARDRAIL, guarded.getErrorType());
    }
}

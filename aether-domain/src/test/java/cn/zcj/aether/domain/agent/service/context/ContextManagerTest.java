package cn.zcj.aether.domain.agent.service.context;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 单元测试 — P2 上下文压缩守卫。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>TokenEstimator 基本估算</li>
 *   <li>AutoCompactResult 构造</li>
 *   <li>alignToolPairBoundaries — 配对边界对齐</li>
 *   <li>trimMessages — 消息修剪 + 占位</li>
 *   <li>microCompact — 配对安全断言</li>
 *   <li>ModelContextWindowRegistry — 配置化窗口查询</li>
 *   <li>摘要防污染前缀常量验证</li>
 * </ul>
 */
class ContextManagerTest {

    private TokenEstimator tokenEstimator;
    private ModelContextWindowRegistry windowRegistry;

    @BeforeEach
    void setUp() {
        windowRegistry = new ModelContextWindowRegistry();
        tokenEstimator = new TokenEstimator();
        // 手动注入 Registry（无 Spring 容器）
        try {
            var field = TokenEstimator.class.getDeclaredField("windowRegistry");
            field.setAccessible(true);
            field.set(tokenEstimator, windowRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ========== TokenEstimator ==========

    @Test
    void tokenEstimatorShouldEstimateEnglishText() {
        String text = "Hello world, this is a test message with multiple words.";
        int tokens = tokenEstimator.estimate(text);
        assertTrue(tokens > 0, "English text should estimate > 0 tokens");
    }

    @Test
    void tokenEstimatorShouldEstimateChineseText() {
        String text = "这是一段中文测试文本";
        int tokens = tokenEstimator.estimate(text);
        assertTrue(tokens > 0, "Chinese text should estimate > 0 tokens");
    }

    @Test
    void tokenEstimatorShouldHandleNull() {
        assertEquals(0, tokenEstimator.estimate(null));
    }

    @Test
    void tokenEstimatorShouldHandleEmpty() {
        assertEquals(0, tokenEstimator.estimate(""));
    }

    @Test
    void tokenEstimatorShouldHandleShortText() {
        assertEquals(1, tokenEstimator.estimate("Hi"));
    }

    @Test
    void tokenEstimatorShouldBeProportional() {
        String shortText = "Hello";
        String longText = "Hello world this is a much longer piece of text with many more words to count tokens for";

        int shortTokens = tokenEstimator.estimate(shortText);
        int longTokens = tokenEstimator.estimate(longText);
        assertTrue(longTokens > shortTokens, "Longer text should have more tokens");
    }

    // ========== AutoCompactResult ==========

    @Test
    void autoCompactResultNotNeeded() {
        AutoCompactResult result = AutoCompactResult.notNeeded();
        assertFalse(result.isCompacted());
    }

    @Test
    void autoCompactResultCompacted() {
        List<TurnMessage> compactedList = List.of(
            TurnMessage.user("summary text"),
            TurnMessage.assistant("response")
        );
        AutoCompactResult result = AutoCompactResult.compacted(
            "对话摘要", compactedList, 1000, 500);

        assertTrue(result.isCompacted());
        assertEquals("对话摘要", result.getSummary());
        assertEquals(1000, result.getPreCompactTokens());
        assertEquals(500, result.getPostCompactTokens());
    }

    // ========== P2-1: alignToolPairBoundaries ==========

    @Test
    void alignToolPairBoundariesShouldRemoveLeadingOrphanToolResults() {
        List<TurnMessage> messages = new ArrayList<>(List.of(
                TurnMessage.toolResult("tc-1", "read", "result1"),
                TurnMessage.toolResult("tc-2", "write", "result2"),
                TurnMessage.user("hello"),
                TurnMessage.assistant("hi")
        ));

        ContextManager cm = newContextManager();
        cm.alignToolPairBoundaries(messages);

        assertEquals(2, messages.size(), "应移除开头两条孤儿 tool_result");
        assertEquals("user", messages.get(0).role());
    }

    @Test
    void alignToolPairBoundariesShouldRemoveTrailingDanglingToolUse() {
        List<TurnMessage> messages = new ArrayList<>(List.of(
                TurnMessage.user("hello"),
                TurnMessage.assistant("hi"),
                TurnMessage.assistantWithToolCalls("let me check",
                        List.of(Map.of("id", "tc-1", "name", "read", "input", Map.of())))
        ));

        ContextManager cm = newContextManager();
        cm.alignToolPairBoundaries(messages);

        assertEquals(2, messages.size(), "应移除末尾悬空 assistant-with-tool-calls");
        assertEquals("assistant", messages.get(1).role());
        assertFalse(messages.get(1).hasToolCalls());
    }

    @Test
    void alignToolPairBoundariesShouldHandleBothEnds() {
        List<TurnMessage> messages = new ArrayList<>(List.of(
                TurnMessage.toolResult("tc-1", "read", "orphan"),
                TurnMessage.user("hello"),
                TurnMessage.assistantWithToolCalls("checking",
                        List.of(Map.of("id", "tc-2", "name", "search", "input", Map.of())))
        ));

        ContextManager cm = newContextManager();
        cm.alignToolPairBoundaries(messages);

        assertEquals(1, messages.size(), "应同时移除头尾");
        assertEquals("user", messages.get(0).role());
    }

    @Test
    void alignToolPairBoundariesShouldNotTouchCleanList() {
        List<TurnMessage> messages = new ArrayList<>(List.of(
                TurnMessage.user("hello"),
                TurnMessage.assistant("hi"),
                TurnMessage.user("how are you")
        ));

        ContextManager cm = newContextManager();
        cm.alignToolPairBoundaries(messages);

        assertEquals(3, messages.size(), "干净列表不应被修改");
    }

    @Test
    void alignToolPairBoundariesShouldHandleEmptyList() {
        List<TurnMessage> messages = new ArrayList<>();
        ContextManager cm = newContextManager();
        assertDoesNotThrow(() -> cm.alignToolPairBoundaries(messages));
        assertTrue(messages.isEmpty());
    }

    @Test
    void alignToolPairBoundariesShouldHandleNull() {
        ContextManager cm = newContextManager();
        assertNull(cm.alignToolPairBoundaries(null));
    }

    // ========== P2-2: trimMessages ==========

    @Test
    void trimMessagesShouldTrimAndInsertPlaceholder() {
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("system initial"));
        for (int i = 0; i < 210; i++) {
            messages.add(TurnMessage.user("message " + i));
        }

        ContextManager cm = newContextManager();
        cm.trimMessages(messages, 200);

        assertTrue(messages.size() <= 203, // 首条 + 占位 + 最多200条
                "修剪后应不超过 203 条（首条+占位+200）");
        assertEquals("system initial", messages.get(0).content());
        assertTrue(messages.get(1).content().contains("已跳过"),
                "第二条应为占位消息，包含'已跳过'");
    }

    @Test
    void trimMessagesShouldNotTrimSmallList() {
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("system"));
        for (int i = 0; i < 50; i++) {
            messages.add(TurnMessage.user("msg " + i));
        }

        ContextManager cm = newContextManager();
        int originalSize = messages.size();
        cm.trimMessages(messages, 200);

        assertEquals(originalSize, messages.size(), "小列表不应被修剪");
    }

    @Test
    void trimMessagesShouldHandleEmptyList() {
        List<TurnMessage> messages = new ArrayList<>();
        ContextManager cm = newContextManager();
        assertDoesNotThrow(() -> cm.trimMessages(messages, 200));
    }

    // ========== P2-6: microCompact 配对安全 ==========

    @Test
    void microCompactShouldPreservePairingIntegrity() {
        // 模拟一条 tool_use + tool_result 被覆盖，一条保留
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("edit file A"));
        // 第一次编辑 file.txt
        var toolUse1 = new TurnMessage("tool_use",
                "{\"file_path\":\"/app/file.txt\",\"new_str\":\"v1\"}",
                null, "Edit", null);
        messages.add(toolUse1);
        messages.add(TurnMessage.toolResult("tc-1", "Edit", "success"));
        // 第二次编辑 file.txt（最终版本，应保留）
        var toolUse2 = new TurnMessage("tool_use",
                "{\"file_path\":\"/app/file.txt\",\"new_str\":\"v2\"}",
                null, "Edit", null);
        messages.add(toolUse2);
        messages.add(TurnMessage.toolResult("tc-2", "Edit", "success"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        // 验证：被保留的消息中不会出现孤立的 tool_use（无配对 tool_result）
        for (int i = 0; i < compacted.size(); i++) {
            TurnMessage msg = compacted.get(i);
            if (msg.isToolUse()) {
                // tool_use 后面必须紧跟 tool_result（或至少在列表中）
                boolean hasResult = (i + 1 < compacted.size() && compacted.get(i + 1).isToolResult());
                assertTrue(hasResult,
                        "每条被保留的 tool_use 必须有配对的 tool_result 紧随其后，索引=" + i);
            }
        }

        // 验证：被保留的消息中不会出现孤立的 tool_result（无前置 tool_use）
        for (int i = 0; i < compacted.size(); i++) {
            TurnMessage msg = compacted.get(i);
            if (msg.isToolResult()) {
                boolean hasToolUse = (i > 0 && compacted.get(i - 1).isToolUse());
                assertTrue(hasToolUse,
                        "每条被保留的 tool_result 必须有配对的 tool_use 在其前面，索引=" + i);
            }
        }
    }

    @Test
    void microCompactShouldNotRemoveLastWrite() {
        // 只有一次编辑操作 → 不应被移除
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("edit"));
        var toolUse = new TurnMessage("tool_use",
                "{\"file_path\":\"/app/file.txt\"}", null, "Edit", null);
        messages.add(toolUse);
        messages.add(TurnMessage.toolResult("tc-1", "Edit", "success"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertEquals(3, compacted.size(), "最后一次写操作不应被移除");
    }

    // ========== P2-5: ModelContextWindowRegistry ==========

    @Test
    void registryShouldReturnDefaultForUnknownModel() {
        assertEquals(128_000, windowRegistry.getContextWindow("unknown-model-xyz"));
    }

    @Test
    void registryShouldReturnDefaultForNull() {
        assertEquals(128_000, windowRegistry.getContextWindow(null));
    }

    @Test
    void registryShouldMatchKeyword() {
        assertEquals(200_000, windowRegistry.getContextWindow("claude-sonnet-4-6"));
        assertEquals(128_000, windowRegistry.getContextWindow("gpt-4-turbo"));
        assertEquals(16_000, windowRegistry.getContextWindow("gpt-3.5-turbo"));
        assertEquals(128_000, windowRegistry.getContextWindow("deepseek-v4-flash"));
    }

    @Test
    void registryShouldMatchExactFirst() {
        windowRegistry.register("gpt-4-custom", 256_000);
        // 精确匹配优先于关键字匹配
        assertEquals(256_000, windowRegistry.getContextWindow("gpt-4-custom"));
    }

    @Test
    void registryShouldMatchKeywordFallback() {
        // 未精确注册 → 回退到关键字匹配
        assertEquals(200_000, windowRegistry.getContextWindow("claude-unknown-variant"));
    }

    @Test
    void registryShouldSupportCustomKeyword() {
        windowRegistry.registerKeyword("custom-model", 64_000);
        assertEquals(64_000, windowRegistry.getContextWindow("my-custom-model-v2"));
    }

    @Test
    void registryShouldRegisterBatch() {
        windowRegistry.registerAll(Map.of(
                "model-a", 32_000,
                "model-b", 64_000
        ));
        assertEquals(32_000, windowRegistry.getContextWindow("model-a"));
        assertEquals(64_000, windowRegistry.getContextWindow("model-b"));
    }

    // ========== 摘要防污染前缀 ==========

    @Test
    void summaryPrefixShouldContainNonActiveDirective() {
        assertTrue(ContextManager.SUMMARY_PREFIX.contains("仅供参考"),
                "摘要前缀应包含'仅供参考'");
        assertTrue(ContextManager.SUMMARY_PREFIX.contains("非活跃指令"),
                "摘要前缀应包含'非活跃指令'");
        assertTrue(ContextManager.SUMMARY_PREFIX.contains("勿直接执行"),
                "摘要前缀应包含'勿直接执行'");
    }

    // ========== helpers ==========

    private ContextManager newContextManager() {
        ContextManager cm = new ContextManager();
        try {
            var field = ContextManager.class.getDeclaredField("tokenEstimator");
            field.setAccessible(true);
            field.set(cm, tokenEstimator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cm;
    }
}

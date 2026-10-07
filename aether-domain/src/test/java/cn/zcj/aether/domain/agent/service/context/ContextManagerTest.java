package cn.zcj.aether.domain.agent.service.context;

import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        windowRegistry.register("p0-model", 8_000); // 缩小窗口以便压缩触发
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
    //
    // 纪律（2026-10-07 修复）：microCompact 的测试**一律**用 TurnMessage.assistantWithToolCalls(...)
    // 构造消息——那才是生产形状。new TurnMessage("tool_use", ...) 是生产从不产生的形状，
    // 正是它让 microCompact 整体失效却测试全绿
    // （见 docs/superpowers/specs/2026-10-07-microcompact-write-dedup-fix-design.md §2 缺陷 A）。
    // 唯一例外是 microCompactShouldNotTreatStandaloneToolUseAsWrite：它专门锁定「该形状不参与去重」。

    @Test
    void microCompactShouldPreservePairingIntegrity() {
        // 生产形状：同一路径写两次，第一次被覆盖，第二次保留
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("edit file A"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-1", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "success"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-2", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-2", "Write", "success"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactShouldNotRemoveLastWrite() {
        // 只有一次编辑操作 → 不应被移除
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("edit"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-1", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "success"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertEquals(3, compacted.size(), "最后一次写操作不应被移除");
    }

    @Test
    void microCompactShouldRemoveCoveredWriteInProductionShape() {
        // T1：生产形状下，被后续写覆盖的写操作必须真的被移除（修复前该方法整体空转）
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("edit file A"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-1", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "v1"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-2", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-2", "Write", "v2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertTrue(compacted.size() < messages.size(),
                "被覆盖的写操作应被移除，实际 " + messages.size() + " → " + compacted.size());
        assertEquals(1, compacted.stream().filter(TurnMessage::hasToolCalls).count(),
                "只有最后一次写应保留");
        assertEquals("tc-2",
                compacted.stream().filter(TurnMessage::hasToolCalls)
                        .findFirst().orElseThrow().toolCalls().get(0).get("id"),
                "保留的必须是最后一次写");
        assertFalse(compacted.stream().anyMatch(m -> "tc-1".equals(m.toolCallId())),
                "被覆盖写操作的 tool_result 应一并移除");
        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactShouldKeepMessageMixingWriteAndRead() {
        // T2：Q1 保守规则 —— 并行批次里混有非写调用时，整条消息不参与去重
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("batch"));
        messages.add(TurnMessage.assistantWithToolCalls("",
                List.of(writeCall("tc-1", "/app/file.txt"), readCall("tc-2", "/app/other.txt"))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "ok"));
        messages.add(TurnMessage.toolResult("tc-2", "Read", "content"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-3", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-3", "Write", "v2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertEquals(messages.size(), compacted.size(),
                "含非写调用的消息整条保留，不得重建消息");
        assertTrue(compacted.stream().anyMatch(m -> "tc-2".equals(m.toolCallId())),
                "配对的 Read 结果不得成为孤儿");
        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactShouldRemoveAllPairedResultsOfMultiCallMessage() {
        // T3：一条消息携带 N 个 toolCall 全为同一路径的写 → 消息与全部 N 条 tool_result 一并移除
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("batch writes"));
        messages.add(TurnMessage.assistantWithToolCalls("",
                List.of(writeCall("tc-1", "/app/file.txt"), writeCall("tc-2", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "ok1"));
        messages.add(TurnMessage.toolResult("tc-2", "Write", "ok2"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-3", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-3", "Write", "final"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertTrue(compacted.stream().noneMatch(m -> "tc-1".equals(m.toolCallId())),
                "多条 tool_result 必须全部移除，不能只删紧邻的一条");
        assertTrue(compacted.stream().noneMatch(m -> "tc-2".equals(m.toolCallId())));
        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactShouldKeepMessageWhenPathNotExtractable() {
        // T4：路径抠不出 → 无法证明被覆盖 → 整条保留
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("write without path"));
        messages.add(TurnMessage.assistantWithToolCalls("",
                List.of(Map.of("id", "tc-1", "name", "Write", "input", Map.of("content", "raw")))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "ok"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-2", "/app/file.txt"))));
        messages.add(TurnMessage.toolResult("tc-2", "Write", "ok2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertEquals(messages.size(), compacted.size(), "路径不可提取时整条保留");
        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactShouldNotTreatBashToolAsWrite() {
        // T5：BashTool 已移出写工具白名单 —— 命令行里的路径是猜的，猜错方向是误删
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("shell"));
        messages.add(TurnMessage.assistantWithToolCalls("",
                List.of(Map.of("id", "tc-1", "name", "BashTool", "input", Map.of("command", "mv a.txt b.txt")))));
        messages.add(TurnMessage.toolResult("tc-1", "BashTool", "ok"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-2", "/app/a.txt"))));
        messages.add(TurnMessage.toolResult("tc-2", "Write", "ok2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertTrue(compacted.stream().anyMatch(m -> "tc-1".equals(m.toolCallId())),
                "BashTool 调用不参与写去重，不得被移除");
        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactPlaceholderShouldNamePathAndReason() {
        // T7：省略必须对模型可见 —— 占位为 user 角色（配对中立）并点出路径与原因
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("edit"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-1", "/app/config.yml"))));
        messages.add(TurnMessage.toolResult("tc-1", "Write", "v1"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("tc-2", "/app/config.yml"))));
        messages.add(TurnMessage.toolResult("tc-2", "Write", "v2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        TurnMessage placeholder = compacted.stream()
                .filter(m -> m.content() != null && m.content().startsWith("[系统提示]"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("压缩后应存在占位消息"));
        assertEquals("user", placeholder.role(),
                "占位必须是 user 角色：tool_result 角色会构成孤儿 tool_result");
        assertTrue(placeholder.content().contains("config.yml"), "占位应点出路径名");
        assertTrue(placeholder.content().contains("已被后续写入覆盖"), "占位应说明省略原因");
    }

    @Test
    void microCompactShouldPreservePairingInvariantOnMixedList() {
        // T6：全量配对不变式守卫（写/读混合 + 多路径 + 多批次）
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("mixed"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("a-1", "/app/a.txt"), readCall("a-2", "/app/b.txt"))));
        messages.add(TurnMessage.toolResult("a-1", "Write", "ok"));
        messages.add(TurnMessage.toolResult("a-2", "Read", "content"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("a-3", "/app/a.txt"))));
        messages.add(TurnMessage.toolResult("a-3", "Write", "v2"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("a-4", "/app/c.txt"))));
        messages.add(TurnMessage.toolResult("a-4", "Write", "v1"));
        messages.add(TurnMessage.assistantWithToolCalls("", List.of(writeCall("a-5", "/app/c.txt"))));
        messages.add(TurnMessage.toolResult("a-5", "Write", "v2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertFalse(compacted.stream().anyMatch(m -> "a-4".equals(m.toolCallId())),
                "被覆盖的写与其结果应一并移除");
        assertPairingInvariant(compacted);
    }

    @Test
    void microCompactShouldNotTreatStandaloneToolUseAsWrite() {
        // 独立 tool_use 形状全项目无产出方（见 spec §12），不参与写去重——按原样保留
        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("legacy shape"));
        messages.add(new TurnMessage("tool_use", "{\"file_path\":\"/app/legacy.txt\"}", "l-1", "Edit", null));
        messages.add(TurnMessage.toolResult("l-1", "Edit", "v1"));
        messages.add(new TurnMessage("tool_use", "{\"file_path\":\"/app/legacy.txt\"}", "l-2", "Edit", null));
        messages.add(TurnMessage.toolResult("l-2", "Edit", "v2"));

        ContextManager cm = newContextManager();
        List<TurnMessage> compacted = cm.microCompact(messages);

        assertEquals(messages.size(), compacted.size(),
                "无产出方的形状不得参与去重，也不得被误删");
        assertPairingInvariant(compacted);
    }

    private static Map<String, Object> writeCall(String id, String path) {
        return Map.of("id", id, "name", "Write", "input", Map.of("file_path", path));
    }

    private static Map<String, Object> readCall(String id, String path) {
        return Map.of("id", id, "name", "Read", "input", Map.of("file_path", path));
    }

    /**
     * 配对不变式：列表中声明的 toolCallId（独立 tool_use 消息 + assistant 消息的 toolCalls）
     * 与 tool_result 携带的 toolCallId 必须一一对应 —— 不允许孤儿 tool_result，也不允许悬空 tool_use。
     */
    private static void assertPairingInvariant(List<TurnMessage> messages) {
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        java.util.Set<String> results = new java.util.LinkedHashSet<>();
        for (TurnMessage msg : messages) {
            if (msg.hasToolCalls()) {
                for (Map<String, Object> tc : msg.toolCalls()) {
                    declared.add(String.valueOf(tc.get("id")));
                }
            } else if (msg.isToolUse()) {
                declared.add(msg.toolCallId());
            }
            if (msg.isToolResult()) {
                results.add(msg.toolCallId());
            }
        }
        assertEquals(declared, results,
                "声明的 toolCallId 与 tool_result 的 toolCallId 必须一一对应");
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
        ContextManager cm = new ContextManager(null, null);
        try {
            var field = ContextManager.class.getDeclaredField("tokenEstimator");
            field.setAccessible(true);
            field.set(cm, tokenEstimator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cm;
    }

    // ========== O7: 摘要纳入管线 ==========

    private void injectField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ContextManager newCompactionContextManager(ModelInvoker invoker, ModelPricingRegistry pricing)
            throws Exception {
        ContextManager cm = new ContextManager(invoker, pricing);
        injectField(cm, "tokenEstimator", tokenEstimator);
        SummaryChatModelResolver resolver = mock(SummaryChatModelResolver.class);
        when(resolver.resolve()).thenReturn(mock(ChatModel.class));
        injectField(cm, "summaryChatModelResolver", resolver);
        return cm;
    }

    private List<TurnMessage> manyMessages(int n) {
        List<TurnMessage> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(TurnMessage.user("message " + i + " " + "x".repeat(50)));
        }
        return msgs;
    }

    @Test
    void autoCompactRoutesSummaryThroughModelInvokerAndAccumulatesCost() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenReturn(ModelInvoker.ModelCallResult.builder()
                        .fullText("summary ok").inputTokens(100).outputTokens(50)
                        .events(List.of()).toolCalls(List.of()).build());
        ModelPricingRegistry pricing = mock(ModelPricingRegistry.class);
        when(pricing.lookup("p0-model")).thenReturn(new ModelPricing("p0-model", 0.001, 0.002));

        ContextManager cm = newCompactionContextManager(invoker, pricing);
        TokenBudget budget = mock(TokenBudget.class);

        AutoCompactResult result = cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-1", budget);

        assertTrue(result.isCompacted());
        verify(invoker).callWithStream(any(), anyList(), anyString(), eq("p0-model"));
        verify(budget).accumulateCost(eq(100), eq(50), any(ModelPricing.class));
    }

    @Test
    void summaryCooldownSkipsLlmCallAfterFailure() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenReturn(ModelInvoker.ModelCallResult.error("boom"));
        ContextManager cm = newCompactionContextManager(invoker, mock(ModelPricingRegistry.class));

        cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-2", mock(TokenBudget.class));
        assertTrue(cm.isInSummaryCooldown("s-2"));

        cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-2", mock(TokenBudget.class));
        verify(invoker, times(1)).callWithStream(any(), anyList(), anyString(), anyString());
    }

    // ========== O8: 压缩策略参数化 ==========

    private cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger triggerOf(ContextManager cm)
            throws Exception {
        var f = ContextManager.class.getDeclaredField("compactionTrigger");
        f.setAccessible(true);
        return (cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger) f.get(cm);
    }

    @Test
    void thresholdPercentAndMinMessagesAreConfigurable() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenReturn(ModelInvoker.ModelCallResult.builder()
                        .fullText("s").inputTokens(1).outputTokens(1)
                        .events(List.of()).toolCalls(List.of()).build());
        ContextManager cm = newCompactionContextManager(invoker, mock(ModelPricingRegistry.class));
        cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger trigger = triggerOf(cm);

        // threshold-percent 收紧到 0.05、min-messages 降到 3 → 少量消息也应触发
        java.lang.reflect.Field tp = trigger.getClass().getDeclaredField("thresholdPercent");
        tp.setAccessible(true);
        tp.set(trigger, 0.05);
        java.lang.reflect.Field mm = trigger.getClass().getDeclaredField("minMessagesToCompact");
        mm.setAccessible(true);
        mm.set(trigger, 3);

        // p0-model 窗口 8000，effectiveWindow=8000-20000 为负 → 任何 token 都超过阈值
        AutoCompactResult result = cm.autoCompactIfNeeded(manyMessages(5), "p0-model", "s-tp", mock(TokenBudget.class));
        assertTrue(result.isCompacted(), "threshold-percent=0.05 + min-messages=3 时 5 条消息应触发压缩");

        // 默认 min-messages=20 时同样 5 条消息不应触发
        ContextManager cm2 = newCompactionContextManager(invoker, mock(ModelPricingRegistry.class));
        AutoCompactResult r2 = cm2.autoCompactIfNeeded(manyMessages(5), "p0-model", "s-tp2", mock(TokenBudget.class));
        assertFalse(r2.isCompacted(), "默认 min-messages-to-compact=20 时 5 条消息不应触发压缩");
    }

    @Test
    void protectFirstNPreservesHeadMessages() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenReturn(ModelInvoker.ModelCallResult.builder()
                        .fullText("s").inputTokens(1).outputTokens(1)
                        .events(List.of()).toolCalls(List.of()).build());
        ContextManager cm = newCompactionContextManager(invoker, mock(ModelPricingRegistry.class));
        cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger trigger = triggerOf(cm);
        java.lang.reflect.Field pf = trigger.getClass().getDeclaredField("protectFirstN");
        pf.setAccessible(true);
        pf.set(trigger, 2);
        java.lang.reflect.Field pl = trigger.getClass().getDeclaredField("protectLastN");
        pl.setAccessible(true);
        pl.set(trigger, 3);

        List<TurnMessage> messages = new ArrayList<>();
        messages.add(TurnMessage.user("system-prompt-0"));
        messages.add(TurnMessage.user("important-context-1"));
        messages.addAll(manyMessages(25));

        AutoCompactResult result = cm.autoCompactIfNeeded(messages, "p0-model", "s-pf", mock(TokenBudget.class));
        assertTrue(result.isCompacted());
        assertEquals("system-prompt-0", ((TurnMessage) result.getCompressedMessages().get(0)).content(),
                "头部保护消息应原样保留在最前");
        assertEquals("important-context-1", ((TurnMessage) result.getCompressedMessages().get(1)).content(),
                "protect-first-n=2 时前两条应原样保留");
    }

    @Test
    void ineffectiveCompressionTriggersSuppressRounds() throws Exception {
        // LLM 摘要几乎不缩减 token（长摘要）→ 降幅 < 5% → 进入防抖冷却
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenAnswer(inv -> ModelInvoker.ModelCallResult.builder()
                        .fullText("冗长摘要 ".repeat(200)) // ≈1000+ 字符，与原内容量级相当
                        .inputTokens(1).outputTokens(1)
                        .events(List.of()).toolCalls(List.of()).build());
        ContextManager cm = newCompactionContextManager(invoker, mock(ModelPricingRegistry.class));

        AutoCompactResult first = cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-inef", mock(TokenBudget.class));
        assertTrue(first.isCompacted());
        assertTrue(cm.getIneffectiveSuppressRounds("s-inef") > 0,
                "无有效进展的压缩应进入防抖冷却");

        // 冷却轮数内再次调用 → 不触发压缩（LLM 不再被调用）
        int callsAfterFirst = 1;
        AutoCompactResult second = cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-inef", mock(TokenBudget.class));
        assertFalse(second.isCompacted(), "防抖冷却轮数内应跳过压缩");
        verify(invoker, times(callsAfterFirst)).callWithStream(any(), anyList(), anyString(), anyString());
    }

    @Test
    void tailTokenBudgetComputesFromWindow() {
        var trigger = new cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger();
        assertEquals(-1, trigger.tailTokenBudget(128_000), "tail-token-ratio=0 时应禁用 token 预算");
    }

    @Test
    void tokenEstimatorDelegatesWindowToRegistryOnly() {
        // O8: Registry 未注入时回退默认常量（硬编码窗口表已删除）
        TokenEstimator bare = new TokenEstimator();
        assertEquals(128_000, bare.getContextWindow("claude-sonnet-4-6"),
                "Registry 未注入时应返回默认 128k，而非命中已删除的 claude 关键字表");
        assertEquals(128_000, bare.getContextWindow(null));
        // Registry 注入后精确匹配生效
        assertEquals(200_000, tokenEstimator.getContextWindow("claude-sonnet-4-6"));
    }

    @Test
    void applyToolResultBudgetWritesOverflowToDisk() throws Exception {
        ContextManager cm = newContextManager();
        var offloader = new cn.zcj.aether.domain.agent.service.context.compaction.MessageOffloader();
        java.lang.reflect.Field f = ContextManager.class.getDeclaredField("messageOffloader");
        f.setAccessible(true);
        f.set(cm, offloader);

        String big = "x".repeat(60_000);
        List<TurnMessage> messages = List.of(TurnMessage.toolResult("tc-1", "Bash", big));

        List<TurnMessage> result = cm.applyToolResultBudget(messages);
        assertEquals(1, result.size());
        String content = result.get(0).content();
        assertTrue(content.length() < 1000, "超长工具结果应被替换为预览+路径引用");
        assertTrue(content.contains("已存盘"), "替换文本应包含存盘标记");
        assertTrue(content.contains(".txt"), "替换文本应包含存盘路径引用");
        // 文件真实存在且内容完整
        var m = java.util.regex.Pattern.compile("tool-overflow/[\\w\\-./]+\\.txt").matcher(content);
        assertTrue(m.find(), "替换文本应包含 tool-overflow/ 路径引用");
        Path overflow = Path.of(System.getProperty("user.dir"), ".aether/sessions", m.group());
        assertTrue(Files.exists(overflow), "存盘文件应存在: " + overflow);
        assertEquals(60_000, Files.readString(overflow).length());
    }

    @Test
    void applyToolResultBudgetFallsBackToTruncateWhenOffloaderMissing() {
        ContextManager cm = newContextManager(); // messageOffloader 未注入（null）
        String big = "y".repeat(60_000);
        List<TurnMessage> result = cm.applyToolResultBudget(
                List.of(TurnMessage.toolResult("tc-2", "Bash", big)));
        assertTrue(result.get(0).content().contains("已截断"));
        assertFalse(result.get(0).content().contains("已存盘"));
    }
}

# Aether 记忆系统对齐 hermes 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Aether Java 后端新增 `memory.core` 层（MemoryProvider SPI + MemoryManager + BuiltinMemoryProvider + MemoryContextScrubber + MemoryProperties + MemoryLifecycleHooks），对齐 hermes 记忆系统的生命周期钩子、配置项与上下文围栏净化；复用现有 `MemoryFacade` 体系作为 builtin provider 内部实现；并在 ChatService 接入 prefetch / syncTurn / onSessionEnd。

**Architecture:** 新增 `cn.zcj.aether.domain.agent.service.memory.core` 包（纯 Java 17，无新依赖）。`MemoryLifecycleHooks`（Spring `@Configuration`）负责组装 `MemoryManager` + `BuiltinMemoryProvider`；`BuiltinMemoryProvider` 委托现有 `MemoryFacade`（编码/召回/向量存储全部复用，不改动）；`ChatService` 只依赖 `MemoryLifecycleHooks`，在 turn 前 prefetch、turn 后 syncTurn、会话删除时 onSessionEnd。

**Tech Stack:** Java 17、Spring Boot 3（spring-boot-starter-test / mockito-junit-jupiter）、Maven 3.9.9、RxJava Flowable（流式路径）。单元测试为纯 JUnit 5（无 Spring 上下文、无数据库）。

**Spec:** `docs/superpowers/specs/2026-08-06-aether-memory-alignment-design.md`（已评审）

---

## 文件结构

**新增主代码**（`aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/`）：

| 文件 | 职责 |
|---|---|
| `MemoryProperties.java` | `@ConfigurationProperties(prefix="aether.memory")`，默认值对齐 hermes（char 2200/1375、nudge 10、flush 6、max-results 10、阈值 0.85） |
| `MemoryInitContext.java` | `initialize()` 上下文 record |
| `MemoryProvider.java` | SPI：name/isAvailable/initialize/prefetch/queuePrefetch/syncTurn/getToolSchemas/handleToolCall/shutdown + 可选钩子 |
| `MemoryManager.java` | 编排：builtin 恒接受、外部只许一个；turn 计数/nudge；单线程异步同步 executor；drain/shutdown |
| `MemoryContextScrubber.java` | 静态 `sanitize` + 有状态 `StreamingScrubber`（跨分片标签状态机） |
| `BuiltinMemoryProvider.java` | name="builtin"，prefetch→`MemoryFacade.search`，syncTurn→`MemoryFacade.remember`，scope 启发式分类，`<memory-context>` 围栏格式化 |
| `MemoryLifecycleHooks.java` | Spring 门面，`@EnableConfigurationProperties`，组装 manager，暴露 prefetch/syncTurn/onSessionEnd |

**新增测试**（`aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/`）：

| 文件 | 覆盖 |
|---|---|
| `MemoryPropertiesTest.java` | 默认值对齐 hermes + setter 绑定 |
| `MemoryProviderTest.java` | SPI 默认行为 + handleToolCall 抛异常 |
| `MemoryContextScrubberTest.java` | 单次净化 + 流式跨分片 |
| `MemoryManagerTest.java` | builtin/外部约束、nudge、delegation、drain |
| `FakeMemoryFacade.java` | 测试双：内存余弦相似度检索 |
| `BuiltinMemoryProviderTest.java` | 核心验收：写 10 条 → 精确检索 |
| `MemoryLifecycleHooksTest.java` | 组装、enabled 开关、flush-min-turns |

**修改文件**：

| 文件 | 改动 |
|---|---|
| `aether-domain/.../chat/ChatService.java` | 注入 `MemoryLifecycleHooks`；`injectMemory` 改走 `hooks.prefetch`；同步/流式路径 turn 后 `hooks.syncTurn`；`deleteSession` 加 `onSessionEnd` |
| `aether-app/src/main/resources/application.yml` | 新增 `aether.memory` 配置段 |

**测试命令**（在 `aether/` 根目录执行，`-am` 连带构建依赖模块）：

```bash
mvn -pl aether-domain -am test -Dtest=<TestClass> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

---

## Task 1: MemoryProperties

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProperties.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryPropertiesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPropertiesTest {

    @Test
    void defaultsMatchHermes() {
        MemoryProperties p = new MemoryProperties();
        assertTrue(p.isEnabled());
        assertTrue(p.isUserProfileEnabled());
        assertEquals(2200, p.getMemoryCharLimit());
        assertEquals(1375, p.getUserCharLimit());
        assertEquals(10, p.getNudgeInterval());
        assertEquals(6, p.getFlushMinTurns());
        assertEquals(10, p.getRecall().getMaxResults());
        assertEquals(0.6f, p.getRecall().getSemanticWeight());
        assertEquals(0.3f, p.getRecall().getRecencyWeight());
        assertEquals(0.1f, p.getRecall().getImportanceWeight());
        assertEquals(0.85f, p.getRecall().getConsolidationThreshold());
        assertEquals(1280, p.getRecall().getVectorDimension());
    }

    @Test
    void settersBind() {
        MemoryProperties p = new MemoryProperties();
        p.setEnabled(false);
        p.setNudgeInterval(0);
        p.setMemoryCharLimit(500);
        p.getRecall().setMaxResults(5);
        p.getRecall().setConsolidationThreshold(0.9f);
        assertFalse(p.isEnabled());
        assertEquals(0, p.getNudgeInterval());
        assertEquals(500, p.getMemoryCharLimit());
        assertEquals(5, p.getRecall().getMaxResults());
        assertEquals(0.9f, p.getRecall().getConsolidationThreshold());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryPropertiesTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `MemoryPropertiesTest` cannot be resolved / compilation error (class not defined).

- [ ] **Step 3: Write the implementation**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆系统配置 —— 对齐 hermes cli-config.yaml 的 memory 段。
 *
 * <p>键前缀：{@code aether.memory}；默认值与 hermes 保持一致：</p>
 * <ul>
 *   <li>{@code enabled} ↔ hermes {@code memory.memory_enabled}（true）</li>
 *   <li>{@code user-profile-enabled} ↔ hermes {@code memory.user_profile_enabled}（true）</li>
 *   <li>{@code memory-char-limit} ↔ hermes {@code memory.memory_char_limit}（2200，≈800 token）</li>
 *   <li>{@code user-char-limit} ↔ hermes {@code memory.user_char_limit}（1375，≈500 token）</li>
 *   <li>{@code nudge-interval} ↔ hermes {@code memory.nudge_interval}（10，0=禁用）</li>
 *   <li>{@code flush-min-turns} ↔ hermes {@code memory.flush_min_turns}（6，0=禁用）</li>
 *   <li>{@code recall.max-results} ↔ hermes supermemory {@code max_recall_results}（10）</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "aether.memory", ignoreInvalidFields = true)
public class MemoryProperties {

    /** 记忆系统总开关 */
    private boolean enabled = true;

    /** 用户画像记忆开关 */
    private boolean userProfileEnabled = true;

    /** 注入记忆上下文预算（字符，≈800 token） */
    private int memoryCharLimit = 2200;

    /** 用户画像上下文预算（字符，≈500 token） */
    private int userCharLimit = 1375;

    /** nudge 轮次间隔，0=禁用 */
    private int nudgeInterval = 10;

    /** flush 最小轮次，0=禁用 */
    private int flushMinTurns = 6;

    /** 检索参数 */
    private Recall recall = new Recall();

    /** 检索参数子配置 */
    @Data
    public static class Recall {
        /** prefetch 检索 Top-K */
        private int maxResults = 10;
        /** 语义相似度权重 */
        private float semanticWeight = 0.6f;
        /** 时间衰减权重 */
        private float recencyWeight = 0.3f;
        /** 重要性权重 */
        private float importanceWeight = 0.1f;
        /** 相似度合并阈值 */
        private float consolidationThreshold = 0.85f;
        /** 向量维度（pgvector） */
        private int vectorDimension = 1280;
    }
}
```

> 说明：`ignoreInvalidFields = true` 与现有 `AiAgentAutoConfigProperties`（`ai.agent.config`）风格一致。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryPropertiesTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (BUILD SUCCESS, 2 tests run).

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProperties.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryPropertiesTest.java
git commit -m "feat(memory): 新增 MemoryProperties 配置对齐 hermes memory 段"
```

---

## Task 2: MemoryInitContext + MemoryProvider SPI

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryInitContext.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProvider.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProviderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MemoryProviderTest {

    /** 仅实现必需抽象方法的最小桩 */
    static class StubProvider implements MemoryProvider {
        public String name() { return "stub"; }
        public boolean isAvailable() { return true; }
        public void initialize(String sessionId, MemoryInitContext ctx) {}
        public List<Map<String, Object>> getToolSchemas() { return List.of(); }
    }

    @Test
    void defaultHooksAreNoOp() {
        MemoryProvider p = new StubProvider();
        assertEquals("", p.systemPromptBlock());
        assertEquals("", p.prefetch("query", "s1"));
        assertDoesNotThrow(() -> p.queuePrefetch("query", "s1"));
        assertDoesNotThrow(() -> p.syncTurn("u", "a", "s1", null));
        assertDoesNotThrow(() -> p.shutdown());
        assertDoesNotThrow(() -> p.onTurnStart(1, "m", null));
        assertDoesNotThrow(() -> p.onSessionEnd(null));
        assertDoesNotThrow(() -> p.onSessionSwitch("new", "old", false, false, Map.of()));
        assertEquals("", p.onPreCompress(null));
        assertDoesNotThrow(() -> p.onMemoryWrite("add", "memory", "c", null));
    }

    @Test
    void handleToolCallThrowsByDefault() {
        MemoryProvider p = new StubProvider();
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class, () -> p.handleToolCall("x", Map.of()));
        assertTrue(ex.getMessage().contains("stub"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryProviderTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (classes not defined).

- [ ] **Step 3: Write the implementation**

`MemoryInitContext.java`：

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import java.util.Map;

/**
 * MemoryProvider.initialize 上下文 —— 对齐 hermes MemoryProvider.initialize(**kwargs)
 * 中的关键键（agent_context / agent_identity / user_id / parent_session_id）。
 */
public record MemoryInitContext(
        /** "primary" | "subagent" | "cron" | "flush" */
        String agentContext,
        /** 配置档名（如 "coder"） */
        String agentIdentity,
        /** 平台用户标识 */
        String userId,
        /** 子 Agent 场景的父会话 ID */
        String parentSessionId,
        /** 其他扩展上下文 */
        Map<String, Object> extras
) {
    public static MemoryInitContext empty() {
        return new MemoryInitContext(null, null, null, null, Map.of());
    }
}
```

`MemoryProvider.java`：

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import java.util.List;
import java.util.Map;

/**
 * 可插拔记忆提供者 SPI —— 对齐 hermes agent/memory_provider.py 的 MemoryProvider ABC。
 *
 * <p>方法名按 Java 惯例 camelCase，语义与 hermes 完全一致：</p>
 * <pre>
 *   initialize()          连接/建资源/预热
 *   systemPromptBlock()   注入系统提示的静态说明
 *   prefetch(query)       turn 前后台召回（返回待注入上下文文本）
 *   queuePrefetch(query)  turn 后为下一轮入队后台召回
 *   syncTurn(user, asst)  turn 后异步持久化
 *   getToolSchemas()      暴露给模型的 tool schema（OpenAI function-calling 格式）
 *   handleToolCall()      分发一次 tool 调用，返回 JSON 字符串
 *   shutdown()            干净退出
 * </pre>
 * <p>可选钩子（默认 no-op，按需覆写）：{@code onTurnStart} / {@code onSessionEnd} /
 * {@code onSessionSwitch} / {@code onPreCompress} / {@code onMemoryWrite}。</p>
 */
public interface MemoryProvider {

    /** 提供者短标识，如 "builtin" */
    String name();

    /** 是否已配置且可激活；不应发起网络调用 */
    boolean isAvailable();

    /** 会话级初始化，启动时调用一次 */
    void initialize(String sessionId, MemoryInitContext ctx);

    /** 系统提示中的静态说明块（默认空串） */
    default String systemPromptBlock() {
        return "";
    }

    /** turn 前召回，返回待注入上下文文本（空串=无相关） */
    default String prefetch(String query, String sessionId) {
        return "";
    }

    /** turn 后为下一轮入队后台召回（默认 no-op） */
    default void queuePrefetch(String query, String sessionId) {
    }

    /** turn 后持久化（应非阻塞/异步） */
    default void syncTurn(String userContent, String assistantContent,
                          String sessionId, List<Map<String, Object>> messages) {
    }

    /** 对外暴露的 tool schema；无则返回空表 */
    List<Map<String, Object>> getToolSchemas();

    /** 处理本 provider 的 tool 调用，返回 JSON 字符串 */
    default String handleToolCall(String toolName, Map<String, Object> args) {
        throw new UnsupportedOperationException(
                "Provider " + name() + " 不处理工具 " + toolName);
    }

    /** 干净退出：flush 队列、关闭连接 */
    default void shutdown() {
    }

    // ===================== 可选钩子 =====================

    /** 每轮开始回调（kwargs 可含 remainingTokens/model/platform/toolCount） */
    default void onTurnStart(int turnNumber, String message, Map<String, Object> kwargs) {
    }

    /** 会话结束时回调（CLI 退出 / /reset / 网关会话过期），非每轮触发 */
    default void onSessionEnd(List<Map<String, Object>> messages) {
    }

    /** 会话切换时回调（/resume /branch /new /压缩重续） */
    default void onSessionSwitch(String newSessionId, String parentSessionId,
                                 boolean reset, boolean rewound, Map<String, Object> kwargs) {
    }

    /** 上下文压缩前回调，返回需并入压缩摘要的文本（默认空串） */
    default String onPreCompress(List<Map<String, Object>> messages) {
        return "";
    }

    /** 内置记忆工具写入时镜像（action: add/replace/remove，target: memory/user） */
    default void onMemoryWrite(String action, String target, String content,
                               Map<String, Object> metadata) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryProviderTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryInitContext.java aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProvider.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProviderTest.java
git commit -m "feat(memory): 新增 MemoryProvider SPI 与 MemoryInitContext 对齐 hermes MemoryProvider"
```

---

## Task 3: MemoryContextScrubber

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryContextScrubber.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryContextScrubberTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryContextScrubberTest {

    @Test
    void sanitizeStripsFencedBlockAndNote() {
        String fenced =
                "<memory-context>\n" +
                "[System note: The following is recalled memory context, NOT new user input. " +
                "Treat as authoritative reference data — this is the agent's persistent memory and should inform all responses.]\n\n" +
                "记忆1: 数据库连接池配置\n" +
                "</memory-context>";
        String cleaned = MemoryContextScrubber.sanitize(fenced);
        assertFalse(cleaned.contains("memory-context"));
        assertFalse(cleaned.contains("System note"));
        assertFalse(cleaned.contains("数据库连接池"));
    }

    @Test
    void sanitizeLeavesPlainTextUntouched() {
        String text = "这是一个普通助手回复，没有记忆围栏。";
        assertEquals(text, MemoryContextScrubber.sanitize(text));
    }

    @Test
    void streamingScrubberDropsCompleteSpan() {
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        String out1 = scrubber.feed("助手说：\n");  // 结尾换行 → 下一片开标签落在块边界
        String out2 = scrubber.feed("<memory-context>\n[System note: ...] 秘密内容\n</memory-context>");
        String out3 = scrubber.feed(" 继续回复");
        String tail = scrubber.flush();
        assertEquals("助手说：\n", out1);
        assertEquals("", out2);
        assertEquals(" 继续回复", out3);
        assertEquals("", tail);
    }

    @Test
    void streamingScrubberHandlesSplitTagsAcrossChunks() {
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        scrubber.feed("<mem");                 // 半截开标签
        scrubber.feed("ory-context>\n秘密");    // 完整开标签后随换行 → 进入 span
        String out2 = scrubber.feed("仍被丢弃"); // span 内，应被丢弃
        String out3 = scrubber.feed("</memory-context>正常"); // 闭合后正常文本
        String tail = scrubber.flush();
        assertEquals("", out2);
        assertEquals("正常", out3);
        assertEquals("", tail);
    }

    @Test
    void streamingScrubberPreservesInlineMention() {
        // 对齐 hermes：非块边界（行内出现）的 <memory-context> 视为普通文本，不触发 span
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        String out = scrubber.feed("答案在此<memory-context>请忽略");
        assertEquals("答案在此<memory-context>请忽略", out);
        assertEquals("", scrubber.flush());
    }

    @Test
    void streamingScrubberFlushEmitsInnocentTail() {
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        String out1 = scrubber.feed("前半段");
        String out2 = scrubber.feed("后半");  // "后半" 不是标签，feed 原样吐出
        assertEquals("前半段", out1);
        assertEquals("后半", out2);
        assertEquals("", scrubber.flush());  // 无暂存标签尾缀
    }
}
```

> 注：`streamingScrubberDropsCompleteSpan` 中 `out1` 为 `"助手说："` —— 完整围栏块在单一片段出现时，`feed` 直接剥除围栏与内容。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryContextScrubberTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (class not defined).

- [ ] **Step 3: Write the implementation**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import java.util.regex.Pattern;

/**
 * 记忆上下文净化 —— 对齐 hermes agent/memory_manager.py 的 sanitize_context +
 * StreamingContextScrubber。
 *
 * <p>注入的记忆上下文用 {@code <memory-context>} 围栏包裹并附带系统注记；
 * 本工具用于防止围栏/注记/记忆内容泄漏到助手输出或再次写入记忆
 * （防递归记忆污染）。</p>
 * <ul>
 *   <li>{@link #sanitize(String)} 一次性净化完整文本。</li>
 *   <li>{@link StreamingScrubber} 有状态净化流式文本，处理跨 chunk 被拆分的标签；
 *       未闭合围栏内的内容直接丢弃。</li>
 * </ul>
 */
public final class MemoryContextScrubber {

    private static final Pattern CONTEXT_BLOCK_RE = Pattern.compile(
            "<\\s*memory-context\\s*>[\\s\\S]*?</\\s*memory-context\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSTEM_NOTE_RE = Pattern.compile(
            "\\[System note:\\s*The following is recalled memory context,\\s*NOT new user input\\.\\s*"
            + "Treat as (?:informational background data|authoritative reference data[^\\]]*)\\.\\]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_TAG_RE = Pattern.compile(
            "</?\\s*memory-context\\s*>", Pattern.CASE_INSENSITIVE);

    private MemoryContextScrubber() {
    }

    /** 一次性净化：剥除围栏块、注入的系统注记行、孤立围栏标签。 */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String t = CONTEXT_BLOCK_RE.matcher(text).replaceAll("");
        t = SYSTEM_NOTE_RE.matcher(t).replaceAll("");
        t = FENCE_TAG_RE.matcher(t).replaceAll("");
        return t;
    }

    /**
     * 有状态流式净化器。每个文本分片调用 {@link #feed(String)}，流结束后
     * 调用 {@link #flush()} 取回暂存的尾缀（若为未闭合围栏则丢弃）。
     */
    public static final class StreamingScrubber {

        private static final String OPEN_TAG = "<memory-context>";
        private static final String CLOSE_TAG = "</memory-context>";

        private boolean inSpan = false;
        private boolean atBlockBoundary = true;
        private final StringBuilder buf = new StringBuilder();

        public void reset() {
            inSpan = false;
            atBlockBoundary = true;
            buf.setLength(0);
        }

        public String feed(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            String combined = buf.toString() + text;
            buf.setLength(0);
            String lower = combined.toLowerCase();
            StringBuilder out = new StringBuilder();
            int i = 0;
            int len = combined.length();
            while (i < len) {
                if (inSpan) {
                    int close = lower.indexOf(CLOSE_TAG, i);
                    if (close == -1) {
                        int hold = maxPartialSuffix(lower, i, CLOSE_TAG);
                        if (hold > 0) {
                            buf.append(combined, len - hold, len);
                        }
                        return out.toString(); // span 内容丢弃
                    }
                    i = close + CLOSE_TAG.length();
                    inSpan = false;
                } else {
                    int open = lower.indexOf(OPEN_TAG, i);
                    if (open == -1) {
                        int hold = maxPartialSuffix(lower, i, OPEN_TAG);
                        if (isCompleteOpenTagAtBoundary(lower, len)) {
                            hold = Math.max(hold, OPEN_TAG.length());
                        }
                        if (hold > 0) {
                            if (len - hold > i) {
                                appendVisible(out, combined, i, len - hold);
                            }
                            buf.append(combined, len - hold, len);
                        } else {
                            appendVisible(out, combined, i, len);
                        }
                        return out.toString();
                    }
                    if (isBlockBoundary(lower, open) && isBlockOpenerSuffix(combined, open)) {
                        // 块边界开标签 + 后随换行 → 进入 span
                        if (open > i) {
                            appendVisible(out, combined, i, open);
                        }
                        i = open + OPEN_TAG.length();
                        inSpan = true;
                    } else if (open + OPEN_TAG.length() >= len && isBlockBoundary(lower, open)) {
                        // 尾部完整的块边界开标签：暂存，等下一片确认后随字符
                        if (open > i) {
                            appendVisible(out, combined, i, open);
                        }
                        buf.append(combined, open, len);
                        return out.toString();
                    } else {
                        // 非块边界出现：作为普通文本输出（对齐 hermes 不触发 span）
                        appendVisible(out, combined, i, open + OPEN_TAG.length());
                        i = open + OPEN_TAG.length();
                    }
                }
            }
            if (inSpan) {
                buf.setLength(0); // 未闭合围栏：丢弃残留
            }
            return out.toString();
        }

        public String flush() {
            if (inSpan) {
                inSpan = false;
                buf.setLength(0);
                return "";
            }
            String tail = buf.toString();
            buf.setLength(0);
            return tail;
        }

        // ---- helpers ----

        private void appendVisible(StringBuilder out, String combined, int from, int to) {
            out.append(combined, from, to);
            updateBlockBoundary(combined.substring(from, to));
        }

        /** 开标签所在位置是否为块边界（行首或独立行，对齐 hermes _is_block_boundary） */
        private boolean isBlockBoundary(String lower, int idx) {
            if (idx == 0) {
                return atBlockBoundary;
            }
            int lastNewline = lower.lastIndexOf('\n', idx - 1);
            if (lastNewline == -1) {
                return atBlockBoundary && lower.substring(0, idx).trim().isEmpty();
            }
            return lower.substring(lastNewline + 1, idx).trim().isEmpty();
        }

        /** 开标签后是否紧跟换行（块级开始，对齐 hermes _has_block_opener_suffix） */
        private boolean isBlockOpenerSuffix(String combined, int idx) {
            int after = idx + OPEN_TAG.length();
            if (after >= combined.length()) {
                return false;
            }
            char c = combined.charAt(after);
            return c == '\n' || c == '\r';
        }

        /** combined 是否以完整的、位于块边界的开标签结尾 */
        private boolean isCompleteOpenTagAtBoundary(String lower, int len) {
            if (!lower.endsWith(OPEN_TAG)) {
                return false;
            }
            return isBlockBoundary(lower, len - OPEN_TAG.length());
        }

        private void updateBlockBoundary(String visible) {
            int lastNewline = visible.lastIndexOf('\n');
            if (lastNewline == -1) {
                atBlockBoundary = atBlockBoundary && visible.trim().isEmpty();
            } else {
                atBlockBoundary = visible.substring(lastNewline + 1).trim().isEmpty();
            }
        }

        private static int maxPartialSuffix(String lower, int from, String tag) {
            String tail = lower.substring(from);
            int max = Math.min(tail.length(), tag.length() - 1);
            for (int k = max; k >= 1; k--) {
                if (tag.startsWith(tail.substring(tail.length() - k))) {
                    return k;
                }
            }
            return 0;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryContextScrubberTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (5 tests).

> 若 `streamingScrubberDropsCompleteSpan` 断言 `out1 == "助手说："` 失败：完整围栏出现在同一片时被完整剥除，不影响其后的 `" 继续回复"`；请核对 feed 内循环对 `open == -1` 分支（该分片既有前文又有围栏时先走完整搜索）的处理。

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryContextScrubber.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryContextScrubberTest.java
git commit -m "feat(memory): 新增 MemoryContextScrubber 对齐 hermes 上下文围栏净化"
```

---

## Task 4: MemoryManager

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryManager.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryManagerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    /** 记录调用的桩 provider */
    static class RecordingProvider extends MemoryProviderTest.StubProvider {
        final List<String> calls = new ArrayList<>();
        private final String providerName;

        RecordingProvider(String name) {
            this.providerName = name;
        }
        @Override public String name() { return providerName; }
        @Override public String systemPromptBlock() { return "[block:" + providerName + "]"; }
        @Override public String prefetch(String query, String sessionId) {
            calls.add("prefetch:" + query);
            return "[recall:" + query + "]";
        }
        @Override public void syncTurn(String u, String a, String s, List<Map<String, Object>> m) {
            calls.add("sync:" + u);
        }
        @Override public void onSessionEnd(List<Map<String, Object>> messages) {
            calls.add("sessionEnd");
        }
    }

    private MemoryProperties props;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
    }

    @Test
    void builtinAlwaysAcceptedAndSecondExternalRejected() {
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(new RecordingProvider("builtin"));
        manager.addProvider(new RecordingProvider("external1"));
        manager.addProvider(new RecordingProvider("external2")); // 应被拒

        // 验证：prefetch 只命中 builtin + external1
        String ctx = manager.prefetchAll("q", "s");
        assertTrue(ctx.contains("[recall:q]"));
        // 无法直接数 provider 数 → 用 systemPromptBlock 个数验证只注册 2 个
        String sp = manager.buildSystemPrompt();
        assertTrue(sp.contains("builtin"));
        assertTrue(sp.contains("external1"));
        assertFalse(sp.contains("external2"));
    }

    @Test
    void nudgeAppearsAtInterval() {
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(new RecordingProvider("builtin"));
        for (int i = 0; i < 10; i++) {
            manager.syncAll("u" + i, "a", "s", null);
        }
        manager.drain();
        String sp = manager.buildSystemPrompt();
        assertTrue(sp.contains("Memory nudge"));
        // 未到间隔不出现
        MemoryManager fresh = new MemoryManager(props);
        fresh.addProvider(new RecordingProvider("builtin"));
        fresh.syncAll("u", "a", "s", null);
        fresh.drain();
        assertFalse(fresh.buildSystemPrompt().contains("Memory nudge"));
    }

    @Test
    void syncAllDelegatesAsyncAndDrainWaits() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.syncAll("你好", "你好呀", "s", null);
        manager.drain();
        assertTrue(p.calls.contains("sync:你好"));
        assertEquals(1, manager.getUserTurnCount());
    }

    @Test
    void syncAllPreservesTurnOrderAfterDrain() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.syncAll("u1", "a1", "s", null);
        manager.syncAll("u2", "a2", "s", null);
        manager.drain();
        assertEquals(List.of("sync:u1", "sync:u2"), p.calls);
    }

    @Test
    void prefetchAllDelegatesAndBuildsPrompt() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        String ctx = manager.prefetchAll("q", "s");
        assertEquals("[recall:q]", ctx);
        assertTrue(p.calls.contains("prefetch:q"));
    }

    @Test
    void onSessionEndDelegates() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.onSessionEnd(null);
        assertTrue(p.calls.contains("sessionEnd"));
    }

    @Test
    void disabledManagerDoesNothing() {
        props.setEnabled(false);
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.syncAll("u", "a", "s", null);
        manager.drain();
        assertFalse(p.calls.contains("sync:u"));
        assertEquals("", manager.prefetchAll("q", "s"));
    }
}
```

> 注：`RecordingProvider` 继承 `MemoryProviderTest.StubProvider`（包内可见）。`StubProvider.name()` 被覆写为 `providerName`，其余默认行为保留。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryManagerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`MemoryManager` not defined).

- [ ] **Step 3: Write the implementation**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆编排器 —— 对齐 hermes agent/memory_manager.py 的 MemoryManager。
 *
 * <p>约束：builtin provider 恒被接受；外部（非 builtin）provider 同一时刻只允许
 * 一个，重复注册打警告并拒绝（防 tool schema 膨胀与后端冲突）。</p>
 *
 * <p>同步策略：turn 后写入提交到单线程后台 executor，保证 turn N 先于 turn N+1
 * 落盘；{@link #drain()} 有 5 秒排水超时。</p>
 */
@Slf4j
public final class MemoryManager {

    public static final String BUILTIN_NAME = "builtin";
    private static final long DRAIN_TIMEOUT_SECONDS = 5;

    private final MemoryProperties props;
    private final List<MemoryProvider> providers = new ArrayList<>();
    private boolean hasExternal = false;
    private final AtomicInteger userTurnCount = new AtomicInteger(0);
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();

    public MemoryManager(MemoryProperties props) {
        this.props = props != null ? props : new MemoryProperties();
    }

    /** 注册 provider；builtin 恒接受，外部只许一个。 */
    public synchronized void addProvider(MemoryProvider provider) {
        if (provider == null) {
            return;
        }
        boolean isBuiltin = BUILTIN_NAME.equals(provider.name());
        if (!isBuiltin) {
            if (hasExternal) {
                log.warn("拒绝记忆 provider '{}' — 外部 provider '{}' 已注册。"
                        + "同一时刻只允许一个外部记忆 provider。",
                        provider.name(), firstExternalName());
                return;
            }
            hasExternal = true;
        }
        providers.add(provider);
    }

    /** 汇总所有 provider 的系统提示块 + nudge 提醒。 */
    public String buildSystemPrompt() {
        if (!props.isEnabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryProvider p : providers) {
            try {
                sb.append(p.systemPromptBlock());
            } catch (Exception e) {
                log.warn("记忆 provider '{}' systemPromptBlock 失败: {}", p.name(), e.getMessage());
            }
        }
        sb.append(buildNudge());
        return sb.toString();
    }

    /** nudge：累计轮次达到 nudge-interval 倍数时注入保存记忆提醒。 */
    String buildNudge() {
        int interval = props.getNudgeInterval();
        int turns = userTurnCount.get();
        if (interval <= 0 || turns <= 0 || turns % interval != 0) {
            return "";
        }
        return "\n[Memory nudge: 请考虑是否将本回合的重要信息保存到记忆。]\n";
    }

    /** turn 前召回：汇总各 provider 上下文，单个失败不阻塞其余。 */
    public String prefetchAll(String query, String sessionId) {
        if (!props.isEnabled()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (MemoryProvider p : providers) {
            try {
                String ctx = p.prefetch(query, sessionId);
                if (ctx != null) {
                    out.append(ctx);
                }
            } catch (Exception e) {
                log.warn("记忆 provider '{}' prefetch 失败: {}", p.name(), e.getMessage());
            }
        }
        return out.toString();
    }

    /** turn 后同步：计数 + 异步提交各 provider 写入。 */
    public void syncAll(String userMsg, String assistantResponse, String sessionId,
                        List<Map<String, Object>> messages) {
        if (!props.isEnabled()) {
            return;
        }
        userTurnCount.incrementAndGet();
        for (MemoryProvider p : providers) {
            try {
                syncExecutor.submit(() -> {
                    try {
                        p.syncTurn(userMsg, assistantResponse, sessionId, messages);
                    } catch (Exception e) {
                        log.warn("记忆 provider '{}' syncTurn 失败: {}", p.name(), e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // drain/shutdown 后提交被拒：降级为告警，不中断调用方（如 ChatService 的 turn 路径）
                log.warn("记忆 provider '{}' syncTurn 提交被拒（executor 已关闭）: {}",
                        p.name(), e.getMessage());
            }
        }
    }

    /** turn 后为下一轮入队后台召回。 */
    public void queuePrefetchAll(String query, String sessionId) {
        if (!props.isEnabled()) {
            return;
        }
        for (MemoryProvider p : providers) {
            try {
                p.queuePrefetch(query, sessionId);
            } catch (Exception e) {
                log.warn("记忆 provider '{}' queuePrefetch 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    /** 汇总所有 tool schema。 */
    public List<Map<String, Object>> getAllToolSchemas() {
        if (!props.isEnabled()) {
            return List.of();
        }
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (MemoryProvider p : providers) {
            try {
                schemas.addAll(p.getToolSchemas());
            } catch (Exception e) {
                log.warn("记忆 provider '{}' getToolSchemas 失败: {}", p.name(), e.getMessage());
            }
        }
        return schemas;
    }

    /** 会话结束回调。 */
    public void onSessionEnd(List<Map<String, Object>> messages) {
        if (!props.isEnabled()) {
            return;
        }
        for (MemoryProvider p : providers) {
            try {
                p.onSessionEnd(messages);
            } catch (Exception e) {
                log.warn("记忆 provider '{}' onSessionEnd 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    /** 会话切换回调。 */
    public void onSessionSwitch(String newSessionId, String parentSessionId, boolean reset) {
        if (!props.isEnabled()) {
            return;
        }
        for (MemoryProvider p : providers) {
            try {
                p.onSessionSwitch(newSessionId, parentSessionId, reset, false, Map.of());
            } catch (Exception e) {
                log.warn("记忆 provider '{}' onSessionSwitch 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    /** 已累计的用户轮次。 */
    public int getUserTurnCount() {
        return userTurnCount.get();
    }

    /** 等待所有后台写入完成（5 秒超时）。 */
    public void drain() {
        syncExecutor.shutdown();
        try {
            if (!syncExecutor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                syncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            syncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 干净退出：drain 后调用各 provider shutdown。 */
    public void shutdown() {
        drain();
        for (MemoryProvider p : providers) {
            try {
                p.shutdown();
            } catch (Exception e) {
                log.warn("记忆 provider '{}' shutdown 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    private String firstExternalName() {
        return providers.stream()
                .filter(p -> !BUILTIN_NAME.equals(p.name()))
                .map(MemoryProvider::name)
                .findFirst()
                .orElse("unknown");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryManagerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

> `RecordingProvider` 继承 `MemoryProviderTest.StubProvider`（同包），其 `handleToolCall` 等默认行为来自 SPI。若 `disabledManagerDoesNothing` 因 `props.setEnabled(false)` 后 `syncAll` 直接 return 而未计数，属预期。

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryManager.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryManagerTest.java
git commit -m "feat(memory): 新增 MemoryManager 编排器（单外部约束/nudge/异步同步）"
```

---

## Task 5: BuiltinMemoryProvider（核心验收）

**Files:**
- Create: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/FakeMemoryFacade.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/BuiltinMemoryProvider.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/BuiltinMemoryProviderTest.java`

- [ ] **Step 1: Write the failing test double `FakeMemoryFacade`**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 测试双：内存版 MemoryFacade，基于字符袋向量做真实余弦相似度检索。
 * 使核心验收测试（写 10 条 → 精确检索）无需 Postgres/pgvector 即可运行。
 */
class FakeMemoryFacade implements MemoryFacade {

    static final int DIM = 32;

    final Map<String, MemoryRecord> records = new LinkedHashMap<>();

    @Override
    public CompletableFuture<MemoryRecord> remember(String content, MemoryScope scope, StoreOptions options) {
        String id = UUID.randomUUID().toString();
        MemoryRecord r = MemoryRecord.builder()
                .id(id)
                .content(content)
                .embedding(embed(content))
                .scope(scope)
                .categories(List.of())
                .importance(0.5f)
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now())
                .accessCount(1)
                .isPrivate(scope != null && scope.isPrivate())
                .source("test")
                .build();
        records.put(id, r);
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public void rememberMany(List<MemoryEntry> entries, MemoryScope scope) {
        for (MemoryEntry e : entries) {
            remember(e.content(), scope, new StoreOptions());
        }
    }

    @Override
    public CompletableFuture<List<MemorySearchResult>> recall(String query, RecallOptions options) {
        return CompletableFuture.completedFuture(
                searchByVec(embed(query), options.scopes(), options.maxResults()));
    }

    @Override
    public List<MemorySearchResult> search(String query, MemoryScope scope, int maxResults) {
        return searchByVec(embed(query), List.of(scope), maxResults);
    }

    @Override
    public void drain() {
    }

    @Override
    public CompletableFuture<Void> clear(MemoryScope scope) {
        records.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public MemoryStats stats() {
        return new MemoryStats(records.size(), 0, 0.5);
    }

    private List<MemorySearchResult> searchByVec(float[] q, List<MemoryScope> scopes, int max) {
        List<MemorySearchResult> out = new ArrayList<>();
        for (MemoryRecord r : records.values()) {
            if (scopes != null && !scopes.isEmpty()
                    && scopes.stream().noneMatch(s -> s.contains(r.getScope()))) {
                continue;
            }
            out.add(new MemorySearchResult(r, cosine(q, r.getEmbedding())));
        }
        out.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return out.subList(0, Math.min(out.size(), max));
    }

    static float[] embed(String text) {
        float[] v = new float[DIM];
        for (char c : text.toLowerCase().toCharArray()) {
            v[Math.floorMod(c, DIM)] += 1.0f;
        }
        return v;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
```

- [ ] **Step 2: Write the failing test `BuiltinMemoryProviderTest`**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinMemoryProviderTest {

    private static final List<String> TOPICS = List.of(
            "数据库连接池", "定时任务", "消息队列", "缓存策略", "权限校验",
            "日志链路", "灰度发布", "配置中心", "分布式锁", "限流降级");

    private FakeMemoryFacade facade;
    private MemoryProperties props;
    private BuiltinMemoryProvider provider;

    @BeforeEach
    void setUp() {
        facade = new FakeMemoryFacade();
        props = new MemoryProperties();
        provider = new BuiltinMemoryProvider(facade, props);
    }

    @Test
    void nameAndAvailability() {
        assertEquals("builtin", provider.name());
        assertTrue(provider.isAvailable());
        assertEquals(List.of(), provider.getToolSchemas());
    }

    @Test
    void syncTurnPersistsContentThroughFacade() {
        provider.syncTurn("用户问题", "助手回答关于数据库连接池的内容", "s1", null);
        assertEquals(1, facade.records.size());
        assertTrue(facade.records.values().iterator().next().getContent().contains("数据库连接池"));
    }

    @Test
    void prefetchWrapsResultsInFencedBlock() {
        provider.syncTurn("用户问", "助手回答关于分布式锁的内容", "s1", null);
        provider.syncTurn("用户问", "助手回答关于缓存策略的内容", "s1", null);
        String block = provider.prefetch("分布式锁 怎么用", "s1");
        assertTrue(block.startsWith("<memory-context>"));
        assertTrue(block.endsWith("</memory-context>"));
        assertTrue(block.contains("[System note:"));
        assertTrue(block.contains("分布式锁"));
    }

    @Test
    void classifyScopeUsesUserKeywords() {
        MemoryScope agent = provider.classifyScope("环境变量 AETHER_HOME 指向配置文件");
        MemoryScope user = provider.classifyScope("我喜欢简洁的回答风格");
        assertEquals("agent", agent.path());
        assertTrue(user.path().startsWith("user"));
    }

    @Test
    void prefetchTruncatesByCharLimit() {
        // header 固定 ~206 字符；预算容纳第一条、裁掉第二条，验证"部分保留"分支
        props.setMemoryCharLimit(240);
        provider.syncTurn("用户问", "助手回答关于分布式锁的内容", "s1", null);
        provider.syncTurn("用户问", "助手回答关于缓存策略的内容", "s1", null);
        String block = provider.prefetch("分布式锁", "s1");
        assertTrue(block.endsWith("</memory-context>"));
        assertTrue(block.contains("System note"));
        // 第一条保留、第二条被裁 → 截断标记出现在闭合围栏之前
        assertTrue(block.contains("分布式锁"));
        assertTrue(block.contains("记忆已截断"));
        assertTrue(block.indexOf("记忆已截断") < block.indexOf("</memory-context>"));
    }

    @Test
    void truncatedBlockStillRoundTripsThroughSanitize() {
        props.setMemoryCharLimit(240);
        provider.syncTurn("用户问", "助手回答关于分布式锁的内容", "s1", null);
        provider.syncTurn("用户问", "助手回答关于缓存策略的内容", "s1", null);
        String block = provider.prefetch("分布式锁", "s1");
        // 截断后的块围栏结构完整，仍可被净化器完整剥除
        assertEquals("", MemoryContextScrubber.sanitize(block).trim());
    }

    @Test
    void assistantMentioningUserProfileKeywordStaysInAgentScope() {
        // 用户文本无画像关键词，仅助手回复含 "我是" → 仍归 agent 作用域（防误分类）
        provider.syncTurn("帮我看看这个配置", "我是这样实现的：设置超时时间", "s1", null);
        MemoryRecord r = facade.records.values().iterator().next();
        assertEquals("agent", r.getScope().path());
    }

    @Test
    void prefetchReturnsEmptyWhenFacadeNull() {
        BuiltinMemoryProvider bare = new BuiltinMemoryProvider(null, props);
        assertEquals("", bare.prefetch("q", "s"));
    }

    @Test
    void prefetchIncludesUserScopeWhenEnabled() {
        // 用户文本含画像关键词 → user 作用域；prefetch 应同时召回用户画像记忆
        provider.syncTurn("我喜欢简洁的回答风格", "好的，已记录", "s1", null);
        String block = provider.prefetch("回答风格", "s1");
        assertTrue(block.contains("我喜欢简洁的回答风格"));
    }

    @Test
    void formatMemoryBlockEmptyForNoResults() {
        assertEquals("", BuiltinMemoryProvider.formatMemoryBlock(List.of(), 100));
        assertEquals("", BuiltinMemoryProvider.formatMemoryBlock(null, 100));
    }

    /** 核心验收：写入 10 条不同主题记忆 → 精确检索 → 目标主题被召回且排位第一。 */
    @Test
    void writeTenThenPreciseRecall() {
        for (int i = 0; i < TOPICS.size(); i++) {
            provider.syncTurn("用户提问", "助手回答关于" + TOPICS.get(i) + "的配置说明", "s1", null);
        }
        assertEquals(10, facade.records.size());

        String queryTopic = TOPICS.get(4); // 权限校验
        String block = provider.prefetch(queryTopic + " 怎么配置", "s1");

        String target = queryTopic + "的配置说明";
        assertTrue(block.contains(target), "应召回目标记忆，实际: " + block);

        int pos1 = block.indexOf("记忆1: ");
        int pos2 = block.indexOf("记忆2: ");
        assertTrue(pos1 >= 0);
        String firstBlock = pos2 < 0 ? block.substring(pos1) : block.substring(pos1, pos2);
        assertTrue(firstBlock.contains(queryTopic),
                "目标主题应排位第一，实际第一条: " + firstBlock);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl aether-domain -am test -Dtest=BuiltinMemoryProviderTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`BuiltinMemoryProvider` not defined).

- [ ] **Step 4: Write the implementation `BuiltinMemoryProvider`**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 内置记忆 provider —— 对齐 hermes tools/memory_tool.py 的 MemoryStore + 生命周期语义。
 *
 * <p>委托现有 {@link MemoryFacade}（EncodingFlow/RecallFlow/VectorStore 全部复用）：</p>
 * <ul>
 *   <li>{@link #prefetch} → {@code facade.search(query, agentScope, topK)}，格式化
 *       {@code <memory-context>} 围栏 + 系统注记（对齐 hermes build_memory_context_block）；</li>
 *   <li>{@link #syncTurn} → {@code facade.remember(content, scope, options)}，写入走现有
 *       LLM 编码与相似度合并；</li>
 *   <li>scope 分类为确定性启发式：命中用户画像关键词 → user 作用域，否则 agent 作用域。</li>
 * </ul>
 */
@Slf4j
public class BuiltinMemoryProvider implements MemoryProvider {

    public static final String NAME = "builtin";

    private static final List<String> USER_PROFILE_KEYWORDS =
            List.of("我喜欢", "我偏好", "请记住", "我不喜欢");

    private final MemoryFacade facade;
    private final MemoryProperties props;

    public BuiltinMemoryProvider(MemoryFacade facade, MemoryProperties props) {
        this.facade = facade;
        this.props = props != null ? props : new MemoryProperties();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return facade != null;
    }

    @Override
    public void initialize(String sessionId, MemoryInitContext ctx) {
        // builtin 无连接需要预热；仅记录日志
        log.debug("builtin memory provider 初始化: sessionId={}", sessionId);
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of(); // builtin 不暴露工具（上下文注入即可）
    }

    @Override
    public String prefetch(String query, String sessionId) {
        if (facade == null || query == null || query.isBlank()) {
            return "";
        }
        int topK = props.getRecall().getMaxResults();
        try {
            // agent 事实/约定（memory-char-limit 预算）
            String agentBlock = formatMemoryBlock(
                    facade.search(query, MemoryScope.global().subscope("agent"), topK),
                    props.getMemoryCharLimit());
            // 用户画像（user-char-limit 预算，仅启用时；对齐 hermes MEMORY.md + USER.md 双注入）
            String userBlock = props.isUserProfileEnabled()
                    ? formatMemoryBlock(
                            facade.search(query, new MemoryScope("user", true), topK),
                            props.getUserCharLimit())
                    : "";
            return agentBlock + userBlock;
        } catch (Exception e) {
            log.warn("builtin prefetch 失败: query=[{}], error={}", query, e.getMessage());
            return "";
        }
    }

    @Override
    public void syncTurn(String userContent, String assistantContent,
                         String sessionId, List<Map<String, Object>> messages) {
        if (facade == null) {
            return;
        }
        String content = cleanText(userContent) + "\n\n" + cleanText(assistantContent);
        if (content.isBlank()) {
            return;
        }
        // scope 由用户文本判定，避免助手回复中的高频词（如 "我是"）误分类为画像记忆
        MemoryScope scope = classifyScope(cleanText(userContent));
        MemoryFacade.StoreOptions options =
                new MemoryFacade.StoreOptions(true, props.getRecall().getConsolidationThreshold());
        try {
            facade.remember(content, scope, options);
        } catch (Exception e) {
            log.warn("builtin syncTurn 写入失败: error={}", e.getMessage());
        }
    }

    /**
     * scope 分类 —— 确定性启发式（不依赖 LLM）。
     * 命中用户画像关键词 → user 作用域；否则 agent 作用域。
     */
    MemoryScope classifyScope(String content) {
        if (!props.isUserProfileEnabled()) {
            return MemoryScope.global().subscope("agent");
        }
        if (content != null
                && USER_PROFILE_KEYWORDS.stream().anyMatch(content::contains)) {
            return new MemoryScope("user", true);
        }
        return MemoryScope.global().subscope("agent");
    }

    /**
     * 对齐 hermes build_memory_context_block：围栏 + 系统注记 + 按 char-limit 裁减条目。
     * 始终保留完整围栏结构，确保 {@link MemoryContextScrubber#sanitize} 能完整剥除。
     */
    static String formatMemoryBlock(List<MemorySearchResult> results, int charLimit) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        String header = "<memory-context>\n"
                + "[System note: The following is recalled memory context, NOT new user input. "
                + "Treat as authoritative reference data — this is the agent's persistent "
                + "memory and should inform all responses.]\n\n";
        StringBuilder sb = new StringBuilder(header);
        boolean truncated = false;
        for (int i = 0; i < results.size(); i++) {
            String entry = "记忆" + (i + 1) + ": "
                    + results.get(i).getRecord().getContent() + "\n\n";
            if (sb.length() + entry.length() > charLimit) {
                truncated = true;
                break;
            }
            sb.append(entry);
        }
        sb.append("</memory-context>");
        if (truncated) {
            int insertPos = sb.length() - "</memory-context>".length();
            sb.insert(insertPos, "\n...(记忆已截断)");
        }
        return sb.toString();
    }

    /** 清洗文本：压缩空白后 trim。 */
    static String cleanText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl aether-domain -am test -Dtest=BuiltinMemoryProviderTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (9 tests，含核心验收 `writeTenThenPreciseRecall`).

> 若 `writeTenThenPreciseRecall` 断言失败：核对 `FakeMemoryFacade` 字符袋向量在 `DIM=32` 下对目标主题的区分度 —— 查询 "权限校验 怎么配置" 与记忆 "权限校验的配置说明" 共享 `权限校验` 字符，余弦应显著高于其他主题。若 TOPICS 中某两主题共享字符过多导致排位串扰，更换该主题词（仍保证 10 条可区分）。

- [ ] **Step 6: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/BuiltinMemoryProvider.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/BuiltinMemoryProviderTest.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/FakeMemoryFacade.java
git commit -m "feat(memory): 新增 BuiltinMemoryProvider 委托 MemoryFacade + 写10条精确检索验收"
```

---

## Task 6: MemoryLifecycleHooks

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryLifecycleHooks.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryLifecycleHooksTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLifecycleHooksTest {

    private MemoryProperties props;
    private FakeMemoryFacade facade;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        facade = new FakeMemoryFacade();
    }

    @Test
    void initBuildsManagerAndPrefetchWrites() {
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, facade);
        hooks.init();

        assertTrue(hooks.isEnabled());
        hooks.syncTurn("用户问", "助手回答关于数据库连接池的内容", "s1", null);
        hooks.drain();
        assertEquals(1, facade.records.size());

        String block = hooks.prefetch("数据库连接池", "s1");
        assertTrue(block.startsWith("<memory-context>"));
    }

    @Test
    void disabledDoesNothing() {
        props.setEnabled(false);
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, facade);
        hooks.init();

        assertFalse(hooks.isEnabled());
        hooks.syncTurn("u", "a", "s1", null);
        hooks.drain();
        assertEquals(0, facade.records.size());
        assertEquals("", hooks.prefetch("q", "s1"));
    }

    @Test
    void nullFacadeDoesNotBreak() {
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, null);
        hooks.init();
        hooks.syncTurn("u", "a", "s1", null);
        hooks.drain();
        assertEquals("", hooks.prefetch("q", "s1"));
        assertDoesNotThrow(hooks::shutdown);
    }

    @Test
    void onSessionEndRespectsFlushMinTurns() {
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, facade);
        hooks.init();
        props.setFlushMinTurns(6);

        // 轮次不足 → 不 flush（无写入动作发生，仅验证不抛错）
        hooks.onSessionEnd("s1", 3);
        // 轮次足够 → 正常触发
        assertDoesNotThrow(() -> hooks.onSessionEnd("s1", 6));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryLifecycleHooksTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`MemoryLifecycleHooks` not defined).

- [ ] **Step 3: Write the implementation**

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * ChatService 记忆生命周期门面 —— 对齐 hermes MemoryManager 在 turn 循环中的接入：
 * turn 前 {@link #prefetch}、turn 后 {@link #syncTurn}、会话结束 {@link #onSessionEnd}。
 *
 * <p>组装 {@link MemoryManager} + {@link BuiltinMemoryProvider}；builtin 委托现有
 * {@link MemoryFacade}。{@code aether.memory.enabled=false} 时不激活。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryLifecycleHooks {

    private final MemoryProperties props;
    private final MemoryFacade memoryFacade; // 可空：未配置向量库/EmbeddingModel 时不激活

    private volatile MemoryManager manager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MemoryLifecycleHooks(MemoryProperties props, MemoryFacade memoryFacade) {
        this.props = props != null ? props : new MemoryProperties();
        this.memoryFacade = memoryFacade;
    }

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("aether.memory.enabled=false，记忆系统未激活");
            return;
        }
        MemoryManager m = new MemoryManager(props);
        m.addProvider(new BuiltinMemoryProvider(memoryFacade, props));
        this.manager = m;
        log.info("MemoryLifecycleHooks 已初始化，provider=builtin, enabled={}", props.isEnabled());
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** turn 前召回：返回待注入的 {@code <memory-context>} 围栏文本（空串=无）。 */
    public String prefetch(String query, String sessionId) {
        MemoryManager m = manager;
        return m == null ? "" : m.prefetchAll(query, sessionId);
    }

    /** turn 后持久化：非阻塞异步写入。 */
    public void syncTurn(String userContent, String assistantContent,
                         String sessionId, List<Map<String, Object>> messages) {
        MemoryManager m = manager;
        if (m != null) {
            m.syncAll(userContent, assistantContent, sessionId, messages);
        }
    }

    /** 会话结束：轮次 ≥ flush-min-turns 时触发 onSessionEnd（对齐 hermes flush_min_turns）。 */
    public void onSessionEnd(String sessionId, int sessionTurnCount) {
        MemoryManager m = manager;
        if (m == null) {
            return;
        }
        int min = props.getFlushMinTurns();
        if (min <= 0 || sessionTurnCount >= min) {
            m.onSessionEnd(List.of());
        }
    }

    /** 等待所有后台写入完成。 */
    public void drain() {
        MemoryManager m = manager;
        if (m != null) {
            m.drain();
        }
    }

    /** 干净退出。 */
    public void shutdown() {
        MemoryManager m = manager;
        if (m != null) {
            m.shutdown();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryLifecycleHooksTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

> 说明：`init()` 用 `@PostConstruct` 使纯 JUnit 测试可手动调用；`@Configuration + @EnableConfigurationProperties` 供 Spring 装配（与 `AiAgentAutoConfig` 的注册方式一致）。`MemoryFacade` 经构造参数注入且 `required=false`（未配置向量库/EmbeddingModel 时 Spring 传 null），测试中以 `new MemoryLifecycleHooks(props, facade)` 直接传入。

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryLifecycleHooks.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryLifecycleHooksTest.java
git commit -m "feat(memory): 新增 MemoryLifecycleHooks 门面（prefetch/syncTurn/onSessionEnd）"
```

---

## Task 7: ChatService 集成

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java`

> 集成逻辑全部为委托调用，核心行为（围栏格式化、scope 分类、净化的流式状态机）已在 Task 3/5 单测覆盖。本任务验证标准：模块编译通过 + 现有 `ChatServiceTest` 全绿 + 依赖注入字段为空时行为回退正常。

- [ ] **Step 1: 新增依赖字段与 import，移除被取代的旧字段/import**

**移除**（`injectMemory` 的 MemoryFacade 语义搜索分支被 hooks 取代后，以下成为孤儿项）：

- 字段 `private MemoryFacade memoryFacade;`（L66-67，含其上注释）。
- import `cn.zcj.aether.domain.agent.service.memory.MemoryFacade;`（L25）、`...memory.MemoryScope;`（L26）、`...memory.MemorySearchResult;`（L27）。

> `MemoryStore` import 与回退分支保留。

**新增**：在 import 区（`import cn.zcj.aether.domain.agent.service.memory.MemoryStore;` 之后）加入：

```java
import cn.zcj.aether.domain.agent.service.memory.core.MemoryContextScrubber;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
```

在字段区（原 `private MemoryFacade memoryFacade;` 位置）加入：

```java
    /**
     * 记忆生命周期门面（prefetch/syncTurn/onSessionEnd）。
     * required=false：未配置 EmbeddingModel/向量库或 aether.memory.enabled=false 时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryLifecycleHooks memoryLifecycleHooks;
```

在类内常量区（`private final ObjectMapper objectMapper = new ObjectMapper();` 之后）加入：

```java
    /** 流式路径捕获助手文本的上限（防超长回复写入记忆） */
    private static final int MAX_SYNC_CAPTURE_CHARS = 8000;
```

- [ ] **Step 2: 改写 `injectMemory` 签名与 prefetch 分支**

将 `injectMemory(String instruction, String userMessage, String agentId)`（L567）改为 `injectMemory(String instruction, String userMessage, String agentId, String sessionId)`，并把"优先 MemoryFacade 语义搜索"分支替换为 hooks prefetch：

```java
    /**
     * 记忆注入：优先 MemoryLifecycleHooks.prefetch（内部走 BuiltinMemoryProvider → MemoryFacade），
     * 回退文件存储关键词匹配。
     */
    private String injectMemory(String instruction, String userMessage, String agentId, String sessionId) {
        // Phase 9: 注入标识符上下文（项目文件结构+文档索引）
        if (identifierRegistry != null) {
            try {
                String identifierCtx = identifierRegistry.buildIdentifierContext();
                if (instruction != null && !identifierCtx.isEmpty()) {
                    instruction = instruction + "\n\n" + identifierCtx;
                }
            } catch (Exception e) {
                log.debug("标识符上下文生成失败: {}", e.getMessage());
            }
        }

        // 新：MemoryLifecycleHooks prefetch（含 <memory-context> 围栏格式化与 char-limit 截断）
        if (memoryLifecycleHooks != null) {
            String memoryBlock = memoryLifecycleHooks.prefetch(userMessage, sessionId);
            if (memoryBlock != null && !memoryBlock.isEmpty()) {
                if (instruction == null) return memoryBlock;
                String enriched = instruction.replace("{memory}", memoryBlock);
                if (enriched.equals(instruction)) {
                    log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
                             "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryLifecycleHooks 路径）。", agentId);
                }
                return enriched;
            }
        }

        // 回退：文件存储关键词匹配（仅当 MemoryStore 可用时）
        if (memoryStore == null) return instruction;
        String memoryPrompt = memoryStore.loadMemoryPrompt(userMessage);
        if (memoryPrompt == null || memoryPrompt.isEmpty()) return instruction;
        if (instruction == null) return memoryPrompt;
        String enriched = instruction.replace("{memory}", memoryPrompt);
        if (enriched.equals(instruction)) {
            log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
                     "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryStore 回退路径）。", agentId);
        }
        return enriched;
    }
```

更新三处调用点，补 `sessionId` 实参：

- L234（`handleMessage`）：`String instruction = injectMemory(entry.getInstruction(), message, entry.getName(), sessionId);`
- L304（`handleMessageStream`）：`String instruction = injectMemory(entry.getInstruction(), message, entry.getName(), sessionId);`
- L374（`handleConfirm`）：`String instruction = injectMemory(entry.getInstruction(), "", entry.getName(), sessionId);`

> 删除原 `injectMemory` 中"优先 MemoryFacade 语义搜索"的整个 `if (memoryFacade != null) { ... }` 块（约 L580-603），由 hooks 路径取代。`MemoryFacade` 的调用职责已移入 `BuiltinMemoryProvider`（`MemoryLifecycleHooks` 内部注入），故 ChatService 不再直接持有该字段。

- [ ] **Step 3: 同步路径 turn 后 syncTurn**

在 `handleMessage`（L207）同步执行完成后加入。将：

```java
        List<String> outputs = new ArrayList<>();
        agent.execute(ctx)
                .blockingForEach(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        outputs.add(event.getText());
                    }
                });

        return outputs;
```

改为：

```java
        List<String> outputs = new ArrayList<>();
        agent.execute(ctx)
                .blockingForEach(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        outputs.add(event.getText());
                    }
                });

        // 记忆生命周期：turn 后持久化（异步，经净化防记忆回显递归污染）
        if (memoryLifecycleHooks != null && !outputs.isEmpty()) {
            String assistantText = MemoryContextScrubber.sanitize(String.join("", outputs));
            memoryLifecycleHooks.syncTurn(message, assistantText, sessionId, null);
        }

        return outputs;
```

- [ ] **Step 4: 流式路径捕获净化文本 + turn 后 syncTurn**

在 `handleMessageStream`（L281）末尾，将：

```java
        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, message, metadata, null);

        return agent.execute(ctx);
```

改为：

```java
        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, message, metadata, null);

        // 记忆生命周期：捕获助手文本（经流式净化防记忆回显递归污染）+ turn 后持久化
        // 注意：Flowable 为冷流，闭包捕获的 scrubber/captured 仅支持单次订阅；
        //       onError 终止时（模型失败/超时/取消）不触发 syncTurn，避免持久化残缺轮次。
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        StringBuilder captured = new StringBuilder();
        return agent.execute(ctx)
                .doOnNext(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        String clean = scrubber.feed(event.getText());
                        if (captured.length() < MAX_SYNC_CAPTURE_CHARS) {
                            captured.append(clean);
                        }
                    }
                })
                .doOnComplete(() -> {
                    captured.append(scrubber.flush());
                    if (memoryLifecycleHooks != null && captured.length() > 0) {
                        memoryLifecycleHooks.syncTurn(message, captured.toString(), sessionId, null);
                    }
                });
```

> 说明：`RuntimeEvent` 为不可变对象（仅 `@Getter`），无法改写已发出的 `textDelta`；故净化应用于**捕获路径**（防记忆回显再次写入记忆）。记忆注入在 instruction（系统提示）而非消息流中，回显到 UI 的风险本身较低。

- [ ] **Step 5: `deleteSession` 触发 onSessionEnd**

将 `deleteSession`（L186）改为：

```java
    @Override
    public void deleteSession(String sessionId) {
        if (sessionRepository == null) {
            log.warn("SessionRepository 未配置，无法删除会话: sessionId={}", sessionId);
            return;
        }

        // 记忆生命周期：会话结束 flush（轮次 ≥ flush-min-turns 才真正触发）
        int turnCount = readSessionTurnCount(sessionId);
        if (memoryLifecycleHooks != null) {
            memoryLifecycleHooks.onSessionEnd(sessionId, turnCount);
        }

        sessionRepository.deleteBySessionId(sessionId);
        log.info("会话已删除: sessionId={}", sessionId);
    }

    /** 从会话 stateJson 读取累计轮次（解析失败视为 0）。 */
    private int readSessionTurnCount(String sessionId) {
        try {
            var opt = sessionRepository.findBySessionId(sessionId);
            if (opt.isEmpty() || opt.get().getStateJson() == null) return 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> state = objectMapper.readValue(opt.get().getStateJson(), Map.class);
            return ((Number) state.getOrDefault("currentTurn", 0)).intValue();
        } catch (Exception e) {
            log.warn("读取会话轮次失败: sessionId={}, error={}", sessionId, e.getMessage());
            return 0;
        }
    }
```

- [ ] **Step 6: 编译 + 现有测试回归**

Run: `mvn -pl aether-domain -am test -Dtest=ChatServiceTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（ChatService 路由逻辑未被破坏）。

Run: `mvn -pl aether-domain -am compile`
Expected: BUILD SUCCESS（无编译错误）。

- [ ] **Step 7: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java
git commit -m "feat(chat): ChatService 接入记忆生命周期（prefetch/syncTurn/onSessionEnd）"
```

---

## Task 8: application.yml 配置

**Files:**
- Modify: `aether-app/src/main/resources/application.yml`

- [ ] **Step 1: 在 `aether:` 顶层下新增 `memory` 段**

将：

```yaml
# ====== P0: Security Configuration ======
aether:
  # SSRF 防护：生产环境必须为 false；开发环境在 application-dev.yml 中覆盖为 true
  ssrf:
    allow-private-urls: false
```

改为：

```yaml
# ====== P0: Security Configuration ======
aether:
  # SSRF 防护：生产环境必须为 false；开发环境在 application-dev.yml 中覆盖为 true
  ssrf:
    allow-private-urls: false
  # ====== 记忆系统（对齐 hermes cli-config.yaml memory 段）======
  memory:
    enabled: true                # 记忆系统总开关（hermes memory.memory_enabled）
    user-profile-enabled: true   # 用户画像记忆开关（hermes memory.user_profile_enabled）
    memory-char-limit: 2200      # 注入记忆上下文预算，≈800 token（hermes memory_char_limit）
    user-char-limit: 1375        # 用户画像上下文预算，≈500 token（hermes user_char_limit）
    nudge-interval: 10           # nudge 轮次间隔，0=禁用（hermes memory.nudge_interval）
    flush-min-turns: 6           # flush 最小轮次，0=禁用（hermes memory.flush_min_turns）
    recall:
      max-results: 10            # prefetch 检索 Top-K（hermes supermemory max_recall_results）
      semantic-weight: 0.6       # 语义相似度权重
      recency-weight: 0.3        # 时间衰减权重
      importance-weight: 0.1     # 重要性权重
      consolidation-threshold: 0.85  # 相似度合并阈值
      vector-dimension: 1280     # 向量维度（pgvector）
```

> 现有 `aether.memory.pgvector.enabled`（在 `application-dev.yml`）保留不动；Spring 会将 profile 配置与 base 配置合并。

- [ ] **Step 2: 验证配置绑定**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryPropertiesTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（默认值测试不受 yml 影响，作为绑定基线）。

> 完整绑定验证依赖 Spring 上下文启动，本计划以默认值测试 + 代码评审为准（与现有 `ai.agent.config` 的验证方式一致）。

- [ ] **Step 3: Commit**

```bash
git add aether-app/src/main/resources/application.yml
git commit -m "config(memory): application.yml 新增 aether.memory 配置对齐 hermes"
```

---

## Task 9: 全量回归 + 验收

**Files:**（无新增）

- [ ] **Step 1: 运行 memory.core 全部测试**

Run: `mvn -pl aether-domain -am test -Dtest='MemoryPropertiesTest,MemoryProviderTest,MemoryContextScrubberTest,MemoryManagerTest,BuiltinMemoryProviderTest,MemoryLifecycleHooksTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS，全部通过（含 `writeTenThenPreciseRecall` 核心验收）。

- [ ] **Step 2: 运行 aether-domain 全量测试回归**

Run: `mvn -pl aether-domain -am test`
Expected: BUILD SUCCESS（若存在与本次无关的既有失败用例，记录并在交付说明中注明，不擅自修复）。

- [ ] **Step 3: 全量编译（含 app 模块）**

Run: `mvn -pl aether-app -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 验收核对清单**

- [ ] 10 条记忆写入 → `prefetch` 精确召回且排位第一（`BuiltinMemoryProviderTest.writeTenThenPreciseRecall`）。
- [ ] 外部 provider 只许一个、builtin 恒接受（`MemoryManagerTest`）。
- [ ] 流式跨分片标签净化（`MemoryContextScrubberTest`）。
- [ ] 配置默认值与 hermes 完全一致（`MemoryPropertiesTest`）。
- [ ] ChatService 三处调用点签名更新、编译通过、既有路由测试全绿。

- [ ] **Step 5: Commit（如 Step 2 有遗留改动）**

```bash
git add -A
git commit -m "test(memory): 记忆系统对齐 hermes 全量回归验收"
```

---

## 自检记录

**Spec 覆盖：**
- §3.2 接口签名 → Task 2（MemoryProvider/MemoryInitContext）、Task 4（MemoryManager）。
- §3.3 数据流（prefetch/syncTurn/scope 分类/nudge/flush/净化）→ Task 3、5、6。
- §3.4 配置对齐表 → Task 1、8。
- §3.5 集成点矩阵 → Task 7（ChatService 四锚点）。
- §4 测试验收 → Task 5（核心验收）+ Task 9（全量回归）。
- §5 明确不做 → 本计划未实现外部 provider 插件、未改动现有 MemoryFacade/VectorStore。

**占位符扫描：** 全部步骤含完整代码与命令，无 TBD/TODO/“适当处理”。

**类型一致性：**
- `MemoryProperties.getRecall().getMaxResults()` 在 Task 5/6 与 Task 1 一致。
- `MemoryProvider.prefetch(String, String)` / `syncTurn(...)` 签名在 Task 2/4/5 一致。
- `BuiltinMemoryProvider.formatMemoryBlock(List<MemorySearchResult>, int)` 在 Task 5 测试与实现一致。
- `MemoryLifecycleHooks.onSessionEnd(String, int)` 与 ChatService 调用（`onSessionEnd(sessionId, turnCount)`）一致。

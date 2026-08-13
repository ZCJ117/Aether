# BackgroundReviewer I2/I3 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `BackgroundReviewer` 两条 code-review 发现——评审提示词纳入 goal（I2），模型调用加超时 + executor 队列有界（I3）。

**Architecture:** 全部改动集中在 `aether-domain` 的 `BackgroundReviewer.java` 与其测试。I2 改 `review()` 签名把 goal 拼入用户消息；I3 用有界 `ThreadPoolExecutor`（DiscardPolicy）替换无界单线程 executor，并把模型调用从阻塞 `callWithStream` 换成 `callWithStreamAsync(...).block(REVIEW_TIMEOUT)` 施加 reactor 超时。不改 `ModelInvoker`/`GraphExecutor`/`AgentEventPublisher`。

**Tech Stack:** Java 17、Spring AI、Reactor（Mono/Flux）、JUnit5、Mockito、ReflectionTestUtils。

**参考文档：** `docs/superpowers/specs/2026-08-13-aether-orchestration-background-review-i2-i3-fix-design.md`

**工作目录：** `D:\code\Agents-framework\aether`（分支 `feat/orchestration-hermes-alignment`）

**测试命令：** `mvn -pl aether-domain -am test -Dtest=BackgroundReviewerTest -Dsurefire.failIfNoSpecifiedTests=false`

---

### Task 1: I2 — review() 纳入 goal

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java`

本任务只改 I2：`review(finalOutput)` → `review(goal, finalOutput)`，把 goal 拼进用户消息；模型调用仍用现有 `callWithStream`（阻塞）。I3 在 Task 2 处理。

- [ ] **Step 1: 改 review() 签名与内容**

在 `BackgroundReviewer.java` 把当前 `review` 方法（约 L81-87）：

```java
    /** 对最终输出做模型评审；返回评审文本。 */
    String review(String finalOutput) {
        ModelInvoker.ModelCallResult r = modelInvoker.callWithStream(chatModel,
                List.of(new UserMessage(finalOutput)), systemPrompt, modelRef);
        return r.hasError() ? "[评审失败: " + r.getError() + "]"
                : (r.getFullText() == null ? "" : r.getFullText());
    }
```

替换为：

```java
    /** 对最终输出做模型评审；返回评审文本。goal 为 null/blank 时仅用 finalOutput。 */
    String review(String goal, String finalOutput) {
        String userContent = (goal == null || goal.isBlank())
                ? finalOutput
                : "任务目标:\n" + goal + "\n\n执行结果:\n" + finalOutput;
        ModelInvoker.ModelCallResult r = modelInvoker.callWithStream(chatModel,
                List.of(new UserMessage(userContent)), systemPrompt, modelRef);
        return r.hasError() ? "[评审失败: " + r.getError() + "]"
                : (r.getFullText() == null ? "" : r.getFullText());
    }
```

- [ ] **Step 2: submit() 改调 review(goal, finalOutput)**

在 `submit` 方法内（约 L69），把：

```java
            String review = review(finalOutput);
```

替换为：

```java
            String review = review(goal, finalOutput);
```

- [ ] **Step 3: 更新既有测试的 review() 调用点**

在 `BackgroundReviewerTest.java` 中，`reviewProducesReviewTextFromModelCall`（约 L26）把：

```java
        assertEquals("评审通过", reviewer.review("final output"));
```

替换为：

```java
        assertEquals("评审通过", reviewer.review("目标A", "final output"));
```

- [ ] **Step 4: 新增 I2 测试（goal + finalOutput 都在提示词里）**

在 `BackgroundReviewerTest.java` 增加 import 与测试方法。先在文件顶部 import 区（现有 `import org.mockito.ArgumentMatchers.any;` 之后）加：

```java
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import java.util.List;
```

再在类内追加：

```java
    @Test
    void reviewIncludesGoalAndFinalOutputInPrompt() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.callWithStream(any(), any(), any(), any()))
                .thenReturn(ModelInvoker.ModelCallResult.builder().fullText("评审通过").build());

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");

        reviewer.review("写一份报告", "报告正文");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelInvoker).callWithStream(any(), captor.capture(), any(), any());
        String text = captor.getValue().get(0).getText();
        assertTrue(text.contains("写一份报告"), "提示词应含 goal");
        assertTrue(text.contains("报告正文"), "提示词应含 finalOutput");
    }

    @Test
    void reviewBlankGoalUsesOnlyFinalOutput() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.callWithStream(any(), any(), any(), any()))
                .thenReturn(ModelInvoker.ModelCallResult.builder().fullText("评审通过").build());

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");

        reviewer.review("   ", "报告正文");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelInvoker).callWithStream(any(), captor.capture(), any(), any());
        String text = captor.getValue().get(0).getText();
        assertEquals("报告正文", text, "goal 为 blank 时提示词只应含 finalOutput");
    }
```

- [ ] **Step 5: 跑测试验证全绿**

Run: `mvn -pl aether-domain -am test -Dtest=BackgroundReviewerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 个测试：原 2 个 + 新增 2 个，`submitAsyncRunsReviewRecordsEventAndPublishes` 不变仍通过）

- [ ] **Step 6: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java
git commit -m "fix(d4): BackgroundReviewer review 纳入 goal 拼入评审提示词（code-review I2）"
```

---

### Task 2: I3 — 有界队列 + 模型调用超时

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java`

本任务改 I3 两部分：① executor 无界队列 → 有界 + DiscardPolicy；② review() 的模型调用从 `callWithStream` 换 `callWithStreamAsync(...).block(reviewTimeout)` 施加超时。为此引入 `reviewTimeout`/`queueCapacity` 两个字段 + 包私有 8 参测试构造器。

- [ ] **Step 1: 调整 import**

在 `BackgroundReviewer.java` 顶部 import 区做三处变更：
- 移除 `import java.util.concurrent.Executors;`（不再使用）
- 在 `import java.time.Instant;` 前加 `import java.time.Duration;`
- 在 `import java.util.concurrent.ExecutorService;` 后加三行：

```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
```

（即最终并发相关 import 为 `ArrayBlockingQueue`、`ExecutorService`、`ThreadPoolExecutor`、`TimeUnit`，无 `Executors`。）

- [ ] **Step 2: 加常量 + 字段**

在 `BackgroundReviewer` 类字段区（`private final ExecutorService executor;` 之后）加：

```java
    private final ExecutorService executor;

    private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(60);
    private static final int REVIEW_QUEUE_CAPACITY = 16;
    private final Duration reviewTimeout;
```

- [ ] **Step 3: 改造构造器（public 6 参委托 + 包私有 8 参）**

把当前构造器（约 L39-57）整体替换为：

```java
    @Autowired
    public BackgroundReviewer(GraphExecutionRecorder recorder,
                              AgentEventPublisher publisher,
                              ModelInvoker modelInvoker,
                              ChatModel chatModel,
                              @Value("${aether.graph.background-review.model-ref:gpt-4o}") String modelRef,
                              @Value("${aether.graph.background-review.system-prompt:你是资深评审。请对给定 Agent 执行结果做质量评审，200 字内。}") String systemPrompt) {
        this(recorder, publisher, modelInvoker, chatModel, modelRef, systemPrompt,
                REVIEW_TIMEOUT, REVIEW_QUEUE_CAPACITY);
    }

    /** 包私有测试构造器：可注入短超时/小容量（对齐 GraphExecutionRecorder(int retention) 可测性模式）。 */
    BackgroundReviewer(GraphExecutionRecorder recorder,
                       AgentEventPublisher publisher,
                       ModelInvoker modelInvoker,
                       ChatModel chatModel,
                       String modelRef,
                       String systemPrompt,
                       Duration reviewTimeout,
                       int queueCapacity) {
        this.recorder = recorder;
        this.publisher = publisher;
        this.modelInvoker = modelInvoker;
        this.chatModel = chatModel;
        this.modelRef = modelRef;
        this.systemPrompt = systemPrompt;
        this.reviewTimeout = reviewTimeout;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "background-review");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }
```

- [ ] **Step 4: review() 改用 callWithStreamAsync + block(reviewTimeout)**

把 Task 1 改出的 `review` 方法整体替换为：

```java
    /** 对最终输出做模型评审；返回评审文本。goal 为 null/blank 时仅用 finalOutput。超时/错误统一转占位。 */
    String review(String goal, String finalOutput) {
        String userContent = (goal == null || goal.isBlank())
                ? finalOutput
                : "任务目标:\n" + goal + "\n\n执行结果:\n" + finalOutput;
        try {
            ModelInvoker.ModelCallResult r = modelInvoker.callWithStreamAsync(chatModel,
                    List.of(new UserMessage(userContent)), systemPrompt, modelRef)
                    .block(reviewTimeout);
            if (r == null) {
                return "[评审失败: 无结果]";
            }
            return r.hasError() ? "[评审失败: " + r.getError() + "]"
                    : (r.getFullText() == null ? "" : r.getFullText());
        } catch (Exception e) {
            return "[评审失败: " + e.getMessage() + "]";
        }
    }
```

- [ ] **Step 5: 迁移既有测试的 mock（callWithStream → callWithStreamAsync）**

`review()` 现在走 `callWithStreamAsync`，三个既有测试里凡是 `callWithStream(...)` 的 mock/verify 都要改。具体：

(a) `reviewProducesReviewTextFromModelCall`（约 L21）把：

```java
        when(modelInvoker.callWithStream(any(), any(), any(), any())).thenReturn(result);
```

替换为：

```java
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(result));
```

(b) `reviewIncludesGoalAndFinalOutputInPrompt` 与 `reviewBlankGoalUsesOnlyFinalOutput` 中把 `callWithStream` 两处（when 与 verify）都改为 `callWithStreamAsync`，且 `thenReturn(...)` 换成 `thenReturn(reactor.core.publisher.Mono.just(...))`。

(c) `submitWithBlankOutputIsIgnored`（约 L39）把：

```java
        verify(modelInvoker, never()).callWithStream(any(), any(), any(), any());
```

替换为：

```java
        verify(modelInvoker, never()).callWithStreamAsync(any(), any(), any(), any());
```

(d) `submitAsyncRunsReviewRecordsEventAndPublishes`（约 L49）把：

```java
        when(modelInvoker.callWithStream(any(), any(), any(), any())).thenReturn(result);
```

替换为：

```java
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(result));
```

- [ ] **Step 6: 新增 I3 超时测试**

在 `BackgroundReviewerTest.java` import 区加：

```java
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import org.springframework.test.util.ReflectionTestUtils;
```

再在类内追加：

```java
    @Test
    void reviewReturnsErrorPlaceholderOnTimeout() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = new ModelInvoker(); // 真实 ModelInvoker 走真实 block(timeout)
        org.springframework.ai.chat.model.ChatModel chatModel =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, chatModel,
                "gpt-4o", "你是一名评审。", Duration.ofMillis(100), 4);

        long start = System.currentTimeMillis();
        String r = reviewer.review("goal", "output");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(r.startsWith("[评审失败"), "超时应返回错误占位，实际: " + r);
        assertTrue(elapsed < 5000, "超时应快速返回而非挂死，耗时: " + elapsed + "ms");
        reviewer.shutdown();
    }
```

- [ ] **Step 7: 新增 I3 有界队列测试**

在 `BackgroundReviewerTest.java` 类内追加：

```java
    @Test
    void executorQueueIsBounded() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。", Duration.ofSeconds(30), 4);

        try {
            ExecutorService exec = (ExecutorService) ReflectionTestUtils.getField(reviewer, "executor");
            assertTrue(exec instanceof ThreadPoolExecutor, "executor 应为 ThreadPoolExecutor");
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) exec;
            assertTrue(tpe.getQueue() instanceof ArrayBlockingQueue, "队列必须是有界的 ArrayBlockingQueue");
            assertEquals(4, tpe.getQueue().remainingCapacity() + tpe.getQueue().size(), "队列容量应为 4");
        } finally {
            reviewer.shutdown();
        }
    }
```

- [ ] **Step 8: 跑测试验证全绿**

Run: `mvn -pl aether-domain -am test -Dtest=BackgroundReviewerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（7 个测试：原 3 + Task1 新增 2 + 本任务新增 2）

- [ ] **Step 9: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java
git commit -m "fix(d4): BackgroundReviewer 有界队列 + 模型调用超时（callWithStreamAsync.block，code-review I3）"
```

---

## 完成校验

- domain 模块 `BackgroundReviewerTest` 全绿；`hermes-agent-main/` 零修改。
- 无需改 `application.yml`（超时/队列为硬编码常量，与设计一致）。

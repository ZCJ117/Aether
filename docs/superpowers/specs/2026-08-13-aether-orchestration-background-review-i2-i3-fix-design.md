# BackgroundReviewer I2/I3 修复设计（D4 后续）

日期：2026-08-13
分支：`feat/orchestration-hermes-alignment`
范围：`aether-domain` 单模块，仅 `BackgroundReviewer.java` + 新增测试

## 背景

Batch 4（D4 可视化调试）已交付 `BackgroundReviewer`（默认关闭的后台图执行评审）。最终 code review（commit `a0ce46c`）给出两条 Important，本设计承接修复：

- **I2**：`review()` 只用 `finalOutput` 构造提示词，`goal` 只进了发布事件，模型看不到评审目标。
- **I3**：模型调用无超时 + 单线程 executor 无界队列。模型挂起则唯一评审线程永久阻塞；队列无界则内存可无界增长。

## 目标

1. 评审提示词纳入 `goal`（目标）与 `finalOutput`（最终输出）。
2. 评审模型调用有超时；executor 队列有界 + 满载丢弃（best-effort 语义）。
3. 不改动共享代码 `ModelInvoker`、`GraphExecutor`、`AgentEventPublisher`（手术式修改）。

## 设计

### I2 — goal 纳入评审提示词

`review(String finalOutput)` → `review(String goal, String finalOutput)`，构造用户消息：

```java
String userContent = (goal == null || goal.isBlank())
        ? finalOutput
        : "任务目标:\n" + goal + "\n\n执行结果:\n" + finalOutput;
```

`submit()` 改调 `review(goal, finalOutput)`。调用点已就位：`GraphExecutor.executeGraphFlow` 成功尾已传 `initialMessage` 作 goal，无需改 GraphExecutor。

### I3 — 有界队列 + 超时

**2a. 队列上限**：`Executors.newSingleThreadExecutor`（内部无界 `LinkedBlockingQueue`）→
`ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, new ArrayBlockingQueue<>(16), daemon 线程工厂, new DiscardPolicy())`。满载时丢弃新评审，不抛异常、不阻塞调用方。

**2b. 超时**：不改 `ModelInvoker`。复用其既有 `callWithStreamAsync(...)`（返回 `Mono<ModelCallResult>`，单次无重试），在 `review()` 内 `.block(REVIEW_TIMEOUT)` 施加 reactor 级超时：

```java
ModelCallResult r = modelInvoker.callWithStreamAsync(chatModel, messages, systemPrompt, modelRef)
        .block(REVIEW_TIMEOUT);   // 超时/错误抛异常 → 转错误占位
```

- 单次尝试：后台评审 best-effort，重试挂死模型无意义；超时即释放唯一线程。
- 从 `callWithStream`（阻塞重试、异常封装进 `result.error`）换为 `callWithStreamAsync(...).block()` 后，异常改为**抛出**，故 `review()` 包 try/catch 转 `"[评审失败: ...]"`，并守卫 `r == null`。

### 默认值

- `REVIEW_TIMEOUT = Duration.ofSeconds(60)`，`REVIEW_QUEUE_CAPACITY = 16`，作为私有常量硬编码（不配置化，遵循 CLAUDE.md「不添加未要求的可配置性」）。
- 新增包私有 8 参测试构造器（追加 `reviewTimeout`、`queueCapacity`），public `@Autowired` 6 参构造器委托之（沿用 `GraphExecutionRecorder(int retention)` 既有可测性模式）。

## 错误处理

- `submit()`：保留现有 null/blank 短路与 `executor.isShutdown()` 守卫；满载时 DiscardPolicy 静默丢弃（best-effort 契约）。
- `review()`：超时/模型错误统一转为错误占位文本；`@PreDestroy shutdownNow` 中断阻塞线程（daemon，不阻 JVM 退出）。

## 测试（JUnit5 + Mockito，domain 模块）

1. **I2 goal-in-prompt**：`ArgumentCaptor` 捕获 messages，断言单条 UserMessage 含 goal + finalOutput；blank goal → 仅 finalOutput。
2. **I3 超时**：mock `ChatModel.stream()` → `Flux.never()`，注入 100ms 超时，断言 `review()` 快速返回错误占位（非挂死）。
3. **I3 队列上限**：注入 `queueCapacity=1`，用 `CountDownLatch` 占住唯一线程并填满队列，再 submit 第 3 任务，断言其被丢弃（`AtomicBoolean` 不翻转）。

## 非目标（明确不做）

- 不把 goal 之外的「逐节点中间输出」纳入提示词（评审上下文仅 goal + 最终输出，已与用户确认）。
- 不改 `LoggingHook` 的裸 ObjectMapper 问题（Batch 4 后续项④，独立项）。
- 不做 ModelInvoker 的 Duration 重载（已改用更简单的 `callWithStreamAsync(...).block(timeout)`）。
- 不动 AgentTracer span 接线（Batch 4 后续项①，独立项）。

## 验收

- domain 模块相关测试全绿（`mvn -pl aether-domain -am test -Dtest=BackgroundReviewerTest -Dsurefire.failIfNoSpecifiedTests=false`）。
- `hermes-agent-main/` 零修改。

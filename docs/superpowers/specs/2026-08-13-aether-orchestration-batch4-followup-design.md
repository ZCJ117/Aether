# Batch 4 剩余后续项设计（D4 可视化调试收尾）

日期：2026-08-13
分支：`feat/orchestration-hermes-alignment`
范围：`aether-domain` 单模块，4 个生产文件 + 对应测试，`hermes-agent-main/` 零修改

## 背景

Batch 4（D4 可视化调试）已交付 GraphExecutionRecorder/AgentTracer/AgentEventPublisher/BackgroundReviewer 等。最终 code review 与后续 code review 记录了 6 条剩余后续项，本设计承接一次性收尾：

- **①** AgentTracer 图级 span 死 API——`startGraphExecution`/`startGraphNode`/`endGraphNode` 已添加+测试但从未被生产代码调用（OTel span 不触发）。
- **②** AgentEventPublisher MDC 富化线程级不一致——parallelPool / 裸 `new Thread` 节点线程无 `graphExecutionId` MDC，事件富化只覆盖同步路径。
- **③** 非 graphflow 图注册空 trace——`beginExecution` 无条件调用，仅 catch 分支 `endExecution`，成功路径不关。
- **④** `LoggingHook` 裸 ObjectMapper 无 JavaTimeModule——Instant 序列化抛异常回退 toString（与 AgentEventPublisher 曾修过的同款 bug）。
- **⑤** `SubagentLifecycleService.launch` taskId 计算 `Integer.toHexString(...).substring(0,6)` 对短 task 抛 StringIndexOutOfBounds。
- **⑥** `ExecutionControlService.listActiveSubAgents` 对每个 id 调两次 `taskOf`（Minor）。

## 目标

1. ① 接线 graph.execute + graph.node span（属性关联，不做跨线程 parent-child）。
2. ② 让工作线程（parallel/graphflow）也能读到 `graphExecutionId` MDC 富化。
3. ③ 非 graphflow 图不再注册空 trace（仅 graphflow 才 begin）。
4. ④⑤⑥ 三个独立 bug 修复。
5. 手术式修改：不改 `ModelInvoker`/`AgentEventPublisher`/`AgentTracer` 的公共签名。

## 设计

### ④ LoggingHook 裸 ObjectMapper

`LoggingHook.java:23` 的 `MAPPER` 对齐 `AgentEventPublisher.java:20-22`：

```java
private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

新增 import：`com.fasterxml.jackson.databind.SerializationFeature`、`com.fasterxml.jackson.datatype.jsr310.JavaTimeModule`。

### ⑤ launch taskId 越界

`SubagentLifecycleService.java:85` 改为：

```java
String hex = Integer.toHexString(Math.abs(task.task().hashCode()));
String taskId = "t" + hex.substring(0, Math.min(6, hex.length()));
```

根因：`Integer.toHexString` 对小 hashCode 返回 < 6 字符（如 `""` → hashCode=0 → `"0"`，`"a"` → hashCode=97 → `"61"`），`substring(0,6)` 越界。`Math.min` 封顶即修复。
`task.task()==null` 导致的 NPE 是既有上游契约，**不新增 null 守卫**（YAGNI）。

### ⑥ listActiveSubAgents 调两次 taskOf

`ExecutionControlService.listActiveSubAgents` 缓存一次 `taskOf`：

```java
for (String id : lifecycle.activeIds()) {
    String status = lifecycle.status(id).map(Object::toString).orElse("?");
    Optional<DelegationTask> task = lifecycle.taskOf(id);
    String sessionId = task.map(t -> t.parentSessionId()).orElse(null);
    String goal = task.map(t -> t.task()).orElse(null);
    views.add(new ActiveSubAgentView(id, status, sessionId, goal, null));
}
```

新增 import：`java.util.Optional`（`DelegationTask` 同包无需 import）。

### ③ 非 graphflow 空 trace

`GraphExecutor.execute()` 在 `beginExecution` 前预判 graphflow：

```java
boolean isGraphFlow = edges.stream().anyMatch(AgentEdge::isGraphFlow);
if (graphExecutionRecorder != null && isGraphFlow) {
    graphExecutionId = graphExecutionRecorder.beginExecution(sessionId);
    MDC.put("graphExecutionId", graphExecutionId);
    MDC.put("sessionId", sessionId);
}
```

`recordNodeEvent` 只在 `executeGraphFlow` 调用，故非 graphflow 建 trace 只能是空壳；改为仅 graphflow 才 begin，与 recorder「只服务 graphflow 回放」一致。后续 ① 的 span 也 gate 在同一 `graphExecutionId != null` 上，同生共死。

### ① AgentTracer 接线（属性关联，无 parent-child）

**graph.execute span**（`execute()`，仅 `graphExecutionId != null` 时）：

```java
AgentTracer.SpanScope graphSpan = graphExecutionId != null
        ? AgentTracer.startGraphExecution(graphExecutionId, sessionId) : null;
try {
    ... 既有 body ...
} catch (Exception e) {
    if (graphSpan != null) {
        graphSpan.span().setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
    }
    ... 既有错误处理（endExecution + RuntimeEvent.error）...
} finally {
    if (graphSpan != null) {
        graphSpan.close();  // scope.close() + span.end()；错误路径 status 已先置 ERROR
    }
    ... 既有 MDC restore ...
}
```

**graph.node span**（`executeGraphFlow`，镜像 recorder 4 态；`nodeSpan` 主线程 start、工作线程 end，OTel Span 对象可跨线程 end）：

- RUNNING（主线程）：`Span nodeSpan = AgentTracer.startGraphNode(graphExecutionId, flowState.getNodeDef().getAgentType(), name)`
- SKIPPED（主线程）：`AgentTracer.endGraphNode(nodeSpan, true, null)`（跳过非错误，OK）
- COMPLETED（工作线程）：`AgentTracer.endGraphNode(nodeSpan, true, null)`
- FAILED（工作线程）：`AgentTracer.endGraphNode(nodeSpan, false, e.getMessage())`

`nodeSpan` 声明在 `new Thread` 之前、SKIPPED 分支与 lambda 共用（effectively final）。

### ② MDC 线程传播

在 `executeParallel`（`parallelPool.submit`）与 `executeGraphFlow`（`new Thread`）提交点捕获：

```java
Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
```

工作线程 Runnable 首行 `if (mdcCtx != null) MDC.setContextMap(mdcCtx);`，`finally` 里 `MDC.clear()`。

`graphExecutionId`（主线程 `execute()` set）随上下文传入节点线程，`ReActAgent.publishTurnStarted` 等在工作线程调 `toJsonWithMdc` 时读到富化。与既有 `execute()` finally 恢复 MDC 同模式。

## 错误处理

- ① graph.execute span 失败路径先置 ERROR 再 close；node span 的 SKIPPED 视为 OK（非错误）。
- ② MDC 传播 `finally` 清理，无泄漏。
- ③ 非 graphflow 无 trace，故无「未关 trace」残留。

## 测试（JUnit5 + Mockito，domain 模块）

1. **④ LoggingHook**：反射取 `MAPPER`，断言 `writeValueAsString(Instant.now())` 产出 ISO 字符串（非时间戳数组）——复现「Instant 回退 toString」。
2. **⑤ taskId**：构造短 `task()`（如 `""`）的 DelegationTask，断言 `launch()` 不抛、taskId 非空。
3. **⑥ taskOf 单次**：`verify(lifecycle, times(1)).taskOf(id)`。
4. **③ 非 graphflow 空 trace**：非 graphflow（单入口或 SEQUENTIAL）图执行后 recorder 无 trace；graphflow 仍录（现有 `GraphExecutorTraceTest` 已覆盖）。
5. **① span 接线**：smoke——graphflow 图接线后正常执行不抛、recorder 仍录节点事件（不引入 `opentelemetry-sdk-testing`，span 无 SDK 时为 no-op）。
6. **② MDC 传播**：graphflow 图执行中，工作线程内断言 `MDC.get("graphExecutionId")` 非空。

## 非目标（明确不做）

- 不做 OTel 跨线程 parent-child 传播（① 已选属性关联）。
- 不给非 graphflow 补节点录制（③ 已选 graphflow-only）。
- 不引入 `opentelemetry-sdk-testing`。
- 不动 `ModelInvoker`/`AgentEventPublisher`/`AgentTracer` 公共签名。
- 不处理 `task.task()==null` 的既有 NPE 面。

## 验收

- domain 模块相关测试全绿（`mvn -pl aether-domain -am test -Dsurefire.failIfNoSpecifiedTests=false` 或指定测试类）。
- `hermes-agent-main/` 零修改。

# Batch 4 剩余后续项 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收尾 Batch 4（D4 可视化调试）6 条剩余后续项——④ LoggingHook ObjectMapper、⑤ launch taskId 越界、⑥ taskOf 调两次、③ 非 graphflow 空 trace、① AgentTracer span 接线、② MDC 线程传播。

**Architecture:** 全部改动在 `aether-domain`，4 个生产文件 + 4 个测试文件。④⑤⑥ 是独立 1-2 行修复；③①② 集中在 `GraphExecutor.java`，主题为「graph 级可观测性只服务 graphflow + 跨线程上下文传播」。不改 `ModelInvoker`/`AgentEventPublisher`/`AgentTracer` 公共签名，`hermes-agent-main/` 零修改。

**Tech Stack:** Java 17、Spring AI、RxJava3（Flowable）、OpenTelemetry API、SLF4J MDC、Jackson（JavaTimeModule）、JUnit5、Mockito、ReflectionTestUtils。

**参考文档：** `docs/superpowers/specs/2026-08-13-aether-orchestration-batch4-followup-design.md`

**工作目录：** `D:\code\Agents-framework\aether`（分支 `feat/orchestration-hermes-alignment`）

**测试命令（单类）：** `mvn -pl aether-domain -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`
**测试命令（多类）：** `mvn -pl aether-domain -am test -Dtest=LoggingHookTest,SubagentLifecycleServiceTest,ExecutionControlServiceTest,GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false`

---

## 文件结构

| 文件 | 责任 | 变更 |
|------|------|------|
| `aether-domain/.../agent/hook/LoggingHook.java` | 生命周期结构化日志钩子 | ④ MAPPER 加 JavaTimeModule |
| `aether-domain/.../subagent/SubagentLifecycleService.java` | 子Agent 生命周期状态机 | ⑤ taskId substring 封顶 |
| `aether-domain/.../subagent/ExecutionControlService.java` | 编排实时控制面 | ⑥ taskOf 调一次 |
| `aether-domain/.../executor/GraphExecutor.java` | 多Agent图执行器 | ③ begin 门控 + ① span 接线 + ② MDC 传播 |
| `aether-domain/src/test/.../agent/hook/LoggingHookTest.java` | ④ 新测试 | 新建 |
| `aether-domain/src/test/.../subagent/SubagentLifecycleServiceTest.java` | ⑤ 新测试 | 追加 |
| `aether-domain/src/test/.../subagent/ExecutionControlServiceTest.java` | ⑥ 新测试 | 追加 |
| `aether-domain/src/test/.../executor/GraphExecutorTraceTest.java` | ③② 新测试 + helper | 追加 |

---

### Task 1: ④ LoggingHook 裸 ObjectMapper 加 JavaTimeModule

**Files:**
- Create: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/hook/LoggingHookTest.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/hook/LoggingHook.java:7,23`

- [ ] **Step 1: 写失败测试**

新建 `LoggingHookTest.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LoggingHookTest {

    @Test
    void mapperSerializesInstantAsIsoString() {
        ObjectMapper mapper = (ObjectMapper) ReflectionTestUtils.getField(LoggingHook.class, "MAPPER");
        assertNotNull(mapper, "MAPPER 静态字段应存在");

        // 裸 ObjectMapper（无 JavaTimeModule）对 Instant 序列化会抛 InvalidDefinitionException
        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(Instant.now()));
        assertTrue(json.startsWith("\""), "Instant 应序列化为带引号的 ISO 字符串，实际: " + json);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=LoggingHookTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`assertDoesNotThrow` 捕获 `InvalidDefinitionException`，报告 `Java 8 date/time type java.time.Instant not supported by default`）

- [ ] **Step 3: 修 MAPPER**

在 `LoggingHook.java` 顶部 import 区，`import com.fasterxml.jackson.databind.ObjectMapper;`（L7）之后加两行：

```java
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
```

把 `LoggingHook.java:23`：

```java
    private static final ObjectMapper MAPPER = new ObjectMapper();
```

替换为：

```java
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=LoggingHookTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/hook/LoggingHook.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/hook/LoggingHookTest.java
git commit -m "fix(d4): LoggingHook 注册 JavaTimeModule 修复 Instant 序列化回退（后续项④）"
```

---

### Task 2: ⑤ launch taskId substring 越界

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java:85`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleServiceTest.java`

- [ ] **Step 1: 写失败测试**

在 `SubagentLifecycleServiceTest.java` 类内追加（`textEvent`/`task` helper 与 mock 已存在，`Agent`/`RuntimeContext`/`Flowable`/`List`/`assertDoesNotThrow` 均已 import）：

```java
    @Test
    void launchWithShortTaskDoesNotThrow() {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.just(textEvent("x")));

        // task="" 的 hashCode=0 → Integer.toHexString(0)="0" → substring(0,6) 越界
        assertDoesNotThrow(() -> service.launch("ad-short",
                new DelegationTask("", List.of(), null, "u1", "s1", null)));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=SubagentLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`StringIndexOutOfBoundsException: String index out of range: 6`）

- [ ] **Step 3: 修 taskId 计算**

把 `SubagentLifecycleService.java:85`：

```java
        String taskId = "t" + Integer.toHexString(Math.abs(task.task().hashCode())).substring(0, 6);
```

替换为：

```java
        String hex = Integer.toHexString(Math.abs(task.task().hashCode()));
        String taskId = "t" + hex.substring(0, Math.min(6, hex.length()));
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=SubagentLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（8 个测试：原 7 + 新增 1）

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleServiceTest.java
git commit -m "fix(d4): launch taskId substring 越界封顶（后续项⑤）"
```

---

### Task 3: ⑥ listActiveSubAgents 调两次 taskOf

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlService.java:36-39`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlServiceTest.java`

- [ ] **Step 1: 写失败测试**

在 `ExecutionControlServiceTest.java` 类内追加（`verify`/`times`/`Optional`/`List`/`SubagentState`/`DelegationTask` 均已可用）：

```java
    @Test
    void listActiveSubAgentsCallsTaskOfOncePerId() {
        when(lifecycle.activeIds()).thenReturn(List.of("ad-1"));
        when(lifecycle.status("ad-1")).thenReturn(Optional.of(SubagentState.RUNNING));
        when(lifecycle.taskOf("ad-1")).thenReturn(Optional.of(
                new DelegationTask("t1", List.of(), null, "u1", "s1", null)));

        service().listActiveSubAgents();

        verify(lifecycle, times(1)).taskOf("ad-1");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=ExecutionControlServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`taskOf("ad-1")` 实际调用 2 次，`times(1)` 断言失败）

- [ ] **Step 3: 缓存 taskOf 结果**

把 `ExecutionControlService.java:36-39`：

```java
        for (String id : lifecycle.activeIds()) {
            String status = lifecycle.status(id).map(Object::toString).orElse("?");
            String sessionId = lifecycle.taskOf(id).map(t -> t.parentSessionId()).orElse(null);
            String goal = lifecycle.taskOf(id).map(t -> t.task()).orElse(null);
            views.add(new ActiveSubAgentView(id, status, sessionId, goal, null));
        }
```

替换为：

```java
        for (String id : lifecycle.activeIds()) {
            String status = lifecycle.status(id).map(Object::toString).orElse("?");
            Optional<DelegationTask> task = lifecycle.taskOf(id);
            String sessionId = task.map(t -> t.parentSessionId()).orElse(null);
            String goal = task.map(t -> t.task()).orElse(null);
            views.add(new ActiveSubAgentView(id, status, sessionId, goal, null));
        }
```

在 `ExecutionControlService.java` 顶部 import 区加一行（`import java.util.List;` 之后）：

```java
import java.util.Optional;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=ExecutionControlServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（6 个测试：原 5 + 新增 1）

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlService.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlServiceTest.java
git commit -m "fix(d4): listActiveSubAgents 缓存 taskOf 结果，每 id 只调一次（后续项⑥）"
```

---

### Task 4: ③ 非 graphflow 图注册空 trace

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java:111-115`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorTraceTest.java`

- [ ] **Step 1: 写失败测试**

在 `GraphExecutorTraceTest.java` 类内追加（`mock`/`verify`/`never`/`any`/`AgentEdgeType`/`AgentEdge`/`AgentNodeDef`/`AgentGraph`/`LinkedHashMap`/`List` 均已 import）。先加一个非 graphflow 图构造 helper（放 `graphflowGraph()` 之后）：

```java
    private AgentGraph sequentialGraph() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("n1", AgentNodeDef.builder().name("n1").instruction("i1")
                .outputKey("o1").agentType("researcher").build());
        List<AgentEdge> edges = List.of(AgentEdge.builder()
                .workflowName("wf").type(AgentEdgeType.SEQUENTIAL).subAgents(List.of("n1")).build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(edges).build();
    }
```

再加两个测试方法：

```java
    @Test
    void beginExecutionOnlyForGraphflow() throws Exception {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        GraphExecutor executor = buildExecutor(recorder);

        executor.execute(sequentialGraph(), "u1", "s1", "hi").blockingSubscribe();

        verify(recorder, never()).beginExecution(any());
    }

    @Test
    void beginExecutionCalledForGraphflow() throws Exception {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        when(recorder.beginExecution(any())).thenReturn("gx-test");
        GraphExecutor executor = buildExecutor(recorder);

        executor.execute(graphflowGraph(), "u1", "s1", "hi").blockingSubscribe();

        verify(recorder).beginExecution(any());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `beginExecutionOnlyForGraphflow` FAIL（非 graphflow 仍调用了 `beginExecution`，`never()` 断言失败）

- [ ] **Step 3: 门控 beginExecution**

把 `GraphExecutor.java:111-115`：

```java
                if (graphExecutionRecorder != null) {
                    graphExecutionId = graphExecutionRecorder.beginExecution(sessionId);
                    MDC.put("graphExecutionId", graphExecutionId);
                    MDC.put("sessionId", sessionId);
                }
```

替换为：

```java
                boolean isGraphFlow = edges.stream().anyMatch(AgentEdge::isGraphFlow);
                if (graphExecutionRecorder != null && isGraphFlow) {
                    graphExecutionId = graphExecutionRecorder.beginExecution(sessionId);
                    MDC.put("graphExecutionId", graphExecutionId);
                    MDC.put("sessionId", sessionId);
                }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（原 3 + 新增 2 = 5 个测试）

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorTraceTest.java
git commit -m "fix(d4): 仅 graphflow 才 beginExecution，避免非 graphflow 空 trace（后续项③）"
```

---

### Task 5: ① AgentTracer span 接线

> 说明：AgentTracer 用 `GlobalOpenTelemetry`，无 OTel SDK 时 span 为 no-op，故本任务无独立新测试——验证方式是「graphflow 图接线后正常执行不抛、recorder 仍录节点事件」，即既有 `GraphExecutorTraceTest` 全绿（smoke）。

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java`

- [ ] **Step 1: 加 import**

在 `GraphExecutor.java` 顶部 import 区，`import cn.zcj.aether.domain.agent.service.agent.observability.BackgroundReviewer;`（L17）之后加：

```java
import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
```

在 `import lombok.extern.slf4j.Slf4j;`（L25）之前加两行：

```java
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
```

- [ ] **Step 2: graph.execute span 包裹 execute()**

在 `GraphExecutor.execute()` 的 `Flowable.create(emitter -> {` 内，`String prevSessionId = MDC.get("sessionId");`（L105）之后加一行声明：

```java
            AgentTracer.SpanScope graphSpan = null;
```

在 Task 4 改出的 begin 门控块内（`MDC.put("sessionId", sessionId);` 之后）加一行：

```java
                    graphSpan = AgentTracer.startGraphExecution(graphExecutionId, sessionId);
```

在 catch 块内，`log.error("GraphExecutor error", e);`（L151）之后加：

```java
                if (graphSpan != null) {
                    graphSpan.span().setStatus(StatusCode.ERROR, e.getMessage());
                }
```

在 finally 块内，`if (graphExecutionId != null) {`（L162）之前加：

```java
                if (graphSpan != null) {
                    graphSpan.close();
                }
```

- [ ] **Step 3: graph.node span 包裹 graphflow 节点**

在 `executeGraphFlow` 节点循环内，`AgentNodeDef def = flowState.getNodeDef();`（L675）之后加 nodeSpan 声明：

```java
                Span nodeSpan = (graphExecutionId != null)
                        ? AgentTracer.startGraphNode(graphExecutionId, def.getAgentType(), name) : null;
```

在 SKIPPED 分支（`recordNodeEvent(SKIPPED, ...)` 之后、`batchLatch.countDown();` 之前，L694 附近）加：

```java
                    if (nodeSpan != null) {
                        AgentTracer.endGraphNode(nodeSpan, true, null);
                    }
```

在 `new Thread` 工作线程的 COMPLETED 记录（`recordNodeEvent(COMPLETED, ...)` 之后，L729 附近）加：

```java
                        if (nodeSpan != null) {
                            AgentTracer.endGraphNode(nodeSpan, true, null);
                        }
```

在 FAILED 记录（`recordNodeEvent(FAILED, ...)` 之后，L776 附近）加：

```java
                        if (nodeSpan != null) {
                            AgentTracer.endGraphNode(nodeSpan, false, e.getMessage());
                        }
```

- [ ] **Step 4: 跑 smoke 测试确认无回归**

Run: `mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 个测试全绿，graphflow 执行无异常、recorder 仍录节点事件）

- [ ] **Step 5: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java
git commit -m "feat(d4): AgentTracer 接线 graph.execute + graph.node span（属性关联，后续项①）"
```

---

### Task 6: ② MDC 线程传播

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorTraceTest.java`

- [ ] **Step 1: 抽 helper 并写失败测试**

先把 `GraphExecutorTraceTest.java` 的 `buildExecutor`（L30-49）重构为委托到新 helper（保留原 `buildExecutor` 签名不变，供既有测试用）：

```java
    private GraphExecutor buildExecutor(GraphExecutionRecorder recorder) throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(RuntimeEvent.text("node output")));
        return buildExecutorWithAgent(recorder, agent);
    }

    private GraphExecutor buildExecutorWithAgent(GraphExecutionRecorder recorder, Agent agent) throws Exception {
        GraphExecutor executor = new GraphExecutor();
        ReflectionTestUtils.setField(executor, "graphExecutionRecorder", recorder);

        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        when(factory.create(any(AgentConfig.class))).thenReturn(agent);
        ReflectionTestUtils.setField(executor, "agentFactory", factory);

        ConditionEvaluator cond = mock(ConditionEvaluator.class);
        when(cond.evaluate(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(executor, "conditionEvaluator", cond);

        ReflectionTestUtils.setField(executor, "subAgentOrchestrator", null);
        ReflectionTestUtils.setField(executor, "hookRegistry", null);
        ReflectionTestUtils.setField(executor, "interventionHandler", null);
        return executor;
    }
```

再加失败测试（`MDC`/`AtomicReference`/`Agent`/`RuntimeContext`/`Flowable`/`RuntimeEvent`/`mock`/`when`/`any` 均已 import）：

```java
    @Test
    void graphflowWorkerThreadInheritsGraphExecutionIdMdc() throws Exception {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        Agent agent = mock(Agent.class);
        AtomicReference<String> workerMdc = new AtomicReference<>();
        when(agent.execute(any(RuntimeContext.class)))
                .thenAnswer(inv -> {
                    workerMdc.set(MDC.get("graphExecutionId"));
                    return Flowable.just(RuntimeEvent.text("node output"));
                });
        GraphExecutor executor = buildExecutorWithAgent(recorder, agent);

        executor.execute(graphflowGraph(), "u1", "s1", "hi").blockingSubscribe();

        assertNotNull(workerMdc.get(), "graphflow 节点工作线程应继承 graphExecutionId MDC");
        assertNull(MDC.get("graphExecutionId"), "执行结束后主线程 MDC 应清理");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `graphflowWorkerThreadInheritsGraphExecutionIdMdc` FAIL（`workerMdc.get()` 为 null）

- [ ] **Step 3: executeParallel 传播 MDC**

在 `executeParallel` 内，`List<ExecutionState> subStates = new CopyOnWriteArrayList<>();`（L237）之后加捕获：

```java
        Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
```

在 `parallelPool.submit(() -> {`（L274）的 lambda 首行（`try {` 之前）加：

```java
                    if (mdcCtx != null) {
                        MDC.setContextMap(mdcCtx);
                    }
```

在 lambda 的 `finally { latch.countDown(); }`（L315-317）改为：

```java
                } finally {
                    MDC.clear();
                    latch.countDown();
                }
```

- [ ] **Step 4: executeGraphFlow 传播 MDC**

在 `executeGraphFlow` 内，`Map<String, String> nodeOutputs = new ConcurrentHashMap<>();`（L646）之后加捕获：

```java
        Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
```

在 `new Thread(() -> {`（L699）的 lambda 首行（`try {` 之前）加：

```java
                        if (mdcCtx != null) {
                            MDC.setContextMap(mdcCtx);
                        }
```

在 lambda 的 `finally { batchLatch.countDown(); }`（L780-782）改为：

```java
                    } finally {
                        MDC.clear();
                        batchLatch.countDown();
                    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（6 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorTraceTest.java
git commit -m "fix(d4): 图节点工作线程传播 graphExecutionId MDC（后续项②）"
```

---

## 完成校验

- 多类测试全绿：

```bash
mvn -pl aether-domain -am test -Dtest=LoggingHookTest,SubagentLifecycleServiceTest,ExecutionControlServiceTest,GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false
```

- 全量 domain 测试回归：

```bash
mvn -pl aether-domain -am test -Dsurefire.failIfNoSpecifiedTests=false
```

- `hermes-agent-main/` 零修改（本计划未触碰该目录）。

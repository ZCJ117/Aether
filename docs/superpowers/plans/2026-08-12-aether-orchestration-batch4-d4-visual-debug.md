# Batch 4（D4 可视化调试）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 D4 可视化调试——每子Agent 直播日志、图级 trace 录制与回放、实时控制 HTTP 端点、MDC 结构化关联、可选后台自评审，并承接 Batch 3 遗留项（detectStale 调度、runtimes 清理）。

**Architecture:** 方案 A 分层 + 端口模式。`DelegationLiveLog` 为 domain 端口 + infrastructure 文件实现（沿用 `AsyncDelegationStore`/`PgAsyncDelegationStore` 惯例，`SubagentLifecycleService` 经 `ObjectProvider` 可选注入）；`GraphExecutionRecorder` 落 domain observability；`ExecutionControlService` 落 domain + aether-trigger 新控制器；`StaleDelegationScanner` 用自建 `ScheduledExecutorService`（代码库无 `@EnableScheduling`）。所有组件 best-effort：失败绝不影响 agent 主循环（对齐 hermes "Never raise into the agent loop"）。

**Tech Stack:** Java 17 + Spring Boot（@Component/@Service/@Value/@ConditionalOnProperty）+ RxJava3 Flowable + JUnit5 + Mockito + OpenTelemetry（AgentTracer）。

**对齐参照（只读，不可修改）：** `hermes-agent-main/tools/delegation_live_log.py`、`agent/moa_trace.py`、`tools/delegate_tool.py`（TUI 控制面）、`tools/async_delegation.py`（list_async_delegations）。

**设计文档：** `docs/superpowers/specs/2026-08-12-aether-orchestration-batch4-d4-visual-debug-design.md`

---

## 文件结构

**新建（8 个实现 + 8 个测试）：**
- `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationLiveLog.java` — 直播日志端口（接口）
- `aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/deleg/FileDelegationLiveLog.java` — 文件实现
- `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/GraphExecutionRecorder.java` — 图级 trace 录制
- `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java` — 后台自评审（默认关）
- `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlService.java` — 控制面服务
- `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/StaleDelegationScanner.java` — stale 调度器
- `aether-trigger/src/main/java/cn/zcj/aether/trigger/http/OrchestrationController.java` — HTTP 控制端点
- 测试：`FileDelegationLiveLogTest` / `GraphExecutionRecorderTest` / `GraphExecutorTraceTest` / `AgentTracerGraphSpanTest` / `AgentEventPublisherMdcTest` / `ExecutionControlServiceTest` / `OrchestrationControllerTest` / `StaleDelegationScannerTest` / `BackgroundReviewerTest` / `SubagentLifecycleLiveLogIntegrationTest` / `SubagentLifecycleRetentionTest`

**修改：**
- `aether-domain/.../subagent/SubagentLifecycleService.java` — 直播日志接线（Task 2）+ runtimes 终态保留（Task 10）
- `aether-domain/.../executor/GraphExecutor.java` — recorder 接线 + MDC（Task 4）
- `aether-domain/.../observability/AgentTracer.java` — 图级 span（Task 5）
- `aether-domain/.../event/AgentEventPublisher.java` — MDC 富化 + 评审事件（Task 6/11）
- `aether-trigger/pom.xml` — 添加 test 依赖（Task 8）
- `aether-app/src/main/resources/application.yml` — 配置文档化（Task 12）

**测试命令（模块根目录 `D:/code/Agents-framework/aether` 下执行）：**
```bash
# domain 单测
mvn -pl aether-domain -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false
# infrastructure 单测
mvn -pl aether-infrastructure -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false
# trigger 单测
mvn -pl aether-trigger -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false
```

---

### Task 1: `DelegationLiveLog` 端口 + `FileDelegationLiveLog` 文件实现

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationLiveLog.java`
- Create: `aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/deleg/FileDelegationLiveLog.java`
- Test: `aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/deleg/FileDelegationLiveLogTest.java`

- [ ] **Step 1: 写失败测试**

创建 `FileDelegationLiveLogTest.java`：

```java
package cn.zcj.aether.infrastructure.deleg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileDelegationLiveLogTest {

    @TempDir
    Path tempDir;

    private FileDelegationLiveLog newLog() {
        return new FileDelegationLiveLog(tempDir);
    }

    @Test
    void appendThenReadBackContainsHeaderAndLines() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "do the thing");
        log.append("deleg_abc", "assistant", "hello world");
        log.append("deleg_abc", "result", "ok");
        log.close("deleg_abc", "done");

        List<String> lines = log.tail("deleg_abc", 100);
        assertTrue(lines.stream().anyMatch(l -> l.contains("do the thing")), "header 应含 goal");
        assertTrue(lines.stream().anyMatch(l -> l.contains("hello world")), "应含 assistant 行");
        assertTrue(lines.stream().anyMatch(l -> l.contains("done")), "应含终态摘要行");
    }

    @Test
    void tailReturnsLastNInOrder() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "g");
        for (int i = 0; i < 10; i++) {
            log.append("deleg_abc", "assistant", "line-" + i);
        }
        List<String> tail = log.tail("deleg_abc", 3);
        assertEquals(3, tail.size());
        assertTrue(tail.get(tail.size() - 1).contains("line-9"));
    }

    @Test
    void directoriesAreIsolatedPerDelegation() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_a", "goal A");
        log.append("deleg_a", "assistant", "A-line");
        log.open("deleg_b", "goal B");
        log.append("deleg_b", "assistant", "B-line");

        assertTrue(log.tail("deleg_a", 10).stream().anyMatch(l -> l.contains("A-line")));
        assertFalse(log.tail("deleg_a", 10).stream().anyMatch(l -> l.contains("B-line")));
        assertTrue(log.tail("deleg_b", 10).stream().anyMatch(l -> l.contains("B-line")));
    }

    @Test
    void writerDegradesOnOpenFailureAndNeverRaises() throws Exception {
        Path blocker = tempDir.resolve("blocker.txt");
        Files.writeString(blocker, "x");
        FileDelegationLiveLog log = new FileDelegationLiveLog(blocker.resolve("sub").toString());
        assertDoesNotThrow(() -> {
            log.open("deleg_x", "g");
            log.append("deleg_x", "assistant", "line");
        });
        assertTrue(log.tail("deleg_x", 10).isEmpty(), "写入失败后应降级为空");
    }

    @Test
    void closeIsIdempotent() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "g");
        log.append("deleg_abc", "assistant", "one");
        log.close("deleg_abc", "final1");
        log.close("deleg_abc", "final2");
        long finals = log.tail("deleg_abc", 100).stream().filter(l -> l.contains("final1")).count();
        assertEquals(1, finals, "close 应幂等，不重复追加终态行");
    }

    @Test
    void pruneRemovesOldDirectories() throws Exception {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_old", "old");
        log.close("deleg_old", "x");
        Path oldDir = tempDir.resolve("deleg_old");
        long past = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000;
        Files.setLastModifiedTime(oldDir, FileTime.fromMillis(past));

        int removed = log.prune(7);
        assertEquals(1, removed);
        assertFalse(Files.exists(oldDir));
    }

    @Test
    void longLineIsTruncated() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "g");
        String longLine = "x".repeat(1000);
        log.append("deleg_abc", "assistant", longLine);
        List<String> lines = log.tail("deleg_abc", 10);
        assertTrue(lines.stream().anyMatch(l -> l.contains("(+")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-infrastructure -am test -Dtest=FileDelegationLiveLogTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：找不到 `DelegationLiveLog` / `FileDelegationLiveLog`）。

- [ ] **Step 3: 写端口接口**

创建 `DelegationLiveLog.java`：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import java.util.List;

/**
 * 每子Agent直播日志端口 — 对齐 hermes delegation_live_log.py LiveTranscriptWriter。
 * <p>实现位于 infrastructure（FileDelegationLiveLog）；SubagentLifecycleService 经
 * ObjectProvider 可选注入，无 Bean 时所有调用 no-op。</p>
 * <p>纪律（实现必须遵守）：写失败绝不上抛进 agent 循环；首次失败即禁用该 writer，
 * 降级 debug log；close 幂等。</p>
 */
public interface DelegationLiveLog {

    /** 打开一个子Agent直播日志（写 header：id / goal / started）。 */
    void open(String delegationId, String goal);

    /** 追加一行（实现加时间戳前缀 + 单行折叠/截断）。 */
    void append(String delegationId, String role, String line);

    /** 冲刷缓冲（append-mode 实现可为 no-op 语义占位）。 */
    void flush(String delegationId);

    /** 写终态摘要并关闭（幂等）。 */
    void close(String delegationId, String summary);

    /** 读取末 n 行。 */
    List<String> tail(String delegationId, int n);
}
```

- [ ] **Step 4: 写文件实现**

创建 `FileDelegationLiveLog.java`：

```java
package cn.zcj.aether.infrastructure.deleg;

import cn.zcj.aether.domain.agent.service.subagent.DelegationLiveLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每子Agent直播日志文件实现 — 对齐 hermes delegation_live_log.py LiveTranscriptWriter。
 * <p>纪律（逐条对齐 hermes）：① append-mode 每次写入即落盘，无长句柄（子Agent崩溃不丢行）；
 * ② 任何写失败首次触发即禁用该 writer（内部 ok map），降级 debug log，绝不上抛；
 * ③ 单行折叠 + 截断（assistant 600 / 其他 400）；④ open 时机会性 prune 超 7 天目录；
 * ⑤ close 幂等。</p>
 * <p>文件布局：<base>/<delegationId>/task-0.log（base 默认 ./cache/delegation/live）。</p>
 */
@Slf4j
@Component
public class FileDelegationLiveLog implements DelegationLiveLog {

    public static final int LIVE_RETENTION_DAYS = 7;
    static final int ASSISTANT_MAX = 600;
    static final int RESULT_MAX = 400;
    static final int KICKOFF_MAX = 500;

    private final Path baseDir;
    /** delegationId -> 该 writer 是否仍可用（首错即 false）。 */
    private final Map<String, Boolean> ok = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    public FileDelegationLiveLog(
            @Value("${aether.delegation.live-log-dir:./cache/delegation/live}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    /** 测试注入显式路径。 */
    public FileDelegationLiveLog(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    private Path file(String delegationId) {
        return baseDir.resolve(delegationId).resolve("task-0.log");
    }

    private boolean enabled(String delegationId) {
        return ok.getOrDefault(delegationId, true);
    }

    @Override
    public void open(String delegationId, String goal) {
        try {
            pruneStaleLives();
            Path dir = baseDir.resolve(delegationId);
            Files.createDirectories(dir);
            List<String> header = new ArrayList<>();
            header.add("=== Aether subagent live transcript ===");
            header.add("delegation: " + delegationId + "   task: 0");
            header.add("goal: " + oneLine(goal, KICKOFF_MAX));
            header.add("started: " + Instant.now());
            header.add("(append-only; streams while the subagent runs - tail -f me)");
            header.add("====");
            Files.write(file(delegationId),
                    (String.join("\n", header) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ok.put(delegationId, true);
            closed.remove(delegationId);
        } catch (Exception e) {
            ok.put(delegationId, false);
            log.debug("FileDelegationLiveLog: open 失败 delegation={}", delegationId, e);
        }
    }

    @Override
    public void append(String delegationId, String role, String line) {
        if (!enabled(delegationId)) {
            return;
        }
        int limit = "assistant".equals(role) ? ASSISTANT_MAX : RESULT_MAX;
        String text = "[" + LocalTime.now().withNano(0) + "] " + role + " | "
                + oneLine(line, limit) + "\n";
        try {
            Files.write(file(delegationId), text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Exception e) {
            ok.put(delegationId, false);
            log.debug("FileDelegationLiveLog: append 失败 path={}", file(delegationId), e);
        }
    }

    @Override
    public void flush(String delegationId) {
        // append-mode 已同步落盘；flush 为语义占位
    }

    @Override
    public void close(String delegationId, String summary) {
        if (!closed.add(delegationId)) {
            return; // 幂等：只写一次终态行
        }
        append(delegationId, "final", summary);
    }

    @Override
    public List<String> tail(String delegationId, int n) {
        try {
            Path f = file(delegationId);
            if (!Files.exists(f)) {
                return List.of();
            }
            List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - n);
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (IOException e) {
            log.debug("FileDelegationLiveLog: tail 失败 delegation={}", delegationId, e);
            return List.of();
        }
    }

    /** 机会性清理超过保留窗口的 delegation 目录；返回清理数（best-effort）。 */
    public int pruneStaleLives() {
        return prune(LIVE_RETENTION_DAYS);
    }

    int prune(int maxAgeDays) {
        int removed = 0;
        if (!Files.isDirectory(baseDir)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeDays));
        try (var stream = Files.list(baseDir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                try {
                    if (Files.isDirectory(child) && Files.getLastModifiedTime(child).toInstant().isBefore(cutoff)) {
                        deleteRecursively(child);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // best-effort：单个目录清理失败不影响其他
                }
            }
        } catch (IOException e) {
            log.debug("FileDelegationLiveLog: prune 失败", e);
        }
        return removed;
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    static String oneLine(String s, int limit) {
        if (s == null) {
            return "";
        }
        String collapsed = String.join(" ", s.trim().split("\\s+"));
        if (collapsed.length() > limit) {
            return collapsed.substring(0, limit) + " …(+" + (collapsed.length() - limit) + " chars)";
        }
        return collapsed;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -pl aether-infrastructure -am test -Dtest=FileDelegationLiveLogTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（9 个测试全绿）。

- [ ] **Step 6: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationLiveLog.java aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/deleg/FileDelegationLiveLog.java aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/deleg/FileDelegationLiveLogTest.java
git commit -m "feat(d4): DelegationLiveLog 端口 + FileDelegationLiveLog 文件实现（append-mode 崩溃安全/首错禁用/留存清理/close 幂等）"
```

---

### Task 2: `SubagentLifecycleService` 直播日志接线

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java`
- Test: Create `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleLiveLogIntegrationTest.java`

- [ ] **Step 1: 写失败测试**

创建 `SubagentLifecycleLiveLogIntegrationTest.java`（复用现有 `SubagentLifecycleServiceTest` 的 mock 模式）：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubagentLifecycleLiveLogIntegrationTest {

    private SubAgentBoundary boundary;
    private DefaultAgentFactory agentFactory;
    private ResultRefiner refiner;
    private ExecutorService executor;
    private DelegationLiveLog liveLog;
    private SubagentLifecycleService service;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        boundary = mock(SubAgentBoundary.class);
        agentFactory = mock(DefaultAgentFactory.class);
        refiner = mock(ResultRefiner.class);
        executor = Executors.newCachedThreadPool();
        liveLog = mock(DelegationLiveLog.class);
        service = new SubagentLifecycleService(boundary, agentFactory, refiner, executor, liveLog, 100);
        config = mock(AgentConfig.class);
        when(config.getName()).thenReturn("sub-agent");
        when(config.getCancelToken()).thenReturn(new CancelToken());
        when(boundary.createIsolatedConfig(any(), any(), any(), any(), any(), any())).thenReturn(config);
    }

    private DelegationTask task() {
        return new DelegationTask("分析代码", List.of("code"), null, "u1", "s1", "parent");
    }

    @Test
    void completionAppendsEventsAndClosesWithSummary() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(
                Flowable.just(RuntimeEvent.text("结论")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of("code", 1)));

        String id = "ad-1";
        service.launch(id, task());
        assertTrue(service.wait(id, 5000));

        verify(liveLog).open(eq(id), eq("分析代码"));
        verify(liveLog).append(eq(id), eq("assistant"), eq("结论"));
        verify(liveLog).close(eq(id), contains("COMPLETED"));
    }

    @Test
    void toolResultEventAppendsResultLine() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        RuntimeEvent toolResult = RuntimeEvent.builder()
                .type(RuntimeEvent.EventType.toolResult)
                .toolCallId("c1").toolName("code").toolOutput("done").toolError(false)
                .build();
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.just(toolResult));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[done]", Map.of()));

        String id = "ad-2";
        service.launch(id, task());
        assertTrue(service.wait(id, 5000));

        verify(liveLog).append(eq(id), eq("result"), contains("code ok"));
    }

    @Test
    void cancelClosesWithCancelled() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "", Map.of()));

        String id = "ad-3";
        service.launch(id, task());
        Thread.sleep(100);
        assertTrue(service.cancel(id));
        assertTrue(service.wait(id, 5000));

        verify(liveLog).close(eq(id), contains("CANCELLED"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=SubagentLifecycleLiveLogIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：5-arg 构造函数不存在；mock 校验不通过）。

- [ ] **Step 3: 改构造函数与字段**

在 `SubagentLifecycleService` 中做如下修改：

① 新增字段：
```java
    private final DelegationLiveLog liveLog;
    private final int terminalRetention;
```
放在现有 `runtimes` 字段之后。

② 用下面的构造函数块**整体替换**现有两个构造函数（原 public 3-arg 与 package-private 4-arg）：

```java
    /** Spring 构造：liveLog 可选（无 Bean 时 no-op）；terminalRetention 可配置（默认 100）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                                    ResultRefiner refiner,
                                    org.springframework.beans.factory.ObjectProvider<DelegationLiveLog> liveLogProvider,
                                    @org.springframework.beans.factory.annotation.Value("${aether.subagent.terminal-retention:100}") int terminalRetention) {
        this(boundary, agentFactory, refiner, Executors.newFixedThreadPool(DEFAULT_POOL_SIZE),
                liveLogProvider.getIfAvailable(), terminalRetention);
    }

    /** 测试注入线程池（liveLog 空、retention 默认 100）。 */
    SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                             ResultRefiner refiner, ExecutorService executor) {
        this(boundary, agentFactory, refiner, executor, null, 100);
    }

    /** 测试注入线程池 + liveLog + retention。 */
    SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                             ResultRefiner refiner, ExecutorService executor,
                             DelegationLiveLog liveLog, int terminalRetention) {
        this.boundary = boundary;
        this.agentFactory = agentFactory;
        this.refiner = refiner;
        this.executor = executor;
        this.liveLog = liveLog;
        this.terminalRetention = terminalRetention;
    }
```

- [ ] **Step 4: 在 launch 中 open 直播日志**

在 `launch` 的 `runtimes.put(id, rt);` 之后加一行：

```java
        if (liveLog != null) {
            liveLog.open(id, task.task());
        }
```

- [ ] **Step 5: 在 runAgent 事件循环 append + 终态 close + subagentId MDC**

① `runAgent` 顶部加 `subagentId` MDC（try/finally remove，worker 线程内设置——不在 launch 线程）：

```java
    private ResultRefiner.SubAgentResult runAgent(SubagentRuntime rt) {
        MDC.put("subagentId", rt.id());
        try {
            ...（现有方法体不变，仅把 catch 块末尾补 finally）
        } finally {
            MDC.remove("subagentId");
        }
    }
```
实现时在 `runAgent` 方法第一行加 `MDC.put("subagentId", rt.id());`，并在方法末尾加 `finally { MDC.remove("subagentId"); }`（现有结构是 `try { } catch (Exception e) { }`，直接追加 finally 块）。顶部 import 增加 `import org.slf4j.MDC;`。

② 在 `blockingForEach` lambda 内，现有两个 `if` 块中分别加 append：

```java
                        if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                            collected.add(TurnMessage.assistant(event.getText()));
                            if (liveLog != null) {
                                liveLog.append(rt.id(), "assistant", event.getText());
                            }
                        }
                        if (event.getType() == RuntimeEvent.EventType.toolResult) {
                            collected.add(TurnMessage.toolResult(event.getToolCallId(),
                                    event.getToolName(), event.getToolOutput()));
                            if (liveLog != null) {
                                liveLog.append(rt.id(), "result",
                                        (event.getToolName() == null ? "?" : event.getToolName())
                                        + (event.isToolError() ? " ERROR" : " ok") + ": "
                                        + (event.getToolOutput() == null ? "" : event.getToolOutput()));
                            }
                        }
```

在 `runAgent` 的 COMPLETED 分支（`rt.tryTerminal(SubagentState.COMPLETED);` 之后）与 FAILED 分支（`rt.tryTerminal(SubagentState.FAILED);` 之后）各加一行 `closeLiveLog(rt);`。然后新增私有方法：

```java
    /** 写终态摘要并 close 直播日志（best-effort；close 幂等）。 */
    private void closeLiveLog(SubagentRuntime rt) {
        if (liveLog == null) {
            return;
        }
        String summary = rt.result() != null && rt.result().summary() != null
                ? rt.result().summary() : "";
        liveLog.close(rt.id(), "end status=" + rt.state()
                + (summary.isEmpty() ? "" : " | " + summary));
    }
```

- [ ] **Step 6: 在 cancel / detectStale 中 close**

`cancel` 中 `rt.toCancelled()` 返回 true 的分支内（`future.complete(...)` 之后）加：

```java
        if (liveLog != null) {
            liveLog.close(id, "end status=CANCELLED [取消]");
        }
```

`detectStale` 中 `rt.tryTerminal(SubagentState.TIMED_OUT)` 返回 true 的分支内（`stale.add(rt.id());` 之后）加：

```java
                    if (liveLog != null) {
                        liveLog.close(rt.id(), "end status=TIMED_OUT [超时]");
                    }
```

- [ ] **Step 7: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=SubagentLifecycleLiveLogIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aether-domain -am test -Dtest=SubagentLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 两个测试类全绿（既有 4-arg 构造函数仍兼容旧测试）。

- [ ] **Step 8: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleLiveLogIntegrationTest.java
git commit -m "feat(d4): SubagentLifecycleService 直播日志接线（launch open / 事件流 append / 终态 close，ObjectProvider 可选注入）"
```

---

### Task 3: `GraphExecutionRecorder` 图级 trace 录制

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/GraphExecutionRecorder.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/GraphExecutionRecorderTest.java`

- [ ] **Step 1: 写失败测试**

创建 `GraphExecutionRecorderTest.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphExecutionRecorderTest {

    @Test
    void beginRecordEndProducesOrderedTrace() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        String id = recorder.beginExecution("s1");

        recorder.recordNodeEvent(id, "a", "researcher", GraphFlowState.NodeStatus.RUNNING,
                Instant.now(), null, 0, null);
        recorder.recordNodeEvent(id, "a", "researcher", GraphFlowState.NodeStatus.COMPLETED,
                Instant.now(), Instant.now(), 100, null);
        recorder.recordNodeEvent(id, "b", "summarizer", GraphFlowState.NodeStatus.RUNNING,
                Instant.now(), null, 0, null);

        List<GraphExecutionRecorder.NodeEvent> trace = recorder.getExecutionTrace(id);
        assertEquals(3, trace.size());
        assertEquals("a", trace.get(0).nodeName());
        assertEquals(GraphFlowState.NodeStatus.RUNNING, trace.get(0).status());
        assertEquals("a", trace.get(1).nodeName());
        assertEquals(GraphFlowState.NodeStatus.COMPLETED, trace.get(1).status());
        assertEquals("b", trace.get(2).nodeName());
    }

    @Test
    void retentionEvictsOldestExecutions() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(2);
        String id1 = recorder.beginExecution("s1");
        String id2 = recorder.beginExecution("s2");
        String id3 = recorder.beginExecution("s3");

        assertTrue(recorder.getExecutionTrace(id1).isEmpty(), "最旧执行应被逐出");
        assertFalse(recorder.getExecutionTrace(id2).isEmpty());
        assertFalse(recorder.getExecutionTrace(id3).isEmpty());
    }

    @Test
    void endExecutionRecordsGraphFailureNode() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        String id = recorder.beginExecution("s1");
        recorder.endExecution(id, new RuntimeException("boom"));

        List<GraphExecutionRecorder.NodeEvent> trace = recorder.getExecutionTrace(id);
        assertEquals(1, trace.size());
        assertEquals(GraphFlowState.NodeStatus.FAILED, trace.get(0).status());
        assertEquals("__graph", trace.get(0).nodeName());
    }

    @Test
    void unknownIdReturnsEmpty() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        assertTrue(recorder.getExecutionTrace("nope").isEmpty());
    }

    @Test
    void eventsForEvictedIdAreIgnored() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(1);
        String id = recorder.beginExecution("s1");
        recorder.beginExecution("s2"); // 逐出 id
        recorder.recordNodeEvent(id, "a", "x", GraphFlowState.NodeStatus.RUNNING,
                Instant.now(), null, 0, null);
        assertTrue(recorder.getExecutionTrace(id).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=GraphExecutionRecorderTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：找不到 `GraphExecutionRecorder`）。

- [ ] **Step 3: 写实现**

创建 `GraphExecutionRecorder.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图级执行 trace 录制 — 对齐 hermes moa_trace.py 的 opt-in 语义（aether.graph.trace.persistence=true
 * 才异步落 JSONL）。内存保留最近 {@code retention} 次执行，可 getExecutionTrace 回放。
 * <p>best-effort：任何失败 debug log，绝不影响 GraphExecutor 主流程。</p>
 */
@Slf4j
@Component
public class GraphExecutionRecorder {

    /** 单个节点事件。 */
    public record NodeEvent(
            String graphExecutionId,
            String nodeName,
            String agentType,
            GraphFlowState.NodeStatus status,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            String error
    ) {}

    private final int retention;
    private final boolean persistToFile;
    private final String traceDir;
    private final ExecutorService fileWriter;
    private final Map<String, List<NodeEvent>> traces = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Spring 构造。 */
    @Autowired
    public GraphExecutionRecorder(
            @Value("${aether.graph.trace.retention:200}") int retention,
            @Value("${aether.graph.trace.persistence:false}") boolean persistToFile,
            @Value("${aether.graph.trace.dir:./cache/graph-traces}") String traceDir) {
        this.retention = retention;
        this.persistToFile = persistToFile;
        this.traceDir = traceDir;
        this.fileWriter = persistToFile
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "graph-trace-writer");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    /** 测试构造：仅内存。 */
    GraphExecutionRecorder(int retention) {
        this(retention, false, "./cache/graph-traces");
    }

    /** 开始一次图执行，返回 graphExecutionId（内存注册 + 有界逐出）。 */
    public String beginExecution(String sessionId) {
        String id = "gx-" + UUID.randomUUID().toString().substring(0, 8);
        traces.put(id, new ArrayList<>());
        synchronized (order) {
            order.addLast(id);
            while (order.size() > retention) {
                String evict = order.removeFirst();
                traces.remove(evict);
            }
        }
        return id;
    }

    /** 记录一个节点事件；graphExecutionId 已被逐出时忽略。 */
    public void recordNodeEvent(String graphExecutionId, String nodeName, String agentType,
                                GraphFlowState.NodeStatus status, Instant startedAt,
                                Instant finishedAt, long durationMs, String error) {
        List<NodeEvent> events = traces.get(graphExecutionId);
        if (events == null) {
            return;
        }
        NodeEvent ne = new NodeEvent(graphExecutionId, nodeName, agentType, status,
                startedAt, finishedAt, durationMs, error);
        events.add(ne);
        if (persistToFile && fileWriter != null) {
            fileWriter.submit(() -> appendJsonl(graphExecutionId, ne));
        }
    }

    /** 结束一次图执行；error 非空时记录一条 __graph 失败节点。 */
    public void endExecution(String graphExecutionId, Throwable error) {
        if (error == null) {
            return;
        }
        recordNodeEvent(graphExecutionId, "__graph", null, GraphFlowState.NodeStatus.FAILED,
                Instant.now(), Instant.now(), 0, error.getMessage());
    }

    /** 读取指定图执行的节点事件序列（有序、不可变拷贝）。 */
    public List<NodeEvent> getExecutionTrace(String graphExecutionId) {
        List<NodeEvent> events = traces.get(graphExecutionId);
        return events == null ? List.of() : List.copyOf(events);
    }

    private void appendJsonl(String graphExecutionId, NodeEvent ne) {
        try {
            Path dir = Paths.get(traceDir);
            Files.createDirectories(dir);
            String line = MAPPER.writeValueAsString(ne);
            Files.write(dir.resolve(graphExecutionId + ".jsonl"),
                    (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Exception e) {
            log.debug("GraphExecutionRecorder: 落盘失败 graphExecutionId={}", graphExecutionId, e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=GraphExecutionRecorderTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（5 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/GraphExecutionRecorder.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/GraphExecutionRecorderTest.java
git commit -m "feat(d4): GraphExecutionRecorder 图级 trace 录制（内存有界回放 + 可选 JSONL 落盘，对齐 moa_trace.py）"
```

---

### Task 4: `GraphExecutor` recorder 接线 + MDC

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorTraceTest.java`

- [ ] **Step 1: 写失败测试**

创建 `GraphExecutorTraceTest.java`（`applyBroadcastInterception` 在 `interventionHandler==null` 时短路返回 true，已核实）：

```java
package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.observability.GraphExecutionRecorder;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GraphExecutorTraceTest {

    private GraphExecutor buildExecutor(GraphExecutionRecorder recorder) throws Exception {
        GraphExecutor executor = new GraphExecutor();
        ReflectionTestUtils.setField(executor, "graphExecutionRecorder", recorder);

        DefaultAgentFactory factory = mock(DefaultAgentFactory.class);
        Agent agent = mock(Agent.class);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(RuntimeEvent.text("node output")));
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

    private AgentGraph graphflowGraph() {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        defs.put("n1", AgentNodeDef.builder().name("n1").instruction("i1")
                .outputKey("o1").agentType("researcher").build());
        defs.put("n2", AgentNodeDef.builder().name("n2").instruction("i2")
                .outputKey("o2").agentType("summarizer").build());
        List<AgentEdge> edges = List.of(AgentEdge.builder()
                .workflowName("wf").type(AgentEdgeType.GRAPHFLOW).from("n1").to("n2").build());
        return AgentGraph.builder().appName("app").agentDefs(defs).edges(edges).build();
    }

    @Test
    void recordsNodeEventsAndCleansMdc() throws Exception {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        GraphExecutor executor = buildExecutor(recorder);

        AtomicReference<String> captured = new AtomicReference<>();
        executor.execute(graphflowGraph(), "u1", "s1", "hi")
                .doOnComplete(() -> captured.set(MDC.get("graphExecutionId")))
                .blockingSubscribe();

        assertNotNull(captured.get(), "执行期间 MDC 应携带 graphExecutionId");
        assertNull(MDC.get("graphExecutionId"), "结束后应清理 MDC");
        assertNull(MDC.get("sessionId"), "结束后应清理 sessionId");

        List<GraphExecutionRecorder.NodeEvent> trace = recorder.getExecutionTrace(captured.get());
        assertFalse(trace.isEmpty(), "应录制节点事件");
        assertEquals(GraphFlowState.NodeStatus.RUNNING, trace.get(0).status());
        assertEquals(GraphFlowState.NodeStatus.COMPLETED, trace.get(trace.size() - 1).status());
    }

    @Test
    void recorderIsAutowiredFieldForGraphTrace() throws Exception {
        Field field = GraphExecutor.class.getDeclaredField("graphExecutionRecorder");
        assertEquals(GraphExecutionRecorder.class, field.getType());
        assertTrue(field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class),
                "graphExecutionRecorder 必须经 @Autowired 注入");
    }

    @Test
    void recorderNullIsSafe() throws Exception {
        GraphExecutor executor = buildExecutor(null);
        assertDoesNotThrow(() -> executor.execute(graphflowGraph(), "u1", "s1", "hi")
                .blockingSubscribe());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：`graphExecutionRecorder` 字段不存在）。

- [ ] **Step 3: 加字段**

在 `GraphExecutor` 的 `parallelPool` 字段（L69）之前加：

```java
    /** D4: 图级 trace 录制（可选注入，镜像 interventionHandler 可空模式）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GraphExecutionRecorder graphExecutionRecorder;
```

顶部 import 增加：
```java
import cn.zcj.aether.domain.agent.service.agent.observability.GraphExecutionRecorder;
import org.slf4j.MDC;
```

- [ ] **Step 4: execute() 注入 begin/end + MDC**

将 `execute` 的 Flowable.create lambda 整体替换如下。**注意：`graphExecutionId`/`prevGraphId`/`prevSessionId` 必须声明在 lambda 开头（`try` 之外）——try 块内声明的变量在 catch/finally 中不可见（Java 作用域）。** 末尾 `finally` 用 `graphExecutionId != null` 判断清理（因为我们只在 recorder 非空时才写入 MDC）：

```java
        return Flowable.create(emitter -> {
            String graphExecutionId = null;
            String prevGraphId = MDC.get("graphExecutionId");
            String prevSessionId = MDC.get("sessionId");
            try {
                ExecutionState state = new ExecutionState();
                List<AgentEdge> edges = graph.getEdges();
                Map<String, AgentNodeDef> agentDefs = graph.getAgentDefs();

                if (graphExecutionRecorder != null) {
                    graphExecutionId = graphExecutionRecorder.beginExecution(sessionId);
                    MDC.put("graphExecutionId", graphExecutionId);
                    MDC.put("sessionId", sessionId);
                }

                if (edges.isEmpty() && graph.getEntryPoint() != null) {
                    AgentNodeDef entry = agentDefs.get(graph.getEntryPoint());
                    if (entry != null) {
                        executeSingle(entry, userId, sessionId,
                                initialMessage, state, emitter, "entry");
                    }
                    notifyGraphHook(HookPoint.ON_GRAPH_FINALIZE, HookContext.builder().sessionId(sessionId).build());
                    emitter.onComplete();
                    return;
                }

                for (AgentEdge edge : edges) {
                    if (edge.isGraphFlow()) {
                        executeGraphFlow(graph, userId, sessionId, initialMessage, emitter, graphExecutionId);
                        return;
                    }
                    switch (edge.getType()) {
                        case SEQUENTIAL -> executeSequential(
                                graph, edge, userId, sessionId, state, emitter);
                        case PARALLEL -> executeParallel(
                                graph, edge, userId, sessionId, state, emitter);
                        case LOOP -> executeLoop(
                                graph, edge, userId, sessionId, state, emitter);
                        case SUBAGENT -> executeSubAgents(
                                graph, edge, userId, sessionId, state, emitter);
                        case EVENT_DRIVEN -> executeEventDriven(
                                graph, edge, userId, sessionId, state, emitter);
                    }
                }

                // D3: ON_GRAPH_FINALIZE
                notifyGraphHook(HookPoint.ON_GRAPH_FINALIZE, HookContext.builder().sessionId(sessionId).build());
                emitter.onComplete();
            } catch (Exception e) {
                log.error("GraphExecutor error", e);
                if (graphExecutionRecorder != null && graphExecutionId != null) {
                    graphExecutionRecorder.endExecution(graphExecutionId, e);
                }
                if (!emitter.isCancelled()) {
                    emitter.onNext(RuntimeEvent.error(e.getMessage()));
                    // D3: ON_GRAPH_FINALIZE（异常分支）
                    notifyGraphHook(HookPoint.ON_GRAPH_FINALIZE, HookContext.builder().sessionId(sessionId).build());
                    emitter.onComplete();
                }
            } finally {
                if (graphExecutionId != null) {
                    if (prevGraphId != null) {
                        MDC.put("graphExecutionId", prevGraphId);
                    } else {
                        MDC.remove("graphExecutionId");
                    }
                    if (prevSessionId != null) {
                        MDC.put("sessionId", prevSessionId);
                    } else {
                        MDC.remove("sessionId");
                    }
                }
            }
```

注意：`executeGraphFlow` 调用点改为 6 参（末尾传 `graphExecutionId`）。`graphExecutionId` 是 lambda 内局部变量，正常完成/异常分支都在 finally 前执行，MDC 清理可靠。

- [ ] **Step 5: 改 executeGraphFlow 签名 + 录制节点事件**

将 `executeGraphFlow` 签名改为：
```java
    private void executeGraphFlow(AgentGraph graph, String userId, String sessionId,
            String initialMessage, FlowableEmitter<RuntimeEvent> emitter, String graphExecutionId) {
```

在节点线程内三处加录制：

① 设置 RUNNING（`flowState.setStatus(GraphFlowState.NodeStatus.RUNNING);` 之后）：
```java
                GraphFlowState flowState = flowStates.get(name);
                flowState.setStatus(GraphFlowState.NodeStatus.RUNNING);
                Instant nodeStart = Instant.now();
                if (graphExecutionRecorder != null && graphExecutionId != null) {
                    graphExecutionRecorder.recordNodeEvent(graphExecutionId, name, flowState.getNodeDef().getAgentType(),
                            GraphFlowState.NodeStatus.RUNNING, nodeStart, null, 0, null);
                }
```

② SKIPPED（BROADCAST 拦截 drop，`flowState.setStatus(GraphFlowState.NodeStatus.SKIPPED);` 之后）：
```java
                    flowState.setStatus(GraphFlowState.NodeStatus.SKIPPED);
                    if (graphExecutionRecorder != null && graphExecutionId != null) {
                        graphExecutionRecorder.recordNodeEvent(graphExecutionId, name, flowState.getNodeDef().getAgentType(),
                                GraphFlowState.NodeStatus.SKIPPED, nodeStart, Instant.now(), 0, null);
                    }
```

③ COMPLETED（`flowState.setStatus(GraphFlowState.NodeStatus.COMPLETED);` 之后）：
```java
                        flowState.setStatus(GraphFlowState.NodeStatus.COMPLETED);
                        if (graphExecutionRecorder != null && graphExecutionId != null) {
                            graphExecutionRecorder.recordNodeEvent(graphExecutionId, name, flowState.getNodeDef().getAgentType(),
                                    GraphFlowState.NodeStatus.COMPLETED, nodeStart, Instant.now(),
                                    java.time.Duration.between(nodeStart, Instant.now()).toMillis(), null);
                        }
```

④ FAILED（catch 块 `flowState.setStatus(GraphFlowState.NodeStatus.FAILED);` 之后）：
```java
                        flowState.setStatus(GraphFlowState.NodeStatus.FAILED);
                        if (graphExecutionRecorder != null && graphExecutionId != null) {
                            graphExecutionRecorder.recordNodeEvent(graphExecutionId, name, flowState.getNodeDef().getAgentType(),
                                    GraphFlowState.NodeStatus.FAILED, nodeStart, Instant.now(),
                                    java.time.Duration.between(nodeStart, Instant.now()).toMillis(), e.getMessage());
                        }
```

需要 import：`java.time.Instant`。

- [ ] **Step 6: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=GraphExecutorTraceTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aether-domain -am test -Dtest=GraphExecutorTest,GraphExecutorHookTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 三个测试类全绿（既有 GraphExecutor 测试不受影响）。

- [ ] **Step 7: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorTraceTest.java
git commit -m "feat(d4): GraphExecutor 接线 GraphExecutionRecorder（execute begin/end + graphflow 节点事件）+ MDC graphExecutionId/sessionId 清理"
```

---

### Task 5: `AgentTracer` 图级 span

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/AgentTracer.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/AgentTracerGraphSpanTest.java`

- [ ] **Step 1: 写失败测试**

创建 `AgentTracerGraphSpanTest.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentTracerGraphSpanTest {

    @Test
    void startGraphExecutionReturnsSpanScope() {
        try (AgentTracer.SpanScope scope = AgentTracer.startGraphExecution("gx-1", "s1")) {
            assertNotNull(scope);
        }
    }

    @Test
    void startGraphNodeReturnsSpan() {
        var span = AgentTracer.startGraphNode("gx-1", "researcher", "n1");
        assertNotNull(span);
        AgentTracer.endGraphNode(span, true, null);
    }

    @Test
    void endGraphNodeErrorSetsErrorStatus() {
        var span = AgentTracer.startGraphNode("gx-1", "researcher", "n1");
        assertNotNull(span);
        assertDoesNotThrow(() -> AgentTracer.endGraphNode(span, false, "boom"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=AgentTracerGraphSpanTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：方法不存在）。

- [ ] **Step 3: 加图级 span 方法**

在 `AgentTracer` 的"Span 创建"区（`startToolCall` 之后）加：

```java
    /** 图级执行 span（graph.execute）— D4 可视化调试。 */
    public static SpanScope startGraphExecution(String graphExecutionId, String sessionId) {
        Span span = tracer.spanBuilder("graph.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("graph.execution.id", graphExecutionId)
            .setAttribute("session.id", sessionId)
            .startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    /** 图节点 span（graph.node.<type>.<id>）— D4 可视化调试。 */
    public static Span startGraphNode(String graphExecutionId, String agentType, String nodeName) {
        return tracer.spanBuilder("graph.node." + (agentType == null ? "unknown" : agentType) + "." + nodeName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("graph.execution.id", graphExecutionId)
            .setAttribute("graph.node.name", nodeName)
            .startSpan();
    }
```

在"Span 结束"区（`endSpanWithError` 之后）加：

```java
    /** 结束图节点 span。 */
    public static void endGraphNode(Span span, boolean success, String errorMsg) {
        if (success) {
            span.setStatus(StatusCode.OK);
        } else if (errorMsg != null) {
            span.setStatus(StatusCode.ERROR, errorMsg);
        } else {
            span.setStatus(StatusCode.ERROR);
        }
        span.end();
    }
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=AgentTracerGraphSpanTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（3 个测试全绿；无 OTel SDK 时 GlobalOpenTelemetry 返回 no-op tracer，安全）。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/AgentTracer.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/AgentTracerGraphSpanTest.java
git commit -m "feat(d4): AgentTracer 图级 span（graph.execute / graph.node.<type>.<id>）"
```

---

### Task 6: `AgentEventPublisher` MDC 富化

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisher.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisherMdcTest.java`

- [ ] **Step 1: 写失败测试**

创建 `AgentEventPublisherMdcTest.java`：

```java
package cn.zcj.aether.domain.agent.service.event;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventPublisherMdcTest {

    private static String captured; // logback 未配置，用反射读 toJson 不可行 → 测 MDC 合并辅助方法

    @Test
    void mdcFieldsReturnContextWhenPresent() {
        MDC.put("graphExecutionId", "gx-1");
        MDC.put("sessionId", "s1");
        MDC.put("subagentId", "ad-1");
        try {
            Map<String, String> mdc = AgentEventPublisher.mdcFields();
            assertEquals("gx-1", mdc.get("graphExecutionId"));
            assertEquals("s1", mdc.get("sessionId"));
            assertEquals("ad-1", mdc.get("subagentId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void mdcFieldsReturnEmptyWhenAbsent() {
        MDC.clear();
        assertTrue(AgentEventPublisher.mdcFields().isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=AgentEventPublisherMdcTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：`mdcFields()` 不存在）。

- [ ] **Step 3: 加 MDC 富化**

在 `AgentEventPublisher` 中，将 `toJson` 方法替换为合并 MDC 上下文：

```java
    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(toJsonWithMdc(obj));
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /** 把 MDC 上下文（graphExecutionId/sessionId/subagentId）并入 JSON 顶层，不改任何事件签名。 */
    private static Object toJsonWithMdc(Object obj) {
        Map<String, String> mdc = mdcFields();
        if (mdc.isEmpty()) {
            return obj;
        }
        // 事件对象序列化为 Map 后合并 MDC 键
        try {
            Map<String, Object> base = new java.util.LinkedHashMap<>(MAPPER.convertValue(obj, Map.class));
            base.putAll(mdc);
            return base;
        } catch (Exception e) {
            return obj;
        }
    }

    /** 当前 MDC 中的结构化上下文字段（供日志 grep/join 与测试）。 */
    public static Map<String, String> mdcFields() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"graphExecutionId", "sessionId", "subagentId"}) {
            String v = MDC.get(key);
            if (v != null && !v.isEmpty()) {
                result.put(key, v);
            }
        }
        return result;
    }
```

顶部 import 增加 `import org.slf4j.MDC;`。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=AgentEventPublisherMdcTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（2 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisher.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisherMdcTest.java
git commit -m "feat(d4): AgentEventPublisher 事件 JSON 合并 MDC 上下文（graphExecutionId/sessionId/subagentId，不改签名）"
```

---

### Task 7: `ExecutionControlService` 控制面服务

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlService.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlServiceTest.java`

- [ ] **Step 1: 写失败测试**

创建 `ExecutionControlServiceTest.java`：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionControlServiceTest {

    private final SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
    private final SpawnGate spawnGate = mock(SpawnGate.class);
    private final AsyncDelegationService asyncDelegation = mock(AsyncDelegationService.class);

    private ExecutionControlService service() {
        return new ExecutionControlService(lifecycle, spawnGate, asyncDelegation);
    }

    @Test
    void interruptSubAgentDelegatesToLifecycleCancel() {
        when(lifecycle.cancel("ad-1")).thenReturn(true);
        assertTrue(service().interruptSubAgent("ad-1"));
        verify(lifecycle).cancel("ad-1");
    }

    @Test
    void interruptUnknownSubAgentReturnsFalse() {
        when(lifecycle.cancel("nope")).thenReturn(false);
        assertFalse(service().interruptSubAgent("nope"));
    }

    @Test
    void listActiveSubAgentsComposesLifecycleViews() {
        when(lifecycle.activeIds()).thenReturn(List.of("ad-1", "ad-2"));
        when(lifecycle.status("ad-1")).thenReturn(Optional.of(SubagentState.RUNNING));
        when(lifecycle.taskOf("ad-1")).thenReturn(Optional.of(
                new DelegationTask("t1", List.of(), null, "u1", "s1", null)));
        when(lifecycle.status("ad-2")).thenReturn(Optional.empty());
        when(lifecycle.taskOf("ad-2")).thenReturn(Optional.empty());

        List<ExecutionControlService.ActiveSubAgentView> views = service().listActiveSubAgents();
        assertEquals(2, views.size());
        assertEquals("ad-1", views.get(0).id());
        assertEquals("RUNNING", views.get(0).status());
        assertEquals("s1", views.get(0).sessionId());
        assertEquals("t1", views.get(0).goal());
    }

    @Test
    void setSpawnPausedDelegatesToSpawnGate() {
        when(spawnGate.isSpawnPaused()).thenReturn(true);
        service().setSpawnPaused(true);
        verify(spawnGate).setSpawnPaused(true);
        assertTrue(spawnGate.isSpawnPaused());
    }

    @Test
    void listDelegationsWithSessionDelegatesToAsync() {
        DelegationRecord rec = new DelegationRecord();
        rec.setId("ad-1");
        when(asyncDelegation.listBySession("s1")).thenReturn(List.of(rec));
        assertEquals(1, service().listDelegations("s1").size());
        verify(asyncDelegation).listBySession("s1");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=ExecutionControlServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：`ExecutionControlService` 不存在）。

- [ ] **Step 3: 写实现**

创建 `ExecutionControlService.java`：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排实时控制面 — 对齐 hermes delegate_tool.py TUI 能力（interrupt / list / spawn pause / delegation 查询）。
 * <p>纯委托：复用 D1 的 SubagentLifecycleService.cancel / SpawnGate / AsyncDelegationService.listBySession。</p>
 */
@Slf4j
@Service
public class ExecutionControlService {

    /** 活跃子Agent视图（供控制面/前端展示）。parentAgentId 恒 null（ToolContext 无当前 agentId）。 */
    public record ActiveSubAgentView(String id, String status, String sessionId,
                                     String goal, String parentAgentId) {}

    private final SubagentLifecycleService lifecycle;
    private final SpawnGate spawnGate;
    private final AsyncDelegationService asyncDelegation;

    public ExecutionControlService(SubagentLifecycleService lifecycle,
                                   SpawnGate spawnGate,
                                   AsyncDelegationService asyncDelegation) {
        this.lifecycle = lifecycle;
        this.spawnGate = spawnGate;
        this.asyncDelegation = asyncDelegation;
    }

    /** 当前活跃子Agent视图列表。 */
    public List<ActiveSubAgentView> listActiveSubAgents() {
        List<ActiveSubAgentView> views = new ArrayList<>();
        for (String id : lifecycle.activeIds()) {
            String status = lifecycle.status(id).map(Object::toString).orElse("?");
            String sessionId = lifecycle.taskOf(id).map(t -> t.parentSessionId()).orElse(null);
            String goal = lifecycle.taskOf(id).map(t -> t.task()).orElse(null);
            views.add(new ActiveSubAgentView(id, status, sessionId, goal, null));
        }
        return views;
    }

    /** 中断单个子Agent；返回是否命中（对齐 hermes interrupt_subagent 返回布尔）。 */
    public boolean interruptSubAgent(String id) {
        boolean accepted = lifecycle.cancel(id);
        if (!accepted) {
            log.warn("ExecutionControlService: interrupt 未命中 id={}", id);
        }
        return accepted;
    }

    /** 全局暂停/恢复新 spawn（对齐 hermes set_spawn_paused）。 */
    public boolean setSpawnPaused(boolean paused) {
        spawnGate.setSpawnPaused(paused);
        return spawnGate.isSpawnPaused();
    }

    /** 查询委派列表；sessionId 为空时返回空列表（运行时视图由 listActiveSubAgents 提供）。 */
    public List<DelegationRecord> listDelegations(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return asyncDelegation.listBySession(sessionId);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=ExecutionControlServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（5 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlService.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/ExecutionControlServiceTest.java
git commit -m "feat(d4): ExecutionControlService 实时控制面（list/interrupt/spawn-pause/delegations，复用 D1）"
```

---

### Task 8: `OrchestrationController` + trigger 测试依赖

**Files:**
- Modify: `aether-trigger/pom.xml`
- Create: `aether-trigger/src/main/java/cn/zcj/aether/trigger/http/OrchestrationController.java`
- Test: `aether-trigger/src/test/java/cn/zcj/aether/trigger/http/OrchestrationControllerTest.java`

- [ ] **Step 1: 加 test 依赖**

在 `aether-trigger/pom.xml` 的 `<dependencies>` 内追加（沿用 domain 模块的测试依赖组合）：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

创建 `OrchestrationControllerTest.java`：

```java
package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.ExecutionControlService;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrchestrationControllerTest {

    private ExecutionControlService control;
    private OrchestrationController controller;

    @BeforeEach
    void setUp() {
        control = mock(ExecutionControlService.class);
        controller = new OrchestrationController();
        ReflectionTestUtils.setField(controller, "executionControlService", control);
    }

    @Test
    void activeSubAgentsReturnsWrappedList() {
        when(control.listActiveSubAgents()).thenReturn(List.of(
                new ExecutionControlService.ActiveSubAgentView("ad-1", "RUNNING", "s1", "t1", null)));
        Response<List<ExecutionControlService.ActiveSubAgentView>> resp = controller.activeSubAgents();
        assertNotNull(resp.getData());
        assertEquals(1, resp.getData().size());
        assertEquals("ad-1", resp.getData().get(0).id());
    }

    @Test
    void interruptReturnsHitResult() {
        when(control.interruptSubAgent("ad-1")).thenReturn(true);
        Response<Boolean> resp = controller.interrupt("ad-1");
        assertEquals(Boolean.TRUE, resp.getData());
        verify(control).interruptSubAgent("ad-1");
    }

    @Test
    void spawnPauseParsesBodyAndDelegates() {
        when(control.setSpawnPaused(true)).thenReturn(true);
        Response<Boolean> resp = controller.setSpawnPaused(Map.of("paused", true));
        assertEquals(Boolean.TRUE, resp.getData());
        verify(control).setSpawnPaused(true);
    }

    @Test
    void delegationsWithSessionDelegates() {
        DelegationRecord rec = new DelegationRecord();
        rec.setId("ad-1");
        rec.setState(SubagentState.RUNNING);
        when(control.listDelegations("s1")).thenReturn(List.of(rec));
        Response<List<DelegationRecord>> resp = controller.delegations("s1");
        assertEquals(1, resp.getData().size());
        verify(control).listDelegations("s1");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn -pl aether-trigger -am test -Dtest=OrchestrationControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：`OrchestrationController` 不存在，或 test 依赖未生效）。

- [ ] **Step 4: 写控制器**

创建 `OrchestrationController.java`：

```java
package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.ExecutionControlService;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 编排实时控制端点 — 对齐 hermes delegate_tool.py TUI 能力，复用现有 JWT 过滤链。
 * <pre>
 * GET  /api/orchestration/active-subagents          → 活跃子Agent列表
 * POST /api/orchestration/subagents/{id}/interrupt  → 中断子Agent
 * POST /api/orchestration/spawn/pause               → 暂停/恢复新 spawn（body: {"paused": bool}）
 * GET  /api/orchestration/delegations?sessionId=    → 委派查询
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestration/")
public class OrchestrationController {

    @Resource
    private ExecutionControlService executionControlService;

    @GetMapping("active-subagents")
    public Response<List<ExecutionControlService.ActiveSubAgentView>> activeSubAgents() {
        try {
            List<ExecutionControlService.ActiveSubAgentView> data = executionControlService.listActiveSubAgents();
            return Response.<List<ExecutionControlService.ActiveSubAgentView>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询活跃子Agent失败", e);
            return Response.<List<ExecutionControlService.ActiveSubAgentView>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("subagents/{id}/interrupt")
    public Response<Boolean> interrupt(@PathVariable("id") String id) {
        try {
            boolean hit = executionControlService.interruptSubAgent(id);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(hit)
                    .build();
        } catch (AppException e) {
            return Response.<Boolean>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("中断子Agent失败 id={}", id, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("spawn/pause")
    public Response<Boolean> setSpawnPaused(@RequestBody(required = false) Map<String, Object> body) {
        try {
            boolean paused = body != null && Boolean.TRUE.equals(body.get("paused"));
            boolean newState = executionControlService.setSpawnPaused(paused);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(newState)
                    .build();
        } catch (Exception e) {
            log.error("设置 spawn 暂停失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("delegations")
    public Response<List<DelegationRecord>> delegations(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            List<DelegationRecord> data = executionControlService.listDelegations(sessionId);
            return Response.<List<DelegationRecord>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询委派列表失败 sessionId={}", sessionId, e);
            return Response.<List<DelegationRecord>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -pl aether-trigger -am test -Dtest=OrchestrationControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（4 个测试全绿）。

- [ ] **Step 6: 提交**

```bash
git add aether-trigger/pom.xml aether-trigger/src/main/java/cn/zcj/aether/trigger/http/OrchestrationController.java aether-trigger/src/test/java/cn/zcj/aether/trigger/http/OrchestrationControllerTest.java
git commit -m "feat(d4): OrchestrationController 实时控制端点（active-subagents/interrupt/spawn-pause/delegations）+ trigger 测试依赖"
```

---

### Task 9: `StaleDelegationScanner` 调度器 + 配置

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/StaleDelegationScanner.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/StaleDelegationScannerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `StaleDelegationScannerTest.java`：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaleDelegationScannerTest {

    @Test
    void scanOnceDelegatesDetectStaleWithTimeout() {
        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        when(lifecycle.detectStale(Duration.ofMinutes(10)))
                .thenReturn(List.of("ad-1", "ad-2"));

        StaleDelegationScanner scanner = new StaleDelegationScanner(lifecycle,
                Duration.ofMinutes(10), 1000);
        List<String> stale = scanner.scanOnce();

        assertEquals(List.of("ad-1", "ad-2"), stale);
        verify(lifecycle).detectStale(Duration.ofMinutes(10));
    }

    @Test
    void zeroIntervalDoesNotSchedule() {
        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        StaleDelegationScanner scanner = new StaleDelegationScanner(lifecycle,
                Duration.ofMinutes(1), 0);
        scanner.start();
        // 不抛异常即通过（interval<=0 不启动调度线程）
        scanner.shutdown();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=StaleDelegationScannerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：`StaleDelegationScanner` 不存在）。

- [ ] **Step 3: 写实现**

创建 `StaleDelegationScanner.java`：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 挂起子Agent检测调度器 — 承接 Batch 3 遗留：detectStale 无生产触发点（挂起子Agent占租约+池线程）。
 * <p>自建单线程 ScheduledExecutorService（代码库无 @EnableScheduling）；配置：
 * aether.delegation.stale-timeout（默认 PT10M）/ aether.delegation.stale-scan-interval-ms
 * （默认 60000；<=0 禁用扫描）。detectStale 完成后经 D1 finalizeDelegation 自动释放租约。</p>
 */
@Slf4j
@Component
public class StaleDelegationScanner {

    private final SubagentLifecycleService lifecycle;
    private final Duration staleTimeout;
    private final long scanIntervalMs;
    private final ScheduledExecutorService scheduler;

    /** Spring 构造。 */
    @Autowired
    public StaleDelegationScanner(SubagentLifecycleService lifecycle,
            @Value("${aether.delegation.stale-timeout:PT10M}") String staleTimeout,
            @Value("${aether.delegation.stale-scan-interval-ms:60000}") long scanIntervalMs) {
        this(lifecycle, Duration.parse(staleTimeout), scanIntervalMs);
    }

    /** 测试构造：不启动 Spring。 */
    StaleDelegationScanner(SubagentLifecycleService lifecycle, Duration staleTimeout, long scanIntervalMs) {
        this.lifecycle = lifecycle;
        this.staleTimeout = staleTimeout;
        this.scanIntervalMs = scanIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stale-delegation-scan");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (scanIntervalMs > 0) {
            scheduler.scheduleWithFixedDelay(this::scanOnce, scanIntervalMs, scanIntervalMs,
                    TimeUnit.MILLISECONDS);
            log.info("StaleDelegationScanner 已启动: timeout={} intervalMs={}", staleTimeout, scanIntervalMs);
        } else {
            log.info("StaleDelegationScanner 已禁用（interval<=0）");
        }
    }

    /** 执行一次 stale 扫描；返回受影响 id 列表（供测试直接调用）。 */
    public List<String> scanOnce() {
        List<String> stale = lifecycle.detectStale(staleTimeout);
        if (!stale.isEmpty()) {
            log.warn("StaleDelegationScanner: 检测到 {} 个挂起子Agent: {}", stale.size(), stale);
        }
        return stale;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=StaleDelegationScannerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（2 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/StaleDelegationScanner.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/StaleDelegationScannerTest.java
git commit -m "feat(d4): StaleDelegationScanner 挂起检测调度器（自建 ScheduledExecutorService，承接 Batch 3 遗留①）"
```

---

### Task 10: `SubagentLifecycleService` runtimes 终态保留上限

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleRetentionTest.java`

- [ ] **Step 1: 写失败测试**

创建 `SubagentLifecycleRetentionTest.java`：

```java
package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubagentLifecycleRetentionTest {

    private SubAgentBoundary boundary;
    private DefaultAgentFactory agentFactory;
    private ResultRefiner refiner;
    private ExecutorService executor;
    private SubagentLifecycleService service;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        boundary = mock(SubAgentBoundary.class);
        agentFactory = mock(DefaultAgentFactory.class);
        refiner = mock(ResultRefiner.class);
        executor = Executors.newCachedThreadPool();
        service = new SubagentLifecycleService(boundary, agentFactory, refiner, executor, null, 3);
        config = mock(AgentConfig.class);
        when(config.getName()).thenReturn("sub-agent");
        when(config.getCancelToken()).thenReturn(new CancelToken());
        when(boundary.createIsolatedConfig(any(), any(), any(), any(), any(), any())).thenReturn(config);
    }

    private void launchToCompletion(String id) {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(RuntimeEvent.text("x")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[x]", Map.of()));
        service.launch(id, new DelegationTask("t", List.of(), null, "u1", "s1", null));
    }

    @Test
    void terminalRetentionEvictsOldestCompleted() throws Exception {
        launchToCompletion("ad-1");
        launchToCompletion("ad-2");
        launchToCompletion("ad-3");
        launchToCompletion("ad-4"); // 触发逐出，保留最近 3 个

        assertTrue(service.wait("ad-1", 5000));
        assertTrue(service.wait("ad-4", 5000));

        // 最旧的 ad-1 已被逐出 → status 返回 empty；最近 3 个仍在
        assertTrue(service.status("ad-1").isEmpty(), "最旧终态应被逐出");
        assertEquals(SubagentState.COMPLETED, service.status("ad-4").orElseThrow());
    }

    @Test
    void activeRuntimesAreNeverEvicted() throws Exception {
        // 永不完成的事件流（保持 RUNNING）
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "", Map.of()));

        service.launch("ad-keep", new DelegationTask("t", List.of(), null, "u1", "s1", null));
        launchToCompletion("ad-1");
        launchToCompletion("ad-2");
        launchToCompletion("ad-3");
        launchToCompletion("ad-4");

        Thread.sleep(200);
        assertEquals(SubagentState.RUNNING, service.status("ad-keep").orElseThrow(),
                "active 运行时永不逐出");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=SubagentLifecycleRetentionTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（ad-1 未被逐出）。

- [ ] **Step 3: 加 evict 逻辑**

在 `SubagentLifecycleService` 中做如下修改：

① 在 `shutdown()` 方法之前新增私有方法：

```java
    /** 终态保留上限：超出 terminalRetention 时逐出最旧终态运行时（active 永不逐出）。 */
    private void evictTerminalIfNeeded() {
        while (runtimes.size() > terminalRetention) {
            String oldest = null;
            Instant oldestAt = null;
            for (SubagentRuntime rt : runtimes.values()) {
                if (SubagentRuntime.isTerminal(rt.state())) {
                    Instant at = rt.createdAt();
                    if (oldestAt == null || at.isBefore(oldestAt)) {
                        oldest = rt.id();
                        oldestAt = at;
                    }
                }
            }
            if (oldest == null) {
                break; // 无可逐出的终态条目（active 全部占位）
            }
            runtimes.remove(oldest);
        }
    }
```

② 在三处终态转换后调用 `evictTerminalIfNeeded();`：
- `runAgent` 的 `rt.tryTerminal(SubagentState.COMPLETED);` 之后
- `runAgent` 的 `rt.tryTerminal(SubagentState.FAILED);` 之后
- `cancel` 中 `rt.toCancelled()` 返回 true 的分支末尾
- `detectStale` 中 `rt.tryTerminal(SubagentState.TIMED_OUT)` 返回 true 的分支内（`liveLog.close` 之后）

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=SubagentLifecycleRetentionTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aether-domain -am test -Dtest=SubagentLifecycleServiceTest,SubagentLifecycleLiveLogIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 三个测试类全绿。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleRetentionTest.java
git commit -m "feat(d4): SubagentLifecycleService runtimes 终态保留上限（默认100，逐出最旧终态，active 永不逐出）"
```

---

### Task 11: `BackgroundReviewer` 后台自评审（默认关闭）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisher.java`（新增评审事件方法）
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEvent.java`（新增 BackgroundReview 事件 record）
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `BackgroundReviewerTest.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackgroundReviewerTest {

    @Test
    void reviewProducesReviewTextFromModelCall() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        ModelInvoker.ModelCallResult result = ModelInvoker.ModelCallResult.builder()
                .fullText("评审通过").build();
        when(modelInvoker.callWithStream(any(), any(), any(), any())).thenReturn(result);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");
        assertEquals("评审通过", reviewer.review("final output"));
    }

    @Test
    void submitWithBlankOutputIsIgnored() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");
        reviewer.submit("gx-1", "goal", "   ");
        verify(modelInvoker, never()).callWithStream(any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl aether-domain -am test -Dtest=BackgroundReviewerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（编译错误：`BackgroundReviewer` 不存在）。

- [ ] **Step 3: 给 AgentEventPublisher + AgentEvent 加评审事件**

在 `AgentEvent.java` 中追加一个 record（放在 `DelegationDispatched` 附近）：

```java
    /** D4: 后台图执行质量评审事件。 */
    public record BackgroundReview(
            String eventId,
            java.time.Instant timestamp,
            String graphExecutionId,
            String sessionId,
            String goal,
            String review
    ) {}
```

在 `AgentEventPublisher.java` 中追加方法：

```java
    /**
     * D4: 发布后台自评审事件（BackgroundReviewer 专用）。
     */
    public void publishBackgroundReview(String graphExecutionId, String sessionId,
                                        String goal, String review) {
        AgentEvent.BackgroundReview event = new AgentEvent.BackgroundReview(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                graphExecutionId, sessionId, goal, review);
        log.info("background_review: {}", toJson(event));
    }
```

- [ ] **Step 4: 写 BackgroundReviewer 实现**

创建 `BackgroundReviewer.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台图执行质量评审 — 对齐 hermes background_review.py（默认关闭，配置启用）。
 * <p>图执行结束后异步对最终输出做模型评审，回写 GraphExecutionRecorder（一条 __review 节点事件）
 * 并发布 AgentEventPublisher 评审事件。best-effort：失败只记 debug log。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.graph.background-review.enabled",
        havingValue = "true", matchIfMissing = false)
public class BackgroundReviewer {

    private final GraphExecutionRecorder recorder;
    private final AgentEventPublisher publisher;
    private final ModelInvoker modelInvoker;
    private final ChatModel chatModel;
    private final String modelRef;
    private final String systemPrompt;
    private final ExecutorService executor;

    @Autowired
    public BackgroundReviewer(GraphExecutionRecorder recorder,
                              AgentEventPublisher publisher,
                              ModelInvoker modelInvoker,
                              ChatModel chatModel,
                              @Value("${aether.graph.background-review.model-ref:gpt-4o}") String modelRef,
                              @Value("${aether.graph.background-review.system-prompt:你是资深评审。请对给定 Agent 执行结果做质量评审，200 字内。}") String systemPrompt) {
        this.recorder = recorder;
        this.publisher = publisher;
        this.modelInvoker = modelInvoker;
        this.chatModel = chatModel;
        this.modelRef = modelRef;
        this.systemPrompt = systemPrompt;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "background-review");
            t.setDaemon(true);
            return t;
        });
    }

    /** 异步提交图执行结果做后台评审。best-effort。 */
    public void submit(String graphExecutionId, String goal, String finalOutput) {
        if (graphExecutionId == null || finalOutput == null || finalOutput.isBlank()) {
            return;
        }
        executor.submit(() -> {
            try {
                String review = review(finalOutput);
                recorder.recordNodeEvent(graphExecutionId, "__review", "background-review",
                        GraphFlowState.NodeStatus.COMPLETED, Instant.now(), Instant.now(), 0, null);
                publisher.publishBackgroundReview(graphExecutionId, null, goal, review);
                log.info("BackgroundReviewer: graphExecutionId={} 评审完成: {}",
                        graphExecutionId, truncate(review, 120));
            } catch (Exception e) {
                log.debug("BackgroundReviewer: 评审失败 graphExecutionId={}", graphExecutionId, e);
            }
        });
    }

    /** 对最终输出做模型评审；返回评审文本。 */
    String review(String finalOutput) {
        ModelInvoker.ModelCallResult r = modelInvoker.callWithStream(chatModel,
                List.of(new UserMessage(finalOutput)), systemPrompt, modelRef);
        return r.hasError() ? "[评审失败: " + r.getError() + "]"
                : (r.getFullText() == null ? "" : r.getFullText());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=BackgroundReviewerTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aether-domain -am test -Dtest=AgentEventPublisherMdcTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 两个测试类全绿。

- [ ] **Step 6: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEvent.java aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisher.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java
git commit -m "feat(d4): BackgroundReviewer 后台自评审（默认关闭，@ConditionalOnProperty 启用）"
```

---

### Task 12: 配置文档化 + 全量回归

**Files:**
- Modify: `aether-app/src/main/resources/application.yml`
- Modify: `aether-app/src/main/resources/application-dev.yml`（可选覆盖）

- [ ] **Step 1: application.yml 追加 D4 配置**

在 `application.yml` 的 `aether:` 段下追加（与 `aether.memory:` 同级；默认值与 @Value 默认值保持一致）：

```yaml
  # ====== D4: 可视化调试 ======
  delegation:
    live-log-dir: ./cache/delegation/live   # 每子Agent直播日志根目录（对齐 hermes cache/delegation/live）
    stale-timeout: PT10M                     # 挂起子Agent判定窗口（StaleDelegationScanner）
    stale-scan-interval-ms: 60000            # stale 扫描间隔；<=0 禁用
  subagent:
    terminal-retention: 100                  # runtimes 终态保留上限（超出逐出最旧）
  graph:
    trace:
      retention: 200                         # 图级 trace 内存保留执行数
      persistence: false                     # 是否异步落 JSONL（对齐 moa_trace.py opt-in）
      dir: ./cache/graph-traces
      background-review:
        enabled: false                       # 后台自评审总开关（默认关）
        model-ref: gpt-4o
```

在 `application-dev.yml` 的 `aether.delegation:` 段下追加（开发环境开启图 trace 落盘便于调试）：

```yaml
    # ====== D4: 图级 trace 落盘（开发环境开启便于离线审计）======
  graph:
    trace:
      persistence: true
```

- [ ] **Step 2: 全量回归**

```bash
cd "D:/code/Agents-framework/aether"
mvn -pl aether-domain -am test
mvn -pl aether-infrastructure -am test
mvn -pl aether-trigger -am test
```
Expected: 三个模块全绿（domain 既有 251 tests + 本批新增全过；infra 既有 16 tests + FileDelegationLiveLogTest；trigger OrchestrationControllerTest）。

- [ ] **Step 3: 编译级启动验证**

```bash
mvn -pl aether-app -am compile
```
Expected: 编译成功（prod 不带 persistence/background-review 属性时可启动——ObjectProvider 可选注入 + @ConditionalOnProperty 保证无 Critical）。

- [ ] **Step 4: 提交**

```bash
git add aether-app/src/main/resources/application.yml aether-app/src/main/resources/application-dev.yml
git commit -m "docs(d4): 配置文档化（live-log-dir/stale/retention/graph-trace/background-review）+ 开发环境图 trace 落盘"
```

---

## Self-Review

**1. Spec 覆盖检查：**
- §4① DelegationLiveLog（端口+infra+事件流接线）→ Task 1/2
- §4② GraphExecutionRecorder（内存有界+可选落盘）→ Task 3/4
- §4④ trace 增强（AgentTracer 图级 span / AgentEventPublisher MDC 富化 / MDC 统一）→ Task 4/5/6
- §4③ ExecutionControlService + HTTP → Task 7/8
- §4 承接：detectStale 调度 → Task 9；runtimes 清理 → Task 10；心跳/ChildResultAggregator/parentAgentId 保持延后（javadoc 注明）
- §4⑤ BackgroundReviewer（默认关）→ Task 11
- §6 测试 → 每任务内 TDD 测试覆盖全部三类
- §7 验收 → Task 12 全量回归

**2. 占位符扫描：** 无 TBD/TODO；每个代码步骤含完整代码。

**3. 类型一致性：** `DelegationLiveLog` 接口方法签名跨 Task 1/2 一致；`GraphExecutionRecorder` 方法签名跨 Task 3/4/11 一致；`ExecutionControlService.ActiveSubAgentView` 跨 Task 7/8 一致；构造函数链 `SubagentLifecycleService`（public @Autowired 5-arg / package-private 4-arg 与 6-arg）在 Task 2 定稿、Task 10 复用。

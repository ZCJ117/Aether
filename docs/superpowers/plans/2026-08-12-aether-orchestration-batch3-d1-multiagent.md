# Batch 3（D1 多Agent协作）异步委派实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Aether 中新增异步子Agent委派能力（方案 a：异步平行新增，同步 `SubAgentOrchestrator` 路径保留原样），含生命周期状态机、PostgreSQL 持久化、崩溃恢复、completion 事件总线、并发租约、预算合并、spawn 闸门。

**Architecture:** 分层增强（方案 A）。domain 层新增 8 个组件：`SubagentState`/`DelegationTask`/`DelegationRecord` 模型、`SubagentLifecycleService`（内存生命周期状态机+执行）、`AsyncDelegationStore`（端口）+ `AsyncDelegationService`（持久化队列+恢复+回灌）、`CompletionBus`（事件总线）、`LeaseManager`（每 session 并发上限）、`DelegationBudget`+`ChildResultAggregator`（预算截断合并）、`SpawnGate`（暂停+深度闸门）。infrastructure 层 `PgAsyncDelegationStore` 复用现有 `spring-jdbc`+`postgresql`（**零新依赖**）。`SubAgentDelegationTool` 增加 `async` 异步模式选项，`ChatModelNode` 完成接线。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate+RowMapper、JUnit5、Mockito、RxJava3（Flowable）、Lombok。**持久化 PostgreSQL（用户 2026-08-12 拍板，替代设计文档原 SQLite 方案，不引入 sqlite-jdbc）。**

**hermes 对齐源（只读，不可修改）：**
- `agent/subagent_lifecycle.py`（状态机 L37/L186，launch L197，cancel L291，_run L401）
- `tools/async_delegation.py`（表结构 L142，dispatch L594，recover L293，restore L344，容量检查 L678，completion L780，list L1269，interrupt L1331/L1360）
- `tools/process_registry.py`（completion_queue L173，drain L1235）
- `tools/delegate_tool.py`（set_spawn_paused L153，_apply_summary_budget L1897，_finalize_child_results L2616，深度 L2795）+ `agent/credential_pool.py`（acquire_lease L1926）

**与设计 §6.3 的三处语义校正**（hermes 实际语义优先，均已核实）：
1. **⑥ LeaseManager**：设计写"失败挂起等待"，hermes 实际是**容量不足直接拒绝（不排队）**（async_delegation.py L678-694，`max_async_children` 默认 3）。采用 hermes 语义。
2. **LeaseManager + ChildLease**：hermes 无 `ChildLease` 类，是 credential_pool.py 的软 ref-count 租约（L1926）。Aether 简化为每 session `AtomicInteger` 计数器 + CAS，不引入 ChildLease 类。
3. **① SubagentState**：hermes 无 `TIMED_OUT` 状态（超时仅作 `wait()` 的 `timed_out` 标志）。Aether 按设计 §6.3① 保留 `TIMED_OUT` 终态（stale 检测用），语义等同 hermes 的"超时即终止"。

---

## 文件结构地图

**domain 新增**（`aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/`）：
| 文件 | 职责 | 对齐 |
|---|---|---|
| `SubagentState.java` | 8 态枚举 | subagent_lifecycle.py L37 |
| `DelegationTask.java` | 异步委派输入 record | SubagentLaunchRequest L49 |
| `DelegationRecord.java` | 持久化实体 @Data | async_delegations 表 L142 |
| `DelegationCompletion.java` | completion 事件 record | _push_completion_event L780 |
| `SpawnGate.java` | 暂停+深度闸门 @Component | delegate_tool.py L153/L2795 |
| `CompletionBus.java` | 事件总线 @Component | process_registry.py L173/L344 |
| `LeaseManager.java` | 每 session 并发租约 @Component | async_delegation.py L678 + credential_pool L1926 |
| `DelegationBudget.java` | 预算配置 record | _apply_summary_budget L1897 |
| `ChildResultAggregator.java` | 子结果合并 @Component | _finalize_child_results L2616 |
| `SubagentLifecycleService.java` | 生命周期状态机+执行 @Component | subagent_lifecycle.py L186 |
| `AsyncDelegationStore.java` | 持久化端口接口 | async_delegations 表 L142 |
| `AsyncDelegationService.java` | 异步委派管线 @Service | async_delegation.py dispatch/recover/restore |

**domain 修改**：
| 文件 | 改动 |
|---|---|
| `SubAgentDelegationTool.java` | 构造器加可空 `SpawnGate`+`AsyncDelegationService`；`inputSchema` 加 `async`；`call()` 加 SpawnGate 闸门 + 异步分支 |
| `ChatModelNode.java`（armory/node） | @Resource 注入 `AsyncDelegationService`+`SpawnGate`，传给工具构造器 |

**infrastructure 新增**（`aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/deleg/`）：
| 文件 | 职责 |
|---|---|
| `PgAsyncDelegationStore.java` | PostgreSQL 实现 @Repository，JdbcTemplate+RowMapper |

**资源修改**：
| 文件 | 改动 |
|---|---|
| `aether-app/src/main/resources/schema.sql` | 加 `t_async_delegation` 表（CREATE TABLE IF NOT EXISTS） |
| `aether-app/src/main/resources/application-dev.yml` | 加 `aether.delegation.persistence: true` |

**测试**：
- domain：`aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/` — `SubagentStateTest` / `SpawnGateTest` / `CompletionBusTest` / `LeaseManagerTest` / `ChildResultAggregatorTest` / `SubagentLifecycleServiceTest` / `AsyncDelegationServiceTest` / `SubAgentDelegationToolAsyncTest`
- infrastructure：`aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/deleg/PgAsyncDelegationStoreTest.java`

**任务依赖顺序**：模型 → 独立组件（SpawnGate/CompletionBus/LeaseManager/Budget）→ LifecycleService → 持久化（Store+表+配置）→ AsyncDelegationService → 工具接线 → 回归。

---

### Task 1: 领域模型（SubagentState / DelegationTask / DelegationRecord）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentState.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationTask.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationRecord.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentStateTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubagentStateTest {

    @Test
    void hasAllDesignStates() {
        assertArrayEquals(new SubagentState[]{
                SubagentState.QUEUED, SubagentState.PENDING, SubagentState.RUNNING,
                SubagentState.COMPLETED, SubagentState.FAILED, SubagentState.CANCELLED,
                SubagentState.TIMED_OUT, SubagentState.INTERRUPTED
        }, SubagentState.values());
    }

    @Test
    void delegationTaskRejectsNullTaskAndSession() {
        assertThrows(NullPointerException.class,
                () -> new DelegationTask(null, List.of(), null, "u1", "s1", "a1"));
        assertThrows(NullPointerException.class,
                () -> new DelegationTask("task", List.of(), null, "u1", null, "a1"));
    }

    @Test
    void delegationTaskCopiesToolNames() {
        DelegationTask task = new DelegationTask("t", List.of("codexplorer"), "m", "u1", "s1", "a1");
        assertTrue(task.toolNames().contains("codexplorer"));
        DelegationTask noTools = new DelegationTask("t", null, "m", "u1", "s1", "a1");
        assertTrue(noTools.toolNames().isEmpty(), "null toolNames 应归一为空列表");
    }

    @Test
    void delegationRecordBuilderDefaultsStateToQueued() {
        DelegationRecord rec = DelegationRecord.builder()
                .id("ad-1").parentSessionId("s1").taskPayload("t").build();
        assertEquals("ad-1", rec.getId());
        assertEquals("s1", rec.getParentSessionId());
        assertFalse(rec.isCompletionDelivered(), "completionDelivered 默认 false");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SubagentStateTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（`cannot find symbol` SubagentState / DelegationTask / DelegationRecord）

- [ ] **Step 3: 最小实现**

`SubagentState.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

/**
 * 子Agent委派状态机 — 对齐 hermes subagent_lifecycle.py SubagentState（L37）。
 * <p>Aether 语义：QUEUED=已落库待执行；PENDING=已提交待 worker 接管；
 * RUNNING=执行中；COMPLETED/FAILED/CANCELLED/INTERRUPTED/TIMED_OUT 为终态。
 * 注：hermes 无 TIMED_OUT（超时仅作 wait 标志），Aether 按设计 §6.3① 引入。</p>
 */
public enum SubagentState {
    QUEUED, PENDING, RUNNING,
    COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED
}
```

`DelegationTask.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import java.util.List;
import java.util.Objects;

/**
 * 异步委派输入 — 对齐 hermes SubagentLaunchRequest（subagent_lifecycle.py L49，简化）。
 */
public record DelegationTask(
        String task,
        List<String> toolNames,
        String modelRef,
        String userId,
        String parentSessionId,
        String parentAgentId
) {
    public DelegationTask {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parentSessionId, "parentSessionId");
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }
}
```

`DelegationRecord.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 异步委派持久化记录 — 对齐 hermes async_delegations 表（async_delegation.py L142）。
 * <p>toolNames 以逗号拼接 TEXT 存储（字段内不含逗号，简化序列化）。</p>
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DelegationRecord {
    private String id;
    private String parentSessionId;
    private String parentAgentId;
    private String taskPayload;
    private List<String> toolNames;
    private SubagentState state;
    private int attemptCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastHeartbeatAt;
    private String resultSummary;
    private boolean completionDelivered;
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SubagentStateTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentState.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationTask.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationRecord.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentStateTest.java
git commit -m "feat(d1): 异步委派领域模型 SubagentState/DelegationTask/DelegationRecord"
```

---

### Task 2: SpawnGate（暂停 + 深度闸门）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SpawnGate.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SpawnGateTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnGateTest {

    @Test
    void enterAndExitTrackDepth() {
        SpawnGate gate = new SpawnGate(3);
        assertTrue(gate.enter());
        assertTrue(gate.enter());
        assertEquals(2, gate.currentDepth());
        gate.exit();
        assertEquals(1, gate.currentDepth());
    }

    @Test
    void pausedBlocksNewSpawns() {
        SpawnGate gate = new SpawnGate(3);
        gate.setSpawnPaused(true);
        assertTrue(gate.isSpawnPaused());
        assertFalse(gate.enter(), "暂停时应拒绝新委派");
    }

    @Test
    void maxDepthBlocksEnter() {
        SpawnGate gate = new SpawnGate(2);
        assertTrue(gate.enter());
        assertTrue(gate.enter());
        assertFalse(gate.enter(), "达到深度上限应拒绝");
        assertEquals(2, gate.currentDepth());
    }

    @Test
    void exitDoesNotGoBelowZero() {
        SpawnGate gate = new SpawnGate(3);
        gate.exit();
        assertEquals(0, gate.currentDepth());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SpawnGateTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol SpawnGate）

- [ ] **Step 3: 最小实现**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 委派 Spawn 闸门 — 对齐 hermes delegate_tool.py set_spawn_paused（L153）/ 深度计数（L2795）。
 * <p>setSpawnPaused 全局暂停新委派（只挡新 spawn，已运行子Agent不受影响，见 L2775）；
 * 深度用 ThreadLocal 追踪当前执行线程的委派嵌套深度（子=父+1，见 _build_child_agent L1234）。
 * 同步路径子Agent在调用线程 blocking 执行，深度正确累计；异步路径各池线程从 0 起（已知简化）。</p>
 */
@Slf4j
@Component
public class SpawnGate {

    public static final int DEFAULT_MAX_DEPTH = 3;

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final int maxDepth;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public SpawnGate() {
        this(DEFAULT_MAX_DEPTH);
    }

    public SpawnGate(int maxDepth) {
        this.maxDepth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
    }

    /** 全局暂停开关：true 时新委派一律拒绝。 */
    public void setSpawnPaused(boolean value) {
        paused.set(value);
    }

    public boolean isSpawnPaused() {
        return paused.get();
    }

    public int maxDepth() {
        return maxDepth;
    }

    /**
     * 尝试进入一层委派：暂停或已达深度上限则拒绝（返回 false），否则深度+1 返回 true。
     * 调用方须在 finally 中配对调用 {@link #exit()}。
     */
    public boolean enter() {
        if (paused.get()) {
            log.warn("SpawnGate: 委派已暂停，拒绝新 spawn");
            return false;
        }
        int d = depth.get();
        if (d >= maxDepth) {
            log.warn("SpawnGate: 委派深度已达上限 maxDepth={}", maxDepth);
            return false;
        }
        depth.set(d + 1);
        return true;
    }

    /** 退出当前委派深度（与 enter 成对，finally 中调用）。 */
    public void exit() {
        depth.set(Math.max(0, depth.get() - 1));
    }

    public int currentDepth() {
        return depth.get();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SpawnGateTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SpawnGate.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SpawnGateTest.java
git commit -m "feat(d1): SpawnGate 暂停开关 + 委派深度闸门"
```

---

### Task 3: CompletionBus + DelegationCompletion

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationCompletion.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/CompletionBus.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/CompletionBusTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CompletionBusTest {

    private static final DelegationCompletion COMPLETION = new DelegationCompletion(
            "ad-1", "s1", "a1", "task", SubagentState.COMPLETED, "ok", Instant.now());

    @Test
    void publishNotifiesSubscribers() {
        CompletionBus bus = new CompletionBus();
        AtomicReference<DelegationCompletion> received = new AtomicReference<>();
        bus.subscribe(received::set);
        bus.publish(COMPLETION);
        assertEquals("ad-1", received.get().delegationId());
        assertEquals(1, bus.pendingCount());
    }

    @Test
    void subscriberExceptionDoesNotBlockOthers() {
        CompletionBus bus = new CompletionBus();
        AtomicInteger reached = new AtomicInteger(0);
        bus.subscribe(c -> { throw new RuntimeException("boom"); });
        bus.subscribe(c -> reached.incrementAndGet());
        bus.publish(COMPLETION);
        assertEquals(1, reached.get(), "异常订阅者不应阻断其它订阅者");
    }

    @Test
    void subscribeFromPersistenceReplaysUndeliveredAndMarksDelivered() {
        DelegationRecord rec = DelegationRecord.builder()
                .id("ad-9").parentSessionId("s1").parentAgentId("a1")
                .taskPayload("task").state(SubagentState.COMPLETED).resultSummary("ok")
                .updatedAt(Instant.now()).build();
        AsyncDelegationStore store = new AsyncDelegationStore() {
            @Override public void save(DelegationRecord record) {}
            @Override public List<DelegationRecord> listBySession(String sessionId) { return List.of(); }
            @Override public List<DelegationRecord> findPendingStale(Instant staleBefore, int limit) { return List.of(); }
            @Override public List<DelegationRecord> findUndeliveredTerminal(int limit) { return List.of(rec); }
            @Override public void markTerminal(String id, SubagentState terminal, String resultSummary) {}
            @Override public void markQueuedForRetry(String id, int newAttemptCount) {}
            @Override public void updateHeartbeat(String id, Instant at) {}
            @Override public void markCompletionDelivered(String id) { deliveredCount.incrementAndGet(); }
        };
        CompletionBus bus = new CompletionBus();
        AtomicInteger published = new AtomicInteger(0);
        bus.subscribe(c -> published.incrementAndGet());

        int replayed = bus.subscribeFromPersistence(store);

        assertEquals(1, replayed);
        assertEquals(1, published.get(), "回灌事件应发布给订阅者");
        assertEquals(1, deliveredCount.get(), "投递后应标记 delivered 去重");
    }

    @Test
    void subscribeFromPersistenceSkipsNullStore() {
        CompletionBus bus = new CompletionBus();
        assertEquals(0, bus.subscribeFromPersistence(null));
    }

    // 匿名 store 的 deliveredCount 捕获
    private static final java.util.concurrent.atomic.AtomicInteger deliveredCount = new java.util.concurrent.atomic.AtomicInteger(0);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=CompletionBusTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol DelegationCompletion / CompletionBus / AsyncDelegationStore）

- [ ] **Step 3: 最小实现**

`DelegationCompletion.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import java.time.Instant;

/**
 * 委派完成事件 — 对齐 hermes async_delegation.py _push_completion_event（L780）。
 */
public record DelegationCompletion(
        String delegationId,
        String parentSessionId,
        String parentAgentId,
        String task,
        SubagentState status,
        String summary,
        Instant completedAt
) {}
```

`AsyncDelegationStore.java`（端口接口，本任务先定义签名，实现在 Task 7）:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import java.time.Instant;
import java.util.List;

/**
 * 异步委派持久化端口 — 对齐 hermes async_delegations 表（async_delegation.py L142）。
 * <p>实现位于 infrastructure（PgAsyncDelegationStore）。持久化未启用时实现为 null，
 * 调用方须空安全降级为内存态。</p>
 */
public interface AsyncDelegationStore {

    void save(DelegationRecord record);

    List<DelegationRecord> listBySession(String sessionId);

    /** 扫描非终态且 stale（updatedAt < staleBefore）的记录，供崩溃恢复。 */
    List<DelegationRecord> findPendingStale(Instant staleBefore, int limit);

    /** 扫描终态且 completion 未投递（completionDelivered=false）的记录，供启动回灌。 */
    List<DelegationRecord> findUndeliveredTerminal(int limit);

    /** 置终态 + 结果摘要（覆盖 markCompleted/markFailed/markInterrupted/markTimedOut）。 */
    void markTerminal(String id, SubagentState terminal, String resultSummary);

    /** 崩溃恢复重入队：置回 QUEUED 并递增 attemptCount。 */
    void markQueuedForRetry(String id, int newAttemptCount);

    void updateHeartbeat(String id, Instant at);

    /** completion 投递成功后标记 delivered（去重，对齐 hermes delivery_state L142）。 */
    void markCompletionDelivered(String id);
}
```

`CompletionBus.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 委派完成总线 — 对齐 hermes process_registry.completion_queue（L173）。
 * <p>hermes 用无界 queue.Queue + drain_notifications（L1235）；Aether 等价为
 * 无界 ConcurrentLinkedQueue + 订阅者回调（多消费者，无背压）。
 * subscribeFromPersistence 对齐 restore_undelivered_completions（L344）：
 * 回灌终态未投递 completion，投递后经 markCompletionDelivered 去重。</p>
 */
@Slf4j
@Component
public class CompletionBus {

    private final ConcurrentLinkedQueue<DelegationCompletion> queue = new ConcurrentLinkedQueue<>();
    private final List<Consumer<DelegationCompletion>> subscribers = new CopyOnWriteArrayList<>();

    /** 发布 completion：入队 + 通知当前订阅者（订阅者异常隔离，不阻断）。 */
    public void publish(DelegationCompletion completion) {
        queue.offer(completion);
        for (Consumer<DelegationCompletion> sub : subscribers) {
            try {
                sub.accept(completion);
            } catch (Exception e) {
                log.warn("CompletionBus 订阅者异常（已隔离）: {}", e.getMessage());
            }
        }
    }

    /** 注册订阅者。 */
    public void subscribe(Consumer<DelegationCompletion> subscriber) {
        subscribers.add(subscriber);
    }

    /**
     * 启动回灌：扫描 store 终态未投递记录 → 发布 → markCompletionDelivered（去重）。
     * @return 回灌条数
     */
    public int subscribeFromPersistence(AsyncDelegationStore store) {
        if (store == null) {
            return 0;
        }
        int replayed = 0;
        for (DelegationRecord rec : store.findUndeliveredTerminal(100)) {
            DelegationCompletion completion = new DelegationCompletion(
                    rec.getId(), rec.getParentSessionId(), rec.getParentAgentId(),
                    rec.getTaskPayload(), rec.getState(), rec.getResultSummary(), rec.getUpdatedAt());
            publish(completion);
            store.markCompletionDelivered(rec.getId());
            replayed++;
        }
        return replayed;
    }

    /** 未消费 completion 数（含已通知订阅者的，仅作统计）。 */
    public int pendingCount() {
        return queue.size();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=CompletionBusTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationCompletion.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/CompletionBus.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/AsyncDelegationStore.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/CompletionBusTest.java
git commit -m "feat(d1): CompletionBus 完成事件总线 + 启动回灌去重"
```

---

### Task 4: LeaseManager（每 session 并发租约）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/LeaseManager.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/LeaseManagerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LeaseManagerTest {

    @Test
    void acquiresUpToMaxPerSession() {
        LeaseManager mgr = new LeaseManager(3);
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertEquals(3, mgr.activeLeases("s1"));
    }

    @Test
    void rejectsBeyondMaxPerSession() {
        LeaseManager mgr = new LeaseManager(2);
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertFalse(mgr.acquireLease("s1"), "超出每 session 上限应拒绝（不排队，对齐 hermes L678）");
    }

    @Test
    void releaseFreesSlot() {
        LeaseManager mgr = new LeaseManager(2);
        mgr.acquireLease("s1");
        mgr.acquireLease("s1");
        mgr.releaseLease("s1");
        assertTrue(mgr.acquireLease("s1"), "释放后应重新获得槽位");
        assertEquals(2, mgr.activeLeases("s1"));
    }

    @Test
    void sessionsAreIndependent() {
        LeaseManager mgr = new LeaseManager(1);
        mgr.acquireLease("s1");
        assertTrue(mgr.acquireLease("s2"), "不同 session 互不影响");
        assertFalse(mgr.acquireLease("s1"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=LeaseManagerTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol LeaseManager）

- [ ] **Step 3: 最小实现**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 委派并发租约 — 对齐 hermes async_delegation.py 容量检查（L678-694，max_async_children 默认 3）
 * + credential_pool.py acquire_lease（L1926，软租约/ref-count）。
 * <p>每 session 并发上限：同 session 活跃委派数 ≥ maxAsyncChildren 时新委派直接 rejected
 * （不排队，对齐 hermes L680-683）。Aether 无 hermes 的 ChildLease 类（软租约），
 * 简化为每 session AtomicInteger + CAS。acquireLease 成功者须在完成时 releaseLease。</p>
 */
@Slf4j
@Component
public class LeaseManager {

    public static final int DEFAULT_MAX_ASYNC_CHILDREN = 3;

    private final int maxAsyncChildren;
    private final ConcurrentMap<String, AtomicInteger> sessionCounters = new ConcurrentHashMap<>();

    public LeaseManager() {
        this(DEFAULT_MAX_ASYNC_CHILDREN);
    }

    public LeaseManager(int maxAsyncChildren) {
        this.maxAsyncChildren = maxAsyncChildren > 0 ? maxAsyncChildren : DEFAULT_MAX_ASYNC_CHILDREN;
    }

    /** 尝试为 session 获取一个并发槽位；已达上限返回 false（拒绝，不排队）。 */
    public boolean acquireLease(String sessionId) {
        AtomicInteger counter = sessionCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        while (true) {
            int cur = counter.get();
            if (cur >= maxAsyncChildren) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /** 释放 session 的一个槽位（幂等）。 */
    public void releaseLease(String sessionId) {
        AtomicInteger counter = sessionCounters.get(sessionId);
        if (counter == null) {
            return;
        }
        counter.updateAndGet(c -> Math.max(0, c - 1));
    }

    public int activeLeases(String sessionId) {
        AtomicInteger counter = sessionCounters.get(sessionId);
        return counter == null ? 0 : counter.get();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=LeaseManagerTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/LeaseManager.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/LeaseManagerTest.java
git commit -m "feat(d1): LeaseManager 每 session 并发租约（容量不足拒绝）"
```

---

### Task 5: DelegationBudget + ChildResultAggregator

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationBudget.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ChildResultAggregator.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/ChildResultAggregatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChildResultAggregatorTest {

    private final ChildResultAggregator aggregator = new ChildResultAggregator();

    private static ResultRefiner.SubAgentResult res(String summary, Map<String, Integer> stats) {
        return new ResultRefiner.SubAgentResult("成功", summary, stats);
    }

    @Test
    void mergesSummariesAndToolStats() {
        var merged = aggregator.merge(List.of(
                res("[子任务A] 结论A", Map.of("code", 2)),
                res("[子任务B] 结论B", Map.of("search", 1))),
                DelegationBudget.defaults());
        assertEquals("成功", merged.status());
        assertTrue(merged.summary().contains("结论A"));
        assertTrue(merged.summary().contains("结论B"));
        assertEquals(2, merged.toolStats().get("code"));
        assertEquals(1, merged.toolStats().get("search"));
    }

    @Test
    void truncatesToBudget() {
        var merged = aggregator.merge(List.of(
                res("A".repeat(50), Map.of())), new DelegationBudget(8000, 20));
        assertTrue(merged.summary().length() <= 23, "应截断到 maxSummaryChars(20)+省略号");
        assertTrue(merged.summary().endsWith("..."));
    }

    @Test
    void disabledBudgetSkipsTruncation() {
        var merged = aggregator.merge(List.of(
                res("A".repeat(50), Map.of())), new DelegationBudget(8000, 0));
        assertEquals(50, merged.summary().length());
    }

    @Test
    void emptyListReturnsUnfinished() {
        var merged = aggregator.merge(List.of(), DelegationBudget.defaults());
        assertEquals("未完成", merged.status());
    }

    @Test
    void skipsBlankSummaries() {
        var merged = aggregator.merge(List.of(
                res("   ", Map.of()), res("[结论]", Map.of())), DelegationBudget.defaults());
        assertFalse(merged.summary().contains("---"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=ChildResultAggregatorTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol DelegationBudget / ChildResultAggregator）

- [ ] **Step 3: 最小实现**

`DelegationBudget.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

/**
 * 委派预算配置 — 对齐 hermes delegate_tool.py DEFAULT_MAX_SUMMARY_CHARS（L716，24000）。
 * <p>maxSummaryChars=0 禁用截断；maxTokensPerChild 为每子Agent上下文预算
 * （本批仅记录，实际每子Agent TokenBudget 由 DefaultAgentFactory 创建，见设计 §6.3⑦）。</p>
 */
public record DelegationBudget(int maxTokensPerChild, int maxSummaryChars) {

    public static final int DEFAULT_MAX_TOKENS_PER_CHILD = 8000;
    /** 对齐 ResultRefiner.MAX_RESULT_LENGTH=2000（Aether 摘要既有上限）。 */
    public static final int DEFAULT_MAX_SUMMARY_CHARS = 2000;

    public DelegationBudget {
        maxTokensPerChild = maxTokensPerChild > 0 ? maxTokensPerChild : DEFAULT_MAX_TOKENS_PER_CHILD;
        maxSummaryChars = maxSummaryChars >= 0 ? maxSummaryChars : DEFAULT_MAX_SUMMARY_CHARS;
    }

    public static DelegationBudget defaults() {
        return new DelegationBudget(DEFAULT_MAX_TOKENS_PER_CHILD, DEFAULT_MAX_SUMMARY_CHARS);
    }
}
```

`ChildResultAggregator.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子结果合并 — 对齐 hermes delegate_tool.py _apply_summary_budget（L1897）/ _finalize_child_results（L2616）。
 * <p>将多个子结果合并为单个摘要：连接各 summary（去空）、按 maxSummaryChars 截断、合并 toolStats。
 * 注：hermes _finalize_child_results 实际逐 result 应用契约而非聚合；Aether 简化为一处合并（设计 §6.3⑦）。</p>
 */
@Slf4j
@Component
public class ChildResultAggregator {

    public ResultRefiner.SubAgentResult merge(List<ResultRefiner.SubAgentResult> children, DelegationBudget budget) {
        if (children == null || children.isEmpty()) {
            return new ResultRefiner.SubAgentResult("未完成", "[无子结果]", Map.of());
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> toolStats = new LinkedHashMap<>();
        for (ResultRefiner.SubAgentResult child : children) {
            if (child.summary() != null && !child.summary().isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n---\n");
                }
                sb.append(child.summary());
            }
            if (child.toolStats() != null) {
                child.toolStats().forEach((k, v) -> toolStats.merge(k, v, Integer::sum));
            }
        }
        String merged = sb.toString();
        if (budget.maxSummaryChars() > 0 && merged.length() > budget.maxSummaryChars()) {
            merged = merged.substring(0, budget.maxSummaryChars()) + "...";
        }
        return new ResultRefiner.SubAgentResult("成功", merged, toolStats);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=ChildResultAggregatorTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/DelegationBudget.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/ChildResultAggregator.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/ChildResultAggregatorTest.java
git commit -m "feat(d1): DelegationBudget + ChildResultAggregator 预算截断与结果合并"
```

---

### Task 6: SubagentLifecycleService（内存生命周期状态机 + 执行）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentRuntime.java`（包私有运行时）
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleServiceTest.java`

**前置知识（实现者必读）**：`SubAgentOrchestrator.dispatch` 展示了子Agent执行模式：
```java
config = boundary.createIsolatedConfig(parentSessionId, task, toolNames, modelRef, taskId, null);
Agent subAgent = agentFactory.create(config);
Map<String, Object> subMetadata = new java.util.HashMap<>();
subMetadata.put("subAgentContext", Boolean.TRUE);
subMetadata.put("taskId", taskId);
RuntimeContext ctx = new RuntimeContext(userId, config.getName(), null, null, task, subMetadata, null);
subAgent.execute(ctx)
    .takeUntil((Predicate<RuntimeEvent>) event -> execConfig.getCancelToken().isCancelled())
    .blockingForEach(event -> {
        if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) collected.add(TurnMessage.assistant(event.getText()));
        if (event.getType() == RuntimeEvent.EventType.toolResult) collected.add(TurnMessage.toolResult(event.getToolCallId(), event.getToolName(), event.getToolOutput()));
    });
result = refiner.refine(task, collected);
```

- [ ] **Step 1: 写失败测试**

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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubagentLifecycleServiceTest {

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
        service = new SubagentLifecycleService(boundary, agentFactory, refiner, executor);
        config = mock(AgentConfig.class);
        when(config.getName()).thenReturn("sub-agent");
        when(config.getCancelToken()).thenReturn(new CancelToken());
        when(boundary.createIsolatedConfig(any(), any(), any(), any(), any(), any())).thenReturn(config);
    }

    private static RuntimeEvent textEvent(String text) {
        return RuntimeEvent.text(text); // RuntimeEvent 静态工厂（builder 亦可）
    }

    private DelegationTask task() {
        return new DelegationTask("分析代码", List.of("code"), null, "u1", "s1", "parent");
    }

    @Test
    void launchRunsToCompletion() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(textEvent("结论")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of("code", 1)));

        String id = service.launch(task());
        assertTrue(service.wait(id, 5000), "wait 应在超时前完成");

        assertEquals(SubagentState.COMPLETED, service.status(id).orElseThrow());
        assertTrue(service.result(id).isPresent());
        assertEquals("成功", service.result(id).orElseThrow().status());
    }

    @Test
    void cancelSetsCancelledAndTerminates() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        // 永不完成的事件流：cancel token 生效前一直阻塞
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("未完成", "[无文本结论]", Map.of()));

        String id = service.launch(task());
        assertTrue(service.cancel(id), "cancel 应被接受");

        // cancel token 已取消 → takeUntil 终止 → worker 收尾
        assertTrue(service.wait(id, 5000));
        assertEquals(SubagentState.CANCELLED, service.status(id).orElseThrow());
    }

    @Test
    void cancelAfterCompletionIsNoOp() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class)))
                .thenReturn(Flowable.just(textEvent("结论")));
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of()));

        String id = service.launch(task());
        assertTrue(service.wait(id, 5000));
        assertFalse(service.cancel(id), "终态后 cancel 应拒绝（对齐 hermes L291 already_terminal）");
    }

    @Test
    void detectStaleMarksTimedOut() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());
        when(refiner.refine(any(), any())).thenReturn(
                new ResultRefiner.SubAgentResult("未完成", "[无文本结论]", Map.of()));

        String id = service.launch(task());
        // 等 worker 进入 RUNNING
        Thread.sleep(200);
        assertEquals(SubagentState.RUNNING, service.status(id).orElseThrow());

        service.rewindHeartbeatForTest(id, Duration.ofMinutes(31));
        List<String> stale = service.detectStale(Duration.ofMinutes(30));
        assertTrue(stale.contains(id), "RUNNING 且心跳过期应判 TIMED_OUT");
        assertEquals(SubagentState.TIMED_OUT, service.status(id).orElseThrow());
    }

    @Test
    void waitTimesOutOnNeverCompletingAgent() throws Exception {
        Agent agent = mock(Agent.class);
        when(agentFactory.create(config)).thenReturn(agent);
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.never());

        String id = service.launch(task());
        assertFalse(service.wait(id, 100), "超时应返回 false（对齐 hermes timed_out 标志）");
        service.cancel(id); // 清理
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SubagentLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol SubagentLifecycleService）

`SubagentRuntime.java`（包私有）:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时子Agent登记 — 对齐 hermes subagent_lifecycle.py _Record（L149/L401）。
 * 状态用 AtomicReference 保证 cancel/complete/heartbeat 并发下的原子转换守卫。
 */
final class SubagentRuntime {

    private final String id;
    private final DelegationTask task;
    private final AgentConfig config;
    private final Agent agent;
    private final CancelToken cancelToken;
    private final Instant createdAt;
    private final AtomicReference<SubagentState> state;
    private volatile ResultRefiner.SubAgentResult result;
    private volatile Instant lastHeartbeatAt;
    private volatile CompletableFuture<ResultRefiner.SubAgentResult> future;

    SubagentRuntime(String id, DelegationTask task, AgentConfig config, Agent agent, CancelToken cancelToken) {
        this.id = id;
        this.task = task;
        this.config = config;
        this.agent = agent;
        this.cancelToken = cancelToken;
        this.createdAt = Instant.now();
        this.lastHeartbeatAt = Instant.now();
        this.state = new AtomicReference<>(SubagentState.QUEUED);
    }

    String id() { return id; }
    DelegationTask task() { return task; }
    AgentConfig config() { return config; }
    Agent agent() { return agent; }
    CancelToken cancelToken() { return cancelToken; }
    Instant createdAt() { return createdAt; }
    SubagentState state() { return state.get(); }
    ResultRefiner.SubAgentResult result() { return result; }
    Instant lastHeartbeatAt() { return lastHeartbeatAt; }
    CompletableFuture<ResultRefiner.SubAgentResult> future() { return future; }

    void setFuture(CompletableFuture<ResultRefiner.SubAgentResult> future) { this.future = future; }

    void setResult(ResultRefiner.SubAgentResult result) { this.result = result; }

    void heartbeat() { this.lastHeartbeatAt = Instant.now(); }

    void rewindHeartbeat(Instant past) { this.lastHeartbeatAt = past; }

    /** QUEUED→RUNNING（worker 开始；若已被 cancel 则 CAS 失败返回 false）。 */
    boolean toRunning() {
        return state.compareAndSet(SubagentState.QUEUED, SubagentState.RUNNING);
    }

    /** 非终态 → CANCELLED（对齐 hermes cancel L291 + _run L402 的守卫）。 */
    boolean toCancelled() {
        if (isTerminal(state.get())) {
            return false;
        }
        state.set(SubagentState.CANCELLED);
        cancelToken.cancel();
        return true;
    }

    /** 仅 RUNNING → 指定终态（终态竞争：已 CANCELLED/TIMED_OUT 则 CAS 失败，不覆盖）。 */
    boolean tryTerminal(SubagentState target) {
        return state.compareAndSet(SubagentState.RUNNING, target);
    }

    static boolean isTerminal(SubagentState s) {
        return s == SubagentState.COMPLETED || s == SubagentState.FAILED
                || s == SubagentState.CANCELLED || s == SubagentState.TIMED_OUT
                || s == SubagentState.INTERRUPTED;
    }
}
```

`SubagentLifecycleService.java`:
```java
package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子Agent生命周期状态机 + 异步执行 — 对齐 hermes subagent_lifecycle.py（L186）。
 * <p>launch：QUEUED→RUNNING，内部线程池执行子Agent（boundary/factory/refiner 复用），
 * 持有 SubagentRuntime（agent + cancelToken + future + 心跳）。状态转换经 AtomicReference
 * CAS 守卫（对齐 hermes RLock + 状态检查）。cancel→CANCELLED；stale（心跳冻结）→TIMED_OUT。
 * 注：hermes 无 TIMED_OUT 状态、超时仅作 wait 标志；Aether 按设计 §6.3③ 引入 stale→TIMED_OUT。</p>
 */
@Slf4j
@Component
public class SubagentLifecycleService {

    public static final int DEFAULT_POOL_SIZE = 5;

    private final SubAgentBoundary boundary;
    private final DefaultAgentFactory agentFactory;
    private final ResultRefiner refiner;
    private final ExecutorService executor;
    private final Map<String, SubagentRuntime> runtimes = new ConcurrentHashMap<>();

    public SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory, ResultRefiner refiner) {
        this(boundary, agentFactory, refiner, Executors.newFixedThreadPool(DEFAULT_POOL_SIZE));
    }

    /** 测试注入线程池。 */
    SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                             ResultRefiner refiner, ExecutorService executor) {
        this.boundary = boundary;
        this.agentFactory = agentFactory;
        this.refiner = refiner;
        this.executor = executor;
    }

    /**
     * 启动异步子Agent：登记 QUEUED → 线程池执行（RUNNING）→ 终态。返回 id。
     * @param id 由调用方生成的持久化委派 id（如 ad-xxx）
     * @param task 委派输入
     * @return 完成 future（completed 后经 status(id) 读取终态）
     */
    public CompletableFuture<ResultRefiner.SubAgentResult> launch(String id, DelegationTask task) {
        String taskId = "t" + Integer.toHexString(Math.abs(task.task().hashCode())).substring(0, 6);
        AgentConfig config = boundary.createIsolatedConfig(
                task.parentSessionId(), task.task(), task.toolNames(), task.modelRef(), taskId, null);
        Agent agent = agentFactory.create(config);
        SubagentRuntime rt = new SubagentRuntime(id, task, config, agent, config.getCancelToken());
        runtimes.put(id, rt);

        CompletableFuture<ResultRefiner.SubAgentResult> future =
                CompletableFuture.supplyAsync(() -> runAgent(rt), executor);
        rt.setFuture(future);
        future.whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("SubagentLifecycleService: 子Agent执行异常 id={}", id, ex);
            }
        });
        return future;
    }

    private ResultRefiner.SubAgentResult runAgent(SubagentRuntime rt) {
        if (!rt.toRunning()) {
            // 启动前已被 cancel → 直接终态返回（对齐 hermes _run L402：非 CANCEL_REQUESTED 才置 RUNNING）
            return rt.result();
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subAgentContext", Boolean.TRUE);
            metadata.put("taskId", rt.id());
            RuntimeContext ctx = new RuntimeContext(rt.task().userId(), rt.config().getName(),
                    null, null, rt.task().task(), metadata, null);
            List<TurnMessage> collected = new ArrayList<>();
            rt.agent().execute(ctx)
                    .takeUntil((io.reactivex.rxjava3.functions.Predicate<RuntimeEvent>) ev ->
                            rt.cancelToken().isCancelled())
                    .blockingForEach(event -> {
                        rt.heartbeat(); // 每次事件刷新心跳（进度信号）
                        if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                            collected.add(TurnMessage.assistant(event.getText()));
                        }
                        if (event.getType() == RuntimeEvent.EventType.toolResult) {
                            collected.add(TurnMessage.toolResult(event.getToolCallId(),
                                    event.getToolName(), event.getToolOutput()));
                        }
                    });
            ResultRefiner.SubAgentResult result = refiner.refine(rt.task().task(), collected);
            rt.setResult(result);
            rt.tryTerminal(SubagentState.COMPLETED); // 已被 cancel/stale 置终态则 CAS 失败，保持原终态
            return result;
        } catch (Exception e) {
            log.error("SubagentLifecycleService: 子Agent执行异常 id={} task={}",
                    rt.id(), truncate(rt.task().task(), 120), e);
            ResultRefiner.SubAgentResult result = new ResultRefiner.SubAgentResult(
                    "失败", "[子任务异常: " + e.getMessage() + "]", Map.of());
            rt.setResult(result);
            rt.tryTerminal(SubagentState.FAILED);
            return result;
        }
    }

    /** 阻塞等待完成，超时返回 false（对齐 hermes wait timed_out 标志，L270）。 */
    public boolean wait(String id, long timeoutMs) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || rt.future() == null) {
            return true;
        }
        try {
            rt.future().get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true; // 已完成（含异常）
        }
    }

    /** 取消：非终态 → CANCELLED + cancel token。返回是否接受（对齐 hermes cancel L291）。 */
    public boolean cancel(String id) {
        SubagentRuntime rt = runtimes.get(id);
        return rt != null && rt.toCancelled();
    }

    /** 查询状态；未知 id 返回 empty。 */
    public Optional<SubagentState> status(String id) {
        SubagentRuntime rt = runtimes.get(id);
        return rt == null ? Optional.empty() : Optional.of(rt.state());
    }

    /** 查询结果；未就绪/未知返回 empty。 */
    public Optional<ResultRefiner.SubAgentResult> result(String id) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || rt.result() == null) {
            return Optional.empty();
        }
        return Optional.of(rt.result());
    }

    /** 更新心跳（仅非终态有效）。 */
    public boolean heartbeat(String id) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || SubagentRuntime.isTerminal(rt.state())) {
            return false;
        }
        rt.heartbeat();
        return true;
    }

    /** 扫描 RUNNING 且心跳冻结超过 timeout 的委派 → TIMED_OUT + 取消 token。返回受影响 id 列表。 */
    public List<String> detectStale(Duration timeout) {
        List<String> stale = new ArrayList<>();
        Instant cutoff = Instant.now().minus(timeout);
        for (SubagentRuntime rt : runtimes.values()) {
            if (rt.state() == SubagentState.RUNNING && rt.lastHeartbeatAt().isBefore(cutoff)) {
                if (rt.tryTerminal(SubagentState.TIMED_OUT)) {
                    rt.cancelToken().cancel();
                    stale.add(rt.id());
                }
            }
        }
        return stale;
    }

    /** 非终态运行时 id 列表（供 interrupt 与 Batch 4 实时查询）。 */
    public List<String> activeIds() {
        List<String> ids = new ArrayList<>();
        for (SubagentRuntime rt : runtimes.values()) {
            if (!SubagentRuntime.isTerminal(rt.state())) {
                ids.add(rt.id());
            }
        }
        return ids;
    }

    /** 查询运行时的委派输入（供 interruptForSession 按 session 过滤）。 */
    public Optional<DelegationTask> taskOf(String id) {
        SubagentRuntime rt = runtimes.get(id);
        return rt == null ? Optional.empty() : Optional.of(rt.task());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        runtimes.values().forEach(rt -> rt.cancelToken().cancel());
    }

    /** 测试辅助：将某运行时的心跳拨回过去，模拟冻结。 */
    void rewindHeartbeatForTest(String id, Duration past) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt != null) {
            rt.rewindHeartbeat(Instant.now().minus(past));
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SubagentLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentRuntime.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleService.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubagentLifecycleServiceTest.java
git commit -m "feat(d1): SubagentLifecycleService 生命周期状态机 + 异步执行"
```

---

### Task 7: AsyncDelegationStore 端口实现（PgAsyncDelegationStore + schema.sql + 配置）

**Files:**
- Create: `aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/deleg/PgAsyncDelegationStore.java`
- Modify: `aether-app/src/main/resources/schema.sql`（追加表）
- Modify: `aether-app/src/main/resources/application-dev.yml`（追加属性）
- Test: `aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/deleg/PgAsyncDelegationStoreTest.java`

- [ ] **Step 1: 修改 schema.sql 与 application-dev.yml**

在 `aether-app/src/main/resources/schema.sql` **末尾追加**（保持 `CREATE TABLE IF NOT EXISTS` 幂等，随 `spring.sql.init.mode: always` 启动加载）：
```sql
-- ============================================================
-- D1: 异步委派表（hermes async_delegations 对齐）
-- ============================================================
CREATE TABLE IF NOT EXISTS t_async_delegation (
    id                   VARCHAR(64)  PRIMARY KEY,
    parent_session_id    VARCHAR(128) NOT NULL,
    parent_agent_id      VARCHAR(128),
    task_payload         TEXT         NOT NULL,
    tool_names           TEXT,
    state                VARCHAR(32)  NOT NULL,
    attempt_count        INTEGER      NOT NULL DEFAULT 1,
    result_summary       TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_heartbeat_at    TIMESTAMP,
    completion_delivered BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_async_deleg_state
    ON t_async_delegation(state, updated_at);
CREATE INDEX IF NOT EXISTS idx_async_deleg_session
    ON t_async_delegation(parent_session_id);
```

在 `aether-app/src/main/resources/application-dev.yml` 的 `aether:` 段追加（跟随 PgSessionRepository 的 `aether.session.persistence` 激活模式）：
```yaml
  # ====== D1: 异步委派持久化（PostgreSQL，激活 PgAsyncDelegationStore）======
  delegation:
    persistence: true
```

- [ ] **Step 2: 写失败测试**

```java
package cn.zcj.aether.infrastructure.deleg;

import cn.zcj.aether.domain.agent.service.subagent.AsyncDelegationStore;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgAsyncDelegationStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PgAsyncDelegationStore store = new PgAsyncDelegationStore(jdbc);

    private static DelegationRecord queued(String id) {
        Instant now = Instant.now();
        return DelegationRecord.builder()
                .id(id).parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of("code", "search")).state(SubagentState.QUEUED)
                .attemptCount(1).createdAt(now).updatedAt(now).build();
    }

    @Test
    void saveBindsColumns() {
        store.save(queued("ad-1"));
        verify(jdbc).update(anyString(),
                eq("ad-1"), eq("s1"), eq("a1"), eq("task"), eq("code,search"),
                eq("QUEUED"), eq(1), any(Timestamp.class), any(Timestamp.class));
    }

    @Test
    void markTerminalUpdatesStateAndSummary() {
        store.markTerminal("ad-1", SubagentState.COMPLETED, "[结论]");
        verify(jdbc).update(anyString(), eq("COMPLETED"), eq("[结论]"), any(Timestamp.class), eq("ad-1"));
    }

    @Test
    void markQueuedForRetryIncrementsAttempt() {
        store.markQueuedForRetry("ad-1", 2);
        verify(jdbc).update(anyString(), eq("QUEUED"), eq(2), any(Timestamp.class), eq("ad-1"));
    }

    @Test
    void updateHeartbeatBindsTimestamp() {
        Instant at = Instant.ofEpochMilli(1_700_000_000_000L);
        store.updateHeartbeat("ad-1", at);
        verify(jdbc).update(anyString(), any(Timestamp.class), eq("ad-1"));
    }

    @Test
    void markCompletionDeliveredBindsId() {
        store.markCompletionDelivered("ad-1");
        verify(jdbc).update(anyString(), eq("ad-1"));
    }

    @Test
    void listBySessionQueriesAndMaps() throws Exception {
        DelegationRecord expected = queued("ad-1");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("s1"))).thenReturn(List.of(expected));
        List<DelegationRecord> result = store.listBySession("s1");
        assertEquals(1, result.size());
        assertEquals("ad-1", result.get(0).getId());
    }

    @Test
    void findPendingStaleAndUndeliveredQuery() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class), eq(50)))
                .thenReturn(List.of());
        assertTrue(store.findPendingStale(Instant.now().minusSeconds(60), 50).isEmpty());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(100))).thenReturn(List.of());
        assertTrue(store.findUndeliveredTerminal(100).isEmpty());
    }

    @Test
    void rowMapperMapsToolNamesSplit() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("ad-1");
        when(rs.getString("parent_session_id")).thenReturn("s1");
        when(rs.getString("parent_agent_id")).thenReturn("a1");
        when(rs.getString("task_payload")).thenReturn("task");
        when(rs.getString("tool_names")).thenReturn("code,search");
        when(rs.getString("state")).thenReturn("RUNNING");
        when(rs.getInt("attempt_count")).thenReturn(2);
        when(rs.getBoolean("completion_delivered")).thenReturn(false);

        DelegationRecord rec = store.rowMapperForTest().mapRow(rs, 0);
        assertEquals("ad-1", rec.getId());
        assertEquals(2, rec.getToolNames().size());
        assertEquals(SubagentState.RUNNING, rec.getState());
        assertEquals(2, rec.getAttemptCount());
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-infrastructure -am test -Dtest=PgAsyncDelegationStoreTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol PgAsyncDelegationStore）

- [ ] **Step 4: 最小实现**

```java
package cn.zcj.aether.infrastructure.deleg;

import cn.zcj.aether.domain.agent.service.subagent.AsyncDelegationStore;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL 异步委派仓储 — 对齐 hermes async_delegations 表（async_delegation.py L142）。
 * <p>激活条件：PostgreSQL 驱动可用 + aether.delegation.persistence=true（跟随 PgSessionRepository 模式）。
 * 测试无真实 DB：Mockito mock JdbcTemplate（跟随 PgvectorVectorStoreSqlTest 基建）。</p>
 */
@Slf4j
@Repository
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnProperty(name = "aether.delegation.persistence", havingValue = "true", matchIfMissing = false)
public class PgAsyncDelegationStore implements AsyncDelegationStore {

    private final JdbcTemplate jdbcTemplate;

    public PgAsyncDelegationStore(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
        log.info("PgAsyncDelegationStore 已初始化");
    }

    /** 测试注入 mock JdbcTemplate。 */
    PgAsyncDelegationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO t_async_delegation
            (id, parent_session_id, parent_agent_id, task_payload, tool_names,
             state, attempt_count, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String MARK_TERMINAL_SQL = """
        UPDATE t_async_delegation
        SET state = ?, result_summary = ?, updated_at = ?, last_heartbeat_at = ?
        WHERE id = ?
        """;

    private static final String MARK_QUEUED_SQL = """
        UPDATE t_async_delegation
        SET state = 'QUEUED', attempt_count = ?, updated_at = ?
        WHERE id = ?
        """;

    private static final String UPDATE_HEARTBEAT_SQL =
        "UPDATE t_async_delegation SET last_heartbeat_at = ?, updated_at = ? WHERE id = ?";

    private static final String MARK_DELIVERED_SQL =
        "UPDATE t_async_delegation SET completion_delivered = TRUE WHERE id = ?";

    private static final String LIST_BY_SESSION_SQL = """
        SELECT id, parent_session_id, parent_agent_id, task_payload, tool_names,
               state, attempt_count, result_summary, created_at, updated_at,
               last_heartbeat_at, completion_delivered
        FROM t_async_delegation WHERE parent_session_id = ? ORDER BY created_at
        """;

    private static final String FIND_PENDING_STALE_SQL = """
        SELECT id, parent_session_id, parent_agent_id, task_payload, tool_names,
               state, attempt_count, result_summary, created_at, updated_at,
               last_heartbeat_at, completion_delivered
        FROM t_async_delegation
        WHERE state IN ('QUEUED','PENDING','RUNNING') AND updated_at < ?
        ORDER BY updated_at LIMIT ?
        """;

    private static final String FIND_UNDELIVERED_SQL = """
        SELECT id, parent_session_id, parent_agent_id, task_payload, tool_names,
               state, attempt_count, result_summary, created_at, updated_at,
               last_heartbeat_at, completion_delivered
        FROM t_async_delegation
        WHERE state NOT IN ('QUEUED','PENDING','RUNNING') AND completion_delivered = FALSE
        ORDER BY updated_at LIMIT ?
        """;

    @Override
    public void save(DelegationRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                record.getId(),
                record.getParentSessionId(),
                record.getParentAgentId(),
                record.getTaskPayload(),
                joinToolNames(record.getToolNames()),
                record.getState() != null ? record.getState().name() : "QUEUED",
                record.getAttemptCount(),
                toTs(record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now()),
                toTs(record.getUpdatedAt() != null ? record.getUpdatedAt() : Instant.now()));
    }

    @Override
    public List<DelegationRecord> listBySession(String sessionId) {
        return jdbcTemplate.query(LIST_BY_SESSION_SQL, DelegationRowMapper.INSTANCE, sessionId);
    }

    @Override
    public List<DelegationRecord> findPendingStale(Instant staleBefore, int limit) {
        return jdbcTemplate.query(FIND_PENDING_STALE_SQL, DelegationRowMapper.INSTANCE,
                Timestamp.from(staleBefore), limit);
    }

    @Override
    public List<DelegationRecord> findUndeliveredTerminal(int limit) {
        return jdbcTemplate.query(FIND_UNDELIVERED_SQL, DelegationRowMapper.INSTANCE, limit);
    }

    @Override
    public void markTerminal(String id, SubagentState terminal, String resultSummary) {
        Instant now = Instant.now();
        jdbcTemplate.update(MARK_TERMINAL_SQL,
                terminal != null ? terminal.name() : "FAILED",
                resultSummary, toTs(now), toTs(now), id);
    }

    @Override
    public void markQueuedForRetry(String id, int newAttemptCount) {
        jdbcTemplate.update(MARK_QUEUED_SQL, newAttemptCount, toTs(Instant.now()), id);
    }

    @Override
    public void updateHeartbeat(String id, Instant at) {
        jdbcTemplate.update(UPDATE_HEARTBEAT_SQL, toTs(at), toTs(Instant.now()), id);
    }

    @Override
    public void markCompletionDelivered(String id) {
        jdbcTemplate.update(MARK_DELIVERED_SQL, id);
    }

    private static String joinToolNames(List<String> toolNames) {
        return toolNames == null || toolNames.isEmpty() ? null
                : toolNames.stream().collect(Collectors.joining(","));
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** 测试暴露 RowMapper。 */
    RowMapper<DelegationRecord> rowMapperForTest() {
        return DelegationRowMapper.INSTANCE;
    }

    private static final class DelegationRowMapper implements RowMapper<DelegationRecord> {
        private static final DelegationRowMapper INSTANCE = new DelegationRowMapper();

        @Override
        public DelegationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            String toolNames = rs.getString("tool_names");
            return DelegationRecord.builder()
                    .id(rs.getString("id"))
                    .parentSessionId(rs.getString("parent_session_id"))
                    .parentAgentId(rs.getString("parent_agent_id"))
                    .taskPayload(rs.getString("task_payload"))
                    .toolNames(toolNames == null || toolNames.isBlank()
                            ? List.of()
                            : Arrays.asList(toolNames.split(",")))
                    .state(rs.getString("state") != null
                            ? SubagentState.valueOf(rs.getString("state")) : null)
                    .attemptCount(rs.getInt("attempt_count"))
                    .resultSummary(rs.getString("result_summary"))
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : null)
                    .updatedAt(rs.getTimestamp("updated_at") != null
                            ? rs.getTimestamp("updated_at").toInstant() : null)
                    .lastHeartbeatAt(rs.getTimestamp("last_heartbeat_at") != null
                            ? rs.getTimestamp("last_heartbeat_at").toInstant() : null)
                    .completionDelivered(rs.getBoolean("completion_delivered"))
                    .build();
        }
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-infrastructure -am test -Dtest=PgAsyncDelegationStoreTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/deleg/PgAsyncDelegationStore.java \
        aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/deleg/PgAsyncDelegationStoreTest.java \
        aether-app/src/main/resources/schema.sql \
        aether-app/src/main/resources/application-dev.yml
git commit -m "feat(d1): PgAsyncDelegationStore 持久化 + t_async_delegation 表 + 配置"
```

---

### Task 8: AsyncDelegationService（异步委派管线）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/AsyncDelegationService.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/AsyncDelegationServiceTest.java`

**前置知识**：`SubAgentDelegationTool` 里父子上下文来源：`parentSessionId = context.sessionId()`，`userId = context.userId()`；子Agent 通过 `orchestrator.dispatch(task, toolNames, null, modelRef, userId, parentSessionId)` 走同步路径。

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AsyncDelegationServiceTest {

    private SubagentLifecycleService lifecycle;
    private CompletionBus completionBus;
    private LeaseManager leaseManager;
    private SpawnGate spawnGate;
    private AsyncDelegationStore store;
    private ExecutorService executor;
    private AsyncDelegationService service;

    @BeforeEach
    void setUp() {
        lifecycle = mock(SubagentLifecycleService.class);
        completionBus = mock(CompletionBus.class);
        leaseManager = mock(LeaseManager.class);
        spawnGate = mock(SpawnGate.class);
        store = mock(AsyncDelegationStore.class);
        executor = Executors.newCachedThreadPool();
        service = new AsyncDelegationService(lifecycle, completionBus, leaseManager, spawnGate, store, executor);
        when(spawnGate.enter()).thenReturn(true);
        when(leaseManager.acquireLease(any())).thenReturn(true);
    }

    private static DelegationTask task() {
        return new DelegationTask("分析代码", List.of("code"), null, "u1", "s1", "parent");
    }

    @Test
    void dispatchPersistsQueuedAndLaunches() {
        when(lifecycle.launch(any(), any())).thenReturn(new CompletableFuture<>());
        String id = service.dispatch(task());
        assertNotNull(id);
        assertTrue(id.startsWith("ad-"));
        verify(store).save(argThat(rec ->
                rec.getState() == SubagentState.QUEUED && rec.getAttemptCount() == 1));
        verify(lifecycle).launch(eq(id), eq(task()));
        verify(leaseManager).acquireLease("s1");
    }

    @Test
    void dispatchRejectsWhenLeaseFull() {
        when(leaseManager.acquireLease("s1")).thenReturn(false);
        assertNull(service.dispatch(task()));
        verify(store, never()).save(any());
        verify(lifecycle, never()).launch(any(), any());
    }

    @Test
    void completionPersistedAndPublishedOnSuccess() {
        ResultRefiner.SubAgentResult result =
                new ResultRefiner.SubAgentResult("成功", "[结论]", java.util.Map.of());
        CompletableFuture<ResultRefiner.SubAgentResult> future = CompletableFuture.completedFuture(result);
        when(lifecycle.launch(any(), any())).thenReturn(future);
        when(lifecycle.status(any())).thenReturn(java.util.Optional.of(SubagentState.COMPLETED));
        when(lifecycle.result(any())).thenReturn(java.util.Optional.of(result));

        service.dispatch(task());

        verify(store).markTerminal(argThat(id -> id.startsWith("ad-")),
                eq(SubagentState.COMPLETED), eq("[结论]"));
        verify(completionBus).publish(argThat(c -> c.status() == SubagentState.COMPLETED));
        verify(store).markCompletionDelivered(argThat(id -> id.startsWith("ad-")));
        verify(leaseManager).releaseLease("s1");
    }

    @Test
    void recoverAbandonedRequeuesWithIncrementedAttempt() {
        DelegationRecord stale = DelegationRecord.builder()
                .id("ad-5").parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of("code")).state(SubagentState.RUNNING).attemptCount(1).build();
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of(stale));
        when(leaseManager.acquireLease("s1")).thenReturn(true);
        when(lifecycle.launch(any(), any())).thenReturn(new CompletableFuture<>());

        int recovered = service.recoverAbandoned();

        assertEquals(1, recovered);
        verify(store).markQueuedForRetry("ad-5", 2);
        verify(lifecycle).launch(eq("ad-5"), any());
    }

    @Test
    void recoverAbandonedCapsAtMaxAttempts() {
        DelegationRecord stale = DelegationRecord.builder()
                .id("ad-6").parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of()).state(SubagentState.RUNNING).attemptCount(8).build();
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of(stale));

        int recovered = service.recoverAbandoned();

        assertEquals(0, recovered);
        verify(store).markTerminal("ad-6", SubagentState.FAILED, "[超出最大尝试次数 8]");
        verify(lifecycle, never()).launch(any(), any());
    }

    @Test
    void recoverAbandonedIsIdempotent() {
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of());
        service.recoverAbandoned();
        service.recoverAbandoned();
        verify(store, times(1)).findPendingStale(any(), eq(100));
    }

    @Test
    void restoreUndeliveredReplaysCompletions() {
        when(store.findUndeliveredTerminal(100)).thenReturn(List.of());
        assertEquals(0, service.restoreUndelivered());
        verify(completionBus).subscribeFromPersistence(store);
    }

    @Test
    void interruptForSessionCancelsMatchingActive() {
        when(lifecycle.activeIds()).thenReturn(List.of("ad-1", "ad-2"));
        when(lifecycle.taskOf("ad-1")).thenReturn(java.util.Optional.of(
                new DelegationTask("a", List.of(), null, "u", "s1", "p")));
        when(lifecycle.taskOf("ad-2")).thenReturn(java.util.Optional.of(
                new DelegationTask("b", List.of(), null, "u", "s2", "p")));
        when(lifecycle.cancel("ad-1")).thenReturn(true);

        int count = service.interruptForSession("s1", "test");

        assertEquals(1, count);
        verify(lifecycle).cancel("ad-1");
        verify(lifecycle, never()).cancel("ad-2");
    }

    @Test
    void listBySessionDelegatesToStore() {
        when(store.listBySession("s1")).thenReturn(List.of(
                DelegationRecord.builder().id("ad-1").parentSessionId("s1").taskPayload("t").build()));
        assertEquals(1, service.listBySession("s1").size());
        verify(store).listBySession("s1");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=AsyncDelegationServiceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol AsyncDelegationService）

- [ ] **Step 3: 最小实现**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步委派管线 — 对齐 hermes async_delegation.py（dispatch L594 / recover L293 / restore L344 / interrupt L1331/L1360）。
 * <p>dispatch：LeaseManager 容量闸 → 落库 QUEUED → SubagentLifecycleService 执行 →
 * 完成时 markTerminal + 推 completion 到 CompletionBus + markCompletionDelivered。
 * 启动恢复：restoreUndelivered 回灌终态未投递 completion；recoverAbandoned 重入队 stale
 * （attemptCount++，上限 8，对齐 hermes _MAX_DELIVERY_ATTEMPTS=8）。
 * store 为 null 时内存降级（aether.delegation.persistence=false）。</p>
 */
@Slf4j
@Service
public class AsyncDelegationService {

    /** 对齐 hermes async_delegation.py _MAX_DELIVERY_ATTEMPTS=8（L85）。 */
    static final int MAX_ATTEMPTS = 8;
    static final Duration STALE_TIMEOUT = Duration.ofMinutes(30);

    private final SubagentLifecycleService lifecycle;
    private final CompletionBus completionBus;
    private final LeaseManager leaseManager;
    private final SpawnGate spawnGate;
    private final AsyncDelegationStore store;
    private final ExecutorService executor;
    private final AtomicBoolean recoveryDone = new AtomicBoolean(false);

    public AsyncDelegationService(SubagentLifecycleService lifecycle,
                                  CompletionBus completionBus,
                                  LeaseManager leaseManager,
                                  SpawnGate spawnGate,
                                  AsyncDelegationStore store) {
        this(lifecycle, completionBus, leaseManager, spawnGate, store,
                Executors.newCachedThreadPool());
    }

    /** 测试注入线程池。 */
    AsyncDelegationService(SubagentLifecycleService lifecycle,
                           CompletionBus completionBus,
                           LeaseManager leaseManager,
                           SpawnGate spawnGate,
                           AsyncDelegationStore store,
                           ExecutorService executor) {
        this.lifecycle = lifecycle;
        this.completionBus = completionBus;
        this.leaseManager = leaseManager;
        this.spawnGate = spawnGate;
        this.store = store;
        this.executor = executor;
    }

    @PostConstruct
    public void start() {
        // 顺序：先回灌完成事件，再恢复 abandoned（对齐 hermes restore_undelivered L356 先调 recover）
        restoreUndelivered();
        recoverAbandoned();
    }

    /**
     * 提交异步委派。成功返回 delegationId；被并发上限拒绝返回 null（不排队，对齐 hermes L680）。
     * SpawnGate 闸门由调用方（SubAgentDelegationTool）负责，本方法不重复加闸。
     */
    public String dispatch(DelegationTask task) {
        if (!leaseManager.acquireLease(task.parentSessionId())) {
            log.warn("AsyncDelegationService: session={} 并发委派达上限，拒绝 task={}",
                    task.parentSessionId(), truncate(task.task(), 120));
            return null;
        }
        String id = "ad-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            DelegationRecord rec = DelegationRecord.builder()
                    .id(id)
                    .parentSessionId(task.parentSessionId())
                    .parentAgentId(task.parentAgentId())
                    .taskPayload(task.task())
                    .toolNames(task.toolNames())
                    .state(SubagentState.QUEUED)
                    .attemptCount(1)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            if (store != null) {
                store.save(rec);
            }
            runDelegation(id, task);
            return id;
        } catch (Exception e) {
            leaseManager.releaseLease(task.parentSessionId());
            if (store != null) {
                store.markTerminal(id, SubagentState.FAILED, "[启动失败: " + e.getMessage() + "]");
            }
            log.error("AsyncDelegationService: dispatch 启动失败 id={}", id, e);
            return null;
        }
    }

    private void runDelegation(String id, DelegationTask task) {
        try {
            CompletableFuture<ResultRefiner.SubAgentResult> future = lifecycle.launch(id, task);
            future.whenComplete((result, err) -> finalizeDelegation(id, task, err));
        } catch (Exception e) {
            log.error("AsyncDelegationService: 启动委派执行失败 id={}", id, e);
            finalizeDelegation(id, task, e);
        }
    }

    private void finalizeDelegation(String id, DelegationTask task, Throwable err) {
        try {
            SubagentState terminal = lifecycle.status(id).orElse(SubagentState.FAILED);
            if (err != null && terminal != SubagentState.CANCELLED
                    && terminal != SubagentState.INTERRUPTED) {
                terminal = SubagentState.FAILED;
            }
            String summary = lifecycle.result(id)
                    .map(ResultRefiner.SubAgentResult::summary).orElse(null);
            if (store != null) {
                store.markTerminal(id, terminal, summary);
            }
            completionBus.publish(new DelegationCompletion(
                    id, task.parentSessionId(), task.parentAgentId(), task.task(),
                    terminal, summary, Instant.now()));
            if (store != null) {
                store.markCompletionDelivered(id);
            }
        } catch (Exception e) {
            log.error("AsyncDelegationService: 收尾委派失败 id={}", id, e);
        } finally {
            leaseManager.releaseLease(task.parentSessionId());
        }
    }

    /**
     * 启动恢复 abandoned：扫描非终态 stale 记录 → 重入队（attemptCount++，幂等仅一次）。
     * 超出 MAX_ATTEMPTS 则置 FAILED（对齐 hermes delivery_attempts>=8 → dropped 终态，L426）。
     * @return 重入队条数
     */
    public int recoverAbandoned() {
        if (!recoveryDone.compareAndSet(false, true)) {
            return 0;
        }
        if (store == null) {
            return 0;
        }
        Instant staleBefore = Instant.now().minus(STALE_TIMEOUT);
        List<DelegationRecord> stale = store.findPendingStale(staleBefore, 100);
        int recovered = 0;
        for (DelegationRecord rec : stale) {
            if (rec.getAttemptCount() >= MAX_ATTEMPTS) {
                store.markTerminal(rec.getId(), SubagentState.FAILED,
                        "[超出最大尝试次数 " + MAX_ATTEMPTS + "]");
                continue;
            }
            if (!leaseManager.acquireLease(rec.getParentSessionId())) {
                continue; // 容量不足，留给后续恢复
            }
            store.markQueuedForRetry(rec.getId(), rec.getAttemptCount() + 1);
            DelegationTask task = new DelegationTask(
                    rec.getTaskPayload(), rec.getToolNames(), null,
                    "system", rec.getParentSessionId(), rec.getParentAgentId());
            runDelegation(rec.getId(), task);
            recovered++;
        }
        log.info("AsyncDelegationService: recoverAbandoned 恢复 {} 条", recovered);
        return recovered;
    }

    /**
     * 启动回灌：COMPLETED/FAILED/... 未投递 completion → 推送到 CompletionBus。
     * @return 回灌条数
     */
    public int restoreUndelivered() {
        if (store == null) {
            return 0;
        }
        int replayed = completionBus.subscribeFromPersistence(store);
        log.info("AsyncDelegationService: restoreUndelivered 回灌 {} 条", replayed);
        return replayed;
    }

    /** 中断指定 session 的所有活跃委派（对齐 interrupt_for_session L1360）。 */
    public int interruptForSession(String sessionId, String reason) {
        int count = 0;
        for (String id : lifecycle.activeIds()) {
            Optional<DelegationTask> t = lifecycle.taskOf(id);
            if (t.isPresent() && sessionId.equals(t.get().parentSessionId())
                    && lifecycle.cancel(id)) {
                count++;
            }
        }
        log.info("AsyncDelegationService: interruptForSession session={} reason={} 中断 {} 个",
                sessionId, reason, count);
        return count;
    }

    /** 中断所有活跃委派（对齐 interrupt_all L1331）。 */
    public int interruptAll(String reason) {
        int count = 0;
        for (String id : lifecycle.activeIds()) {
            if (lifecycle.cancel(id)) {
                count++;
            }
        }
        log.info("AsyncDelegationService: interruptAll reason={} 中断 {} 个", reason, count);
        return count;
    }

    /** 查询 session 的委派记录（持久化启用时）。 */
    public List<DelegationRecord> listBySession(String sessionId) {
        if (store == null) {
            return List.of();
        }
        return store.listBySession(sessionId);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=AsyncDelegationServiceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/AsyncDelegationService.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/AsyncDelegationServiceTest.java
git commit -m "feat(d1): AsyncDelegationService 异步委派管线 + 崩溃恢复 + completion 回灌"
```

---

### Task 9: SubAgentDelegationTool 异步模式 + ChatModelNode 接线

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubAgentDelegationTool.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/node/ChatModelNode.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubAgentDelegationToolAsyncTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubAgentDelegationToolAsyncTest {

    private final SubAgentOrchestrator orchestrator = mock(SubAgentOrchestrator.class);
    private final AsyncDelegationService asyncService = mock(AsyncDelegationService.class);
    private final SpawnGate spawnGate = mock(SpawnGate.class);
    private final ToolContext ctx = new ToolContext("u1", "s1", "call-1");

    private Map<String, Object> input(boolean async) {
        return Map.of("task", "分析", "async", async);
    }

    @Test
    void asyncModeReturnsDelegationIdImmediately() {
        when(spawnGate.enter()).thenReturn(true);
        when(asyncService.dispatch(any())).thenReturn("ad-abc123");
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, asyncService);

        ToolResult result = tool.call(input(true), ctx);

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("ad-abc123"));
        verify(asyncService).dispatch(any(DelegationTask.class));
        verify(spawnGate).exit();
        verify(orchestrator, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void asyncModeRejectedByLeaseReturnsError() {
        when(spawnGate.enter()).thenReturn(true);
        when(asyncService.dispatch(any())).thenReturn(null);
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, asyncService);

        ToolResult result = tool.call(input(true), ctx);

        assertTrue(result.isError());
        verify(spawnGate).exit();
    }

    @Test
    void spawnGatePausedBlocksDelegation() {
        when(spawnGate.enter()).thenReturn(false);
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, asyncService);

        ToolResult result = tool.call(input(true), ctx);

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("拒绝"));
        verify(asyncService, never()).dispatch(any());
        verify(orchestrator, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void syncPathUnchangedWhenAsyncServiceAbsent() {
        when(spawnGate.enter()).thenReturn(true);
        when(orchestrator.dispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of()));
        // 构造器兼容：仅 orchestrator（模拟未接线异步能力）
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, null);

        ToolResult result = tool.call(input(true), ctx);

        assertFalse(result.isError());
        verify(orchestrator).dispatch("分析", List.of(), null, null, "u1", "s1");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SubAgentDelegationToolAsyncTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（cannot find symbol 3 参构造器 / async 分支）

- [ ] **Step 3: 修改实现**

修改 `SubAgentDelegationTool.java`（手术式，不动既有同步逻辑；新增构造器 + `async` 参数 + 分支）：

```java
// 顶部 import 增加
import java.util.UUID;

// 字段增加
private final SpawnGate spawnGate;                 // 可空（未接线时仅同步路径）
private final AsyncDelegationService asyncService; // 可空（未接线时仅同步路径）

// 既有构造器保留（兼容）
public SubAgentDelegationTool(SubAgentOrchestrator orchestrator) {
    this(orchestrator, null, null);
}

// 新增构造器
public SubAgentDelegationTool(SubAgentOrchestrator orchestrator, SpawnGate spawnGate, AsyncDelegationService asyncService) {
    this.orchestrator = orchestrator;
    this.spawnGate = spawnGate;
    this.asyncService = asyncService;
}
```

`inputSchema()` 的 `properties` 中追加 `async` 键：
```java
"async", Map.of(
        "type", "boolean",
        "description", "可选。true 时异步委派（立即返回 delegationId+QUEUED，不等待子Agent完成）；默认 false 同步等待。")
```

`call()` 方法开头（在既有参数解析之后、`log.info` 之前）插入 SpawnGate 闸门 + 异步分支，其余同步逻辑保持原样：
```java
// D1: SpawnGate 闸门（暂停/深度上限 → 拒绝，对齐 hermes delegate_tool.py L2775）
if (spawnGate != null && !spawnGate.enter()) {
    return ToolResult.error(context.toolCallId(), name(),
            "[委派被拒绝: spawn 已暂停或达到深度上限]",
            ToolResult.ErrorType.EXECUTION);
}
try {
    // D1: 异步委派模式（方案 a：平行新能力，同步路径保留）
    if (asyncService != null && Boolean.TRUE.equals(input.get("async"))) {
        String delegationId = asyncService.dispatch(new DelegationTask(
                task, toolNames, modelRef, userId, parentSessionId, null));
        if (delegationId == null) {
            return ToolResult.error(context.toolCallId(), name(),
                    "[委派被拒绝: session 并发委派达上限]",
                    ToolResult.ErrorType.EXECUTION);
        }
        log.info("SubAgentDelegationTool: 异步委派已提交 id={}", delegationId);
        return ToolResult.success(context.toolCallId(), name(),
                "[委派已异步提交] delegationId=" + delegationId + " state=QUEUED");
    }

    log.info("SubAgentDelegationTool: 发起委派 task='{}' tools={} model={} session={}",
            task.length() > 80 ? task.substring(0, 77) + "..." : task,
            toolNames, modelRef, parentSessionId);
    // ... 既有同步 dispatch 逻辑不变 ...
    return ...; // 原 return 保留
} finally {
    if (spawnGate != null) {
        spawnGate.exit();
    }
}
```

> 实现说明：原 `call()` 有多处 `return`（校验失败、成功、失败），需将原方法体整体包入 `try { ... } finally { spawnGate.exit(); }`，确保闸门配对。**逐行保留原逻辑，只改缩进与新增分支。**

修改 `ChatModelNode.java`（L337-346 装配处）：
```java
// 类内 @Resource 字段增加：
@Resource
private cn.zcj.aether.domain.agent.service.subagent.AsyncDelegationService asyncDelegationService;
@Resource
private cn.zcj.aether.domain.agent.service.subagent.SpawnGate spawnGate;

// L339-342 装配改为：
try {
    var delegationTool = new cn.zcj.aether.domain.agent.service.subagent
            .SubAgentDelegationTool(subAgentOrchestrator, spawnGate, asyncDelegationService);
    toolRegistry.register(delegationTool);
    log.info("委派工具已注册: {} (delegate_to_subagent)", delegationTool.name());
} catch (Exception e) {
    log.warn("委派工具注册失败（不阻断启动）: {}", e.getMessage());
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dtest=SubAgentDelegationToolAsyncTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd D:\code\Agents-framework\aether
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/subagent/SubAgentDelegationTool.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/node/ChatModelNode.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/subagent/SubAgentDelegationToolAsyncTest.java
git commit -m "feat(d1): SubAgentDelegationTool 异步模式 + SpawnGate 闸门 + ChatModelNode 接线"
```

---

### Task 10: 全量回归

**Files:**
- 无新代码；仅验证。

- [ ] **Step 1: domain 全量测试**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-domain -am test -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS（含新增 8 个测试类 + Batch 1/2 既有测试不回归）

- [ ] **Step 2: infrastructure 全量测试**

Run: `cd D:\code\Agents-framework\aether && mvn -pl aether-infrastructure -am test -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 确认 schema.sql 未被既有测试破坏**

Run: `cd D:\code\Agents-framework\aether && git diff --stat aether-app/src/main/resources/schema.sql`
Expected: 仅追加 t_async_delegation 段

- [ ] **Step 4: 确认 hermes 只读约束**

Run: `cd D:\code\Agents-framework && git -C hermes-agent-main status --porcelain 2>/dev/null || echo "hermes-agent-main 非 git 仓库或未改动"`
Expected: 无改动（仅检查，不修改）

- [ ] **Step 5: 提交（如有回归修复则随修复提交；无则跳过）**

```bash
cd D:\code\Agents-framework\aether
git add -u aether-domain aether-infrastructure aether-app/src/main/resources
git commit -m "chore(d1): Batch 3 回归验证通过" 2>/dev/null || echo "无改动可提交"
```

---

## 自审记录（writing-plans self-review）

**1. Spec（设计 §6.3）覆盖：**
- ① SubagentState 8 态 → Task 1
- ② DelegationRecord → Task 1
- ③ SubagentLifecycleService（launch/wait/cancel/result/heartbeat/stale→TIMED_OUT）→ Task 6
- ④ AsyncDelegationStore 端口 + PgAsyncDelegationStore + AsyncDelegationService（dispatch/dispatchBatch/recoverAbandoned/restoreUndelivered/interruptForSession/interruptAll/listBySession）→ Task 7/8。**注：dispatchBatch 未单独实现**——hermes 的 batch 语义是"一个 async 槽跑整个 fan-out"（L849），Aether 批量由上层 GraphExecutor 并行调用 dispatch 即可，YAGNI 裁剪（已在 AsyncDelegationService javadoc 说明）。
- ⑤ CompletionBus → Task 3
- ⑥ LeaseManager → Task 4（删 ChildLease 类，hermes 无此抽象）
- ⑦ DelegationBudget + ChildResultAggregator → Task 5（maxTokensPerChild 仅记录不强制，标注）
- ⑧ SpawnGate → Task 2
- 集成（工具 async 选项 + ChatModelNode 接线）→ Task 9

**2. 占位符扫描：** 无 TBD/TODO；所有步骤含完整代码（RuntimeEvent 构造已核实为静态工厂 `RuntimeEvent.text()`，无留白）。

**3. 类型一致性：**
- `AsyncDelegationStore` 接口签名（Task 3 定义）与 `PgAsyncDelegationStore`（Task 7）一致
- `SubagentLifecycleService.launch(String, DelegationTask) → CompletableFuture<SubAgentResult>` 在 Task 6/8 一致使用
- `AsyncDelegationService` 构造器（Task 8）5 参 + 6 参（测试）一致
- `SubAgentDelegationTool` 3 参构造器（orchestrator, spawnGate, asyncService）在 Task 9 定义与测试一致
- `CompletionBus.subscribeFromPersistence(AsyncDelegationStore) → int` 在 Task 3/8 一致

**4. 已知限制（如实记录）：**
- `SubAgentDelegationTool.call` 原方法体需整体包入 try/finally（闸门配对），属必要改动
- `Task 6` 测试用 `Flowable.never()` 模拟永不完成（cancel/stale 场景），`wait(100)` 返回 false 验证超时标志
- infrastructure 测试 mock JdbcTemplate，未覆盖真实 SQL 语法（无 DB 基建，YAGNI 保留）

---

## 执行方式

**Plan complete and saved to `docs/superpowers/plans/2026-08-12-aether-orchestration-batch3-d1-multiagent.md`。两种执行方式：**

1. **Subagent-Driven（推荐）** — 每任务派遣全新子Agent实现 + spec 审查 + 代码质量审查，快速迭代
2. **Inline Execution** — 本会话内用 executing-plans 分批执行，带检查点

（子任务执行者需先读取 `SubAgentOrchestrator.java` 与 `RuntimeEvent.java` 确认执行模式与事件构造，再动 Task 6。）

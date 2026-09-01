# Aether 三个 P0 架构缺陷修复方案（可落地 · 可验证）

> 文档类型：架构修复方案（含完整代码与配置，落地执行版）
> 基线：`D:\code\Agents-framework\aether` master @ 2026-08-17（348 commits）
> 范围：仅三项 P0 缺陷 —— ① 无界线程池与裸线程；② 双重试嵌套放大；③ 默认无持久化
> 依据：`docs/aether-backend-architecture-refactoring.md`（2026-08-13 诊断版）的 P0-1/P0-2/P0-3，本文为其实施细化；所有 file:line 均已在本基线复核

---

## 0. 总览

| # | 缺陷 | 根因（复核证据） | 修复策略 | 优先级 |
|---|---|---|---|---|
| F1 | 无界线程池与裸线程 | `GraphExecutor.java:85` `Executors.newCachedThreadPool()`；`:727` `new Thread()`；另 4 处裸线程（StaleDelegationScanner/MemoryEmbeddingBackfillRunner/RateLimitFilter）+ 3 处隐式 commonPool | **统一线程资源管理**：新增 `AetherExecutorRegistry` 集中创建命名有界池，全部消费点注入；删除业务代码直接 `new Thread` | P0-1 |
| F2 | 双重试嵌套放大 | `ModelInvoker.java:40,227`（MAX_RETRIES=3 + `Thread.sleep` 退避）+ `ChatModelNode.java:514` 包装 `ResilientChatModelExecutor`（maxAttempts=3 退避循环）→ 同步路径最坏 4×3=12 次下游调用 | **重试单一所有权**：`ResilientChatModelExecutor` 为唯一语义级重试/回退所有者；`ModelInvoker.callWithStream` 退化为单次调用 + 双通道超时；移除 `isRetryable` 旧分类 | P0-2 |
| F3 | 默认无持久化 | ① `PgSessionRepository.java:28`/`RedisSessionRepository.java:25` `matchIfMissing=false` → 默认无 Bean；② `schema.sql` 缺 `aether_session` DDL（仅存于 `docs/postgresql_schema.sql`，不自动执行）；③ `ChatService.java:90-91,145-149` `required=false` + `exceptionally` 吞异常 → 静默降级 | **默认安全持久化**：schema.sql 补 DDL；Pg 仓储 `matchIfMissing=true` 默认启用；写失败显式告警 + 指标，不再静默 | P0-3 |

实施顺序建议：**F1 → F2 → F3**，各自独立提交（可单独 revert）。

---

## 1. 缺陷一：无界线程池与裸线程

### 1.1 现状与根因（全量清单，已复核）

| 位置 | 现状 | 风险 |
|---|---|---|
| `aether-domain/.../executor/GraphExecutor.java:85` | `Executors.newCachedThreadPool()` parallelPool | 并发图/GRAPHFLOW 下线程无上限 → OOM / 线程耗尽整进程崩溃 |
| `aether-domain/.../executor/GraphExecutor.java:727` | `new Thread(() -> {...})`（GRAPHFLOW 每个批次节点裸起线程） | 同上，且不受任何池管控、无命名 |
| `aether-domain/.../executor/GraphExecutor.java:292` | `parallelPool.submit(...)`（PARALLEL 边） | 依赖无界池 |
| `aether-domain/.../subagent/StaleDelegationScanner.java:45` | `new Thread(r, "stale-delegation-scan")` | 裸线程，无生命周期管理 |
| `aether-domain/.../memory/core/MemoryEmbeddingBackfillRunner.java:33` | `new Thread(r, "memory-backfill")` | 裸线程 |
| `aether-trigger/.../filter/RateLimitFilter.java:94` | `new Thread(r, "rate-limit-cleanup")` | 裸线程（每个实例建清理线程） |
| `aether-infrastructure/.../repository/PgvectorVectorStore.java:72` | `Executors.newFixedThreadPool(4)`（无界队列） | 队列无界，突发写入堆积 |
| `aether-infrastructure/.../repository/PgSessionRepository.java:73,102` / `RedisSessionRepository.java:37,69` | `CompletableFuture.runAsync(...)`（未指定 executor → ForkJoinPool.commonPool） | 阻塞型 DB IO 占用全局共享池 |
| `aether-domain/.../memory/MemoryStore.java:233+` / `RecallFlow.java:47,66,73` / `DefaultMemoryFacade.java:47` | `CompletableFuture.runAsync/supplyAsync`（commonPool） | 同上（P1 项，可选） |
| `aether-domain/.../observability/BackgroundReviewer.java:73-80` / `GraphExecutionRecorder.java:68` / `ToolExecutor.java:57-69` | 内部自建 `ThreadPoolExecutor`（有界，参数各异） | 参数不统一、无法统一治理（可迁移） |

> 参考模板：`ToolExecutor.java:49-69` 已实现正确的有界池（4/16/queue200/CallerRuns/命名 daemon 线程），本次将其参数统一收编进注册表。

### 1.2 目标设计：统一线程资源管理（`AetherExecutorRegistry`）

**原则**：
1. 全部线程池由一个配置类集中定义，前缀 `aether.thread-pools.*`，每池可独立调参；
2. 所有池有界（核心线程 + 有界队列 + 拒绝策略），杜绝 `newCachedThreadPool`；
3. 业务代码禁止 `new Thread`；后台扫描/清理类改用注入的池或 Spring `@Scheduled`；
4. 消费点**字段级安全默认**（直 `new` 的测试也能跑），Spring 注入覆盖；
5. 池 Bean 注册 `destroyMethod="shutdown"`，JVM 优雅停机不丢任务。

**默认参数表**（基准：2C4G 单实例；生产可按 `-Xmx` 与流量调）：

| 池（Bean 名） | core | max | 队列类型/容量 | keepAlive | 拒绝策略 | 线程名 | 用途 |
|---|---|---|---|---|---|---|---|
| `graphPool` | 4 | 8 | `LinkedBlockingQueue` 200 | 60s | CallerRuns | `aether-graph-%d` | GraphExecutor PARALLEL + GRAPHFLOW 节点 |
| `toolPool` | 4 | 16 | 200 | 60s | CallerRuns | `aether-tool-%d` | 工具执行（迁移 ToolExecutor 参数） |
| `memoryIoPool` | 2 | 4 | 500 | 60s | CallerRuns | `aether-memory-%d` | 记忆/向量 DB IO（PgvectorVectorStore、MemoryStore 等） |
| `sessionPersistPool` | 2 | 4 | 1000 | 60s | CallerRuns | `aether-session-%d` | 会话持久化写（异步落库） |
| `delegationPool` | 2 | 5 | 500 | 60s | CallerRuns | `aether-delegation-%d` | 异步委派/租约扫描（max=5 对齐旧 `SubagentLifecycleService.DEFAULT_POOL_SIZE=5`，行为零变化） |
| `backgroundReviewPool` | 1 | 1 | 100 | 60s | Discard | `aether-bg-review-%d` | 后台自评审（丢得起，语义保持） |
| `graphTracePool` | 1 | 1 | 200 | 60s | Discard | `aether-trace-%d` | 图 trace 异步落盘（丢得起） |
| `scheduledPool` | 2 | 4 | 100 | 60s | CallerRuns | `aether-sched-%d` | 定时清理（RateLimit 窗口清理等） |

> 参数依据：图并发典型为"用户并发数 × 每图并行节点数"，单实例目标并发 8 节点/批次（队列 200 提供缓冲）；工具调用含 Python/HTTP 长耗时，16 上限防止线程饥饿；会话/记忆写为短事务，小池 + CallerRuns 反压即可；评审/trace 为可丢弃辅助链路，DiscardPolicy 保主链路。

### 1.3 关键代码

**① 配置属性类**（新增，`aether-app/src/main/java/cn/zcj/aether/config/AetherThreadPoolProperties.java`）：

```java
package cn.zcj.aether.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一线程池配置 —— 前缀 aether.thread-pools.*
 * 每个命名池均可独立覆盖；缺省值即下方 DEFAULT 表。
 */
@Data
@ConfigurationProperties(prefix = "aether.thread-pools")
public class AetherThreadPoolProperties {

    /** 默认参数（core/max/queue/keepAliveSec/namePrefix/policy） */
    private static final Map<String, PoolConfig> DEFAULT = Map.of(
            "graph",             new PoolConfig(4, 8,  200, 60, "aether-graph",      "CallerRunsPolicy"),
            "tool",              new PoolConfig(4, 16, 200, 60, "aether-tool",       "CallerRunsPolicy"),
            "memory-io",         new PoolConfig(2, 4,  500, 60, "aether-memory",     "CallerRunsPolicy"),
            "session-persist",   new PoolConfig(2, 4,  1000, 60, "aether-session",   "CallerRunsPolicy"),
            "delegation",        new PoolConfig(2, 5,  500, 60, "aether-delegation", "CallerRunsPolicy"),
            "background-review", new PoolConfig(1, 1,  100, 60, "aether-bg-review",  "DiscardPolicy"),
            "graph-trace",       new PoolConfig(1, 1,  200, 60, "aether-trace",      "DiscardPolicy"),
            "scheduled",         new PoolConfig(2, 4,  100, 60, "aether-sched",      "CallerRunsPolicy")
    );

    /** 允许覆盖：key 为池名，值为该池参数；未覆盖的池使用默认 */
    private Map<String, PoolConfig> pools = new HashMap<>();

    public PoolConfig resolve(String name) {
        PoolConfig c = pools.get(name);
        return c != null ? c : DEFAULT.get(name);
    }

    @Data
    public static class PoolConfig {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private long keepAliveSeconds;
        private String threadNamePrefix;
        private String rejectionPolicy; // AbortPolicy | DiscardPolicy | DiscardOldestPolicy | CallerRunsPolicy

        public PoolConfig() {}
        public PoolConfig(int core, int max, int queue, long keepAlive,
                          String prefix, String policy) {
            this.corePoolSize = core; this.maxPoolSize = max; this.queueCapacity = queue;
            this.keepAliveSeconds = keepAlive; this.threadNamePrefix = prefix; this.rejectionPolicy = policy;
        }
    }
}
```

**② 注册表**（新增，`aether-app/.../config/AetherExecutorRegistry.java`）：

```java
package cn.zcj.aether.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程资源管理：集中创建全部命名有界线程池。
 * - 消费方（domain/infrastructure）通过 @Resource(name=...) 注入，编译期零依赖；
 * - 所有池有界 + 命名 daemon 线程 + 优雅停机；
 * - 拒绝策略支持 Abort/Discard/DiscardOldest/CallerRuns。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AetherThreadPoolProperties.class)
public class AetherExecutorRegistry {

    @Bean(name = "graphPool", destroyMethod = "shutdown")
    public ExecutorService graphPool(AetherThreadPoolProperties p) {
        return build(p.resolve("graph"));
    }

    @Bean(name = "toolPool", destroyMethod = "shutdown")
    public ExecutorService toolPool(AetherThreadPoolProperties p) {
        return build(p.resolve("tool"));
    }

    @Bean(name = "memoryIoPool", destroyMethod = "shutdown")
    public ExecutorService memoryIoPool(AetherThreadPoolProperties p) {
        return build(p.resolve("memory-io"));
    }

    @Bean(name = "sessionPersistPool", destroyMethod = "shutdown")
    public ExecutorService sessionPersistPool(AetherThreadPoolProperties p) {
        return build(p.resolve("session-persist"));
    }

    @Bean(name = "delegationPool", destroyMethod = "shutdown")
    public ExecutorService delegationPool(AetherThreadPoolProperties p) {
        return build(p.resolve("delegation"));
    }

    @Bean(name = "backgroundReviewPool", destroyMethod = "shutdown")
    public ExecutorService backgroundReviewPool(AetherThreadPoolProperties p) {
        return build(p.resolve("background-review"));
    }

    @Bean(name = "graphTracePool", destroyMethod = "shutdown")
    public ExecutorService graphTracePool(AetherThreadPoolProperties p) {
        return build(p.resolve("graph-trace"));
    }

    @Bean(name = "scheduledPool", destroyMethod = "shutdown")
    public ScheduledExecutorService scheduledPool(AetherThreadPoolProperties p) {
        AetherThreadPoolProperties.PoolConfig c = p.resolve("scheduled");
        return new ScheduledThreadPoolExecutor(c.getCorePoolSize(),
                namedFactory(c.getThreadNamePrefix()), handler(c.getRejectionPolicy()));
    }

    private static ExecutorService build(AetherThreadPoolProperties.PoolConfig c) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                c.getCorePoolSize(), c.getMaxPoolSize(),
                c.getKeepAliveSeconds(), TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(c.getQueueCapacity()),
                namedFactory(c.getThreadNamePrefix()),
                handler(c.getRejectionPolicy()));
        pool.allowCoreThreadTimeOut(false);
        log.info("线程池就绪: name={}, core={}, max={}, queue={}, policy={}",
                c.getThreadNamePrefix(), c.getCorePoolSize(), c.getMaxPoolSize(),
                c.getQueueCapacity(), c.getRejectionPolicy());
        return pool;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static RejectedExecutionHandler handler(String policy) {
        return switch (policy) {
            case "DiscardPolicy" -> new ThreadPoolExecutor.DiscardPolicy();
            case "DiscardOldestPolicy" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            case "AbortPolicy" -> new ThreadPoolExecutor.AbortPolicy();
            default -> new ThreadPoolExecutor.CallerRunsPolicy();
        };
    }
}
```

**③ application.yml 配置段**（追加；不写则全部走默认参数）：

```yaml
aether:
  thread-pools:
    graph:
      core-pool-size: 4
      max-pool-size: 8
      queue-capacity: 200
      keep-alive-seconds: 60
      thread-name-prefix: aether-graph
      rejection-policy: CallerRunsPolicy
    # 其余池缺省即默认值，可按需覆盖
```

**④ 消费点改造（核心 diff 示意）**：

`GraphExecutor.java`：
```java
// 删除: private final ExecutorService parallelPool = Executors.newCachedThreadPool();
@javax.annotation.Resource(name = "graphPool")
private ExecutorService graphPool;   // Spring 注入；直 new 测试时为 null → 见下方兜底

// 字段级安全默认（保证无 Spring 上下文时也有界可用）：
private ExecutorService safeGraphPool() {
    return graphPool != null ? graphPool : new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200),
            r -> { Thread t = new Thread(r, "aether-graph-fallback"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());
}

// :292  PARALLEL 边
safeGraphPool().submit(() -> { ... });

// :727  GRAPHFLOW 并发批次：裸线程 → 池提交（latch 已保证批次同步，execute 即可）
safeGraphPool().execute(() -> {
    // 原 new Thread 的 Runnable 体原样搬入
});
```

`StaleDelegationScanner.java`、`MemoryEmbeddingBackfillRunner.java`：注入 `delegationPool` / `memoryIoPool`（同样字段级安全默认），`new Thread` 替换为 `pool.execute(...)`。`RateLimitFilter.java:94`：注入 `scheduledPool`，清理改为 `scheduledPool.scheduleAtFixedRate(...)`。

`PgvectorVectorStore.java:72`、`PgSessionRepository.java`、`RedisSessionRepository.java`：`CompletableFuture.runAsync(task, safeMemoryPool())` / `runAsync(task, safeSessionPool())`，显式传池，退出 commonPool。

`ToolExecutor.java:57-69`、`BackgroundReviewer.java:73-80`、`GraphExecutionRecorder.java:68`：删除内部池，注入 `toolPool` / `backgroundReviewPool` / `graphTracePool`（保留字段级默认兜底，兼容现有测试直 `new`）。

### 1.4 适用场景 / 风险 / 回滚

- **适用**：所有并发执行路径（图编排、工具、记忆 IO、会话写、后台任务）。单实例规模 2C4G~8C16G 均适用；多实例部署时各实例独立池（与 F1 的"单实例约束"配套，见架构重构方案 P1-3）。
- **风险**：
  - 拒绝策略为 CallerRuns 时，队列满会占用**调用线程**执行任务 → 图执行请求线程可能被拉长（反压生效，属预期，但需监控 `rejected`/`active` 指标）。
  - Discard 策略的评审/trace 链路在高峰会丢任务（预期语义，勿用于主链路）。
  - 兜底池与注入池并存：若某处注入名拼写错误会静默走兜底 → 需在启动日志断言 `graphPool` 非空（见验证）。
- **回滚**：该改动为纯新增 + 调用点替换，`git revert` 单提交即可；兜底池保证即使回滚不全也不出现无界池。

### 1.5 修改前后行为对比

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 并发 50 路 GRAPHFLOW | 线程数无上限，OOM/线程耗尽风险 | 线程收敛 ≤8（+CallerRuns 反压），超限请求由调用线程执行或优雅排队 |
| 裸 `new Thread` 数 | 4 处业务裸线程（无命名/无管控） | 0 处；全部进入命名有界池，可观测（jstack 可见 `aether-*` 前缀） |
| 会话/记忆异步写 | ForkJoinPool.commonPool 被阻塞型 DB IO 占用 | 独立有界 `aether-session-*`/`aether-memory-*` 池 |
| 线程可观测性 | 线程名杂乱（`pool-1-thread-*`） | 统一 `aether-{用途}-N`，jstack/Arthas 可定位 |

### 1.6 验证方法

1. **单元测试**（新增 `AetherExecutorRegistryTest`，纯 JDK 断言）：
   - 每个命名池：`core≤max`、队列容量 >0、`getQueue().remainingCapacity()==配置值`；
   - 提交 `core+queue+1` 个阻塞任务后第 N+1 个按策略处理（CallerRuns：由当前线程执行，`Thread.currentThread().getName()` 断言）。
2. **调用点测试**：`GraphExecutorTest` 增加"50 并发 GRAPHFLOW 批次"用例，断言 `((ThreadPoolExecutor) graphPool).getActiveCount() <= maxPoolSize` 且线程总数收敛（无 50+ 线程）。
3. **启动断言**：`ApplicationRunner` 打印各池参数（注册表已含日志），人工核对 `aether-graph` 等前缀出现于日志。
4. **压测**：`wrk -t8 -c50` 打并发 chat_stream，观察 `thread dump`：线程数 ≤ 配置上限，无 `pool-1-thread-*` 大量堆积；Prometheus `jvm_threads_live_threads` 收敛。

---

## 2. 缺陷二：双重试嵌套放大

### 2.1 现状：完整重试链（已复核）

```
ReActAgent.queryLoop (:251-265)
 └─ modelInvoker.callWithStreamCachedAsync(...).block(2min)   ← 调用级超时 120s（不重试）
    失败 → 回退 modelInvoker.callWithStreamCached(...)          ← 同步路径
        └─ ModelInvoker.callWithStream (:227)
             for (attempt = 0..3) {                             ← 外层重试：最多 4 次尝试
                 Thread.sleep(backoff)  1s→2s→4s→8s(×2 上限15s)
                 chatModel.stream(prompt).collectList().block() ← 无超时！
             }
             └─ ResilientChatModelExecutor.stream() → call() (:135-192)
                  while(true) {
                      currentChatModel.call(prompt)
                      失败 → TurnRetryState(maxAttempts=3) 恢复指令:      ← 内层重试
                        JITTERED_BACKOFF: 2s→4s→8s(+jitter, 上限30s)  / 3 次
                        ADAPTIVE_RATE_LIMIT: 30/60/90/120s
                        CONTEXT_COMPRESSION / CREDENTIAL_ROTATION / PROVIDER_FALLBACK
                      耗尽 → TERMINATE → ResilientCallException
                  }
```

**最坏情形**（同步路径、持续 5xx、无 fallback）：外层 4 次 × 内层 3 次 = **12 次下游调用**；耗时 ≈ 外层退避 (1+2+4+8=15s) + 内层退避 (2+4+8+jitter≈14~20s)×4 ≈ **71~95s+**；`Thread.sleep` 双层阻塞同一调用链线程。

### 2.2 目标设计：重试单一所有权

**职责划分（消除重叠）**：

| 层 | 修复后职责 | 参数 |
|---|---|---|
| `ResilientChatModelExecutor.call()` | **唯一语义级重试/回退所有者**：错误分类 → 退避重试 / 上下文压缩 / 凭据轮换 / fallback 链；耗尽抛 `ResilientCallException` | maxAttempts=3（`currentModelConfig.maxAttempts`，可配）；jitteredBackoff base=2s/max=30s/jitter=0.5；自适应限流 30/60/90/120s；fallback 每模型 3 次 |
| `ModelInvoker.callWithStream` | **退化为单次调用 + 超时**：移除 for 循环 / `Thread.sleep` / `isRetryable`；`stream().collectList().block(callTimeoutMs)`，超时或异常 → `ModelCallResult.error`（不再重试） | `aether.model.invoker.call-timeout-ms=120000`；`aether.model.invoker.connect-timeout-ms=30000`（由底层 HTTP 客户端配置） |
| `ReActAgent` | 调用超时兜底：`.block(2min)` 改为读配置 `aether.model.invoker.call-timeout-ms`；缓存失败回退同步路径逻辑保留（同步路径本身已无重试） | 同上 |

**如何避免内外层重复重试**：重试入口全仓库唯一（`ResilientChatModelExecutor.call` 的 while 循环）；`ModelInvoker` 与 `ReActAgent` 不再存在任何 `for/retry` 逻辑，只负责"单次调用 + 总超时"。任何新重试需求必须落在 Resilient 层（开放封闭）。

### 2.3 关键代码

**① `ModelInvoker.callWithStream` 重写（核心改动）**：

```java
// 删除: MAX_RETRIES / INITIAL_BACKOFF_MS / MAX_BACKOFF_MS 常量；isRetryable() 方法

/** 单次模型调用总超时（毫秒）；读取 aether.model.invoker.call-timeout-ms */
@org.springframework.beans.factory.annotation.Value("${aether.model.invoker.call-timeout-ms:120000}")
private long callTimeoutMs;

public ModelCallResult callWithStream(ChatModel chatModel, List<Message> messages,
        String systemPrompt, String modelName) {
    // ... 组装 fullMessages / Prompt（不变） ...

    try {
        // 单次调用 + 总超时。重试/回退/凭据轮换已由 ResilientChatModelExecutor 统一负责
        List<ChatResponse> responses = chatModel.stream(prompt)
                .collectList()
                .block(Duration.ofMillis(callTimeoutMs));
        // ... 解析 text/toolCalls/token 用量（不变） ...
        return ModelCallResult.builder()...build();
    } catch (Exception e) {
        log.error("模型调用失败（单次调用，重试已收口至 ResilientChatModelExecutor）: "
                + "model={}, error={}", modelName, e.getMessage());
        return ModelCallResult.error(e.getMessage());
    }
}
```

> 语义说明：当 `chatModel` 为 `ResilientChatModelExecutor` 时，`block(callTimeoutMs)` 超时意味着"内层 3 次退避 + fallback 全链仍未成功"，直接返回错误结果 → `ReActAgent` 发 `error` 事件结束本轮；符合"重试预算收敛"目标。若内层 sleep 导致 `block` 超时，RxJava 会尝试取消订阅；由于 `Flux.defer` 内是同步 `call()`，线程会等到内层当前一次恢复动作结束——该占用被缺陷一的有界池 + CallerRuns 反压约束（两修复协同）。

**② 连接超时（双通道）**：`aether-app/.../config` 中若存在 WebClient/RestClient 配置，补：
```java
// WebClient: .httpClient(reactor.netty.http.client.HttpClient.create()
//     .responseTimeout(Duration.ofMillis(connectTimeoutMs)))
// 或 RestClient: .requestFactory(new JdkClientHttpRequestFactory(client))  // JdkHttpClient connectTimeout
@Value("${aether.model.invoker.connect-timeout-ms:30000}") long connectTimeoutMs;
```
（当前 `ModelProvider` 实现如 `OpenAIProvider` 使用 Spring AI 默认客户端；若未暴露超时配置，先在 `application.yml` 声明键，接入点标记 TODO。）

**③ ReActAgent 超时统一**（`:257`）：
```java
// 原: .block(java.time.Duration.ofMinutes(2))
.block(java.time.Duration.ofMillis(modelCallTimeoutMs))
// modelCallTimeoutMs 由构造注入或 @Value 提供，与 ModelInvoker 同源 aether.model.invoker.call-timeout-ms
```

**④ 配置段**：
```yaml
aether:
  model:
    invoker:
      call-timeout-ms: 120000      # 单次模型调用总超时（流式收集）
      connect-timeout-ms: 30000    # 建连超时
    resilient:
      max-attempts: 3              # （可选）覆盖 ResilientChatModelExecutor 默认，经 ModelConfig.maxAttempts 生效
```

### 2.4 风险与回滚

- **风险**：
  1. 同步路径从"最多 12 次"降为"最多 3 次"，短时网络抖动下成功率略降——由内层 jitter 退避 + fallback 链补偿，可接受；
  2. 某些 Provider（如小觅 mimo）400 曾靠外层重试兜底（`isRetryable` 中 `modelRef.contains("mimo")` 特判）——需确认 `DefaultModelErrorClassifier` 对 400 的分类能覆盖（若不能，在分类器补 BAD_REQUEST→重试映射，而非恢复外层循环）；
  3. `ModelInvokerTest` 现有断言基于重试行为，需同步改写。
- **回滚（两档）**：
  - **A（推荐）**：该改动独立提交，`git revert <commit>` 整体回退，恢复旧循环；下线前用灰度流量验证 ≥1 周。
  - **B（保守灰度）**：保留旧循环但加开关 `aether.model.invoker.legacy-retry-enabled`（默认 false），`if (legacyRetryEnabled) { 旧循环 } else { 单次调用 }`；灰度期置 true 观察，稳定后删除旧分支并移除开关。**注意**：开关模式下旧循环与 Resilient 并存即缺陷复现，故只作为临时回退通道，默认必须 false，且上线计划中写明删除日期。

### 2.5 修改前后行为对比

| 场景 | 修复前（同步路径） | 修复后 |
|---|---|---|
| 持续 5xx（无 fallback） | 12 次下游调用，最坏 ~71~95s | **3 次**下游调用，最坏 ~14~20s（2+4+8s+jitter） |
| 持续 5xx（有 1 级 fallback） | 12 次主模型 + 12 次 fallback | 3 次主模型 + 3 次 fallback |
| 429 限流 | 外层 4×(1..15s) + 内层自适应 30/60/90/120s 叠加 | 仅内层自适应表（30/60/90/120s） |
| 单次挂起 60s | 外层 `.block(2min)` 兜底 + 内层 sleep 叠加，线程长占 | `.block(120s)` 单层兜底，超时即错误事件 |
| 线程阻塞 | 两层 `Thread.sleep` 占用调用链 | 单层 sleep，受有界池控制 |

### 2.6 验证方法

1. **单元测试（改写 `ModelInvokerTest`）**：
   - `mock ChatModel.stream` 持续抛 5xx → 断言 `callWithStream` **只调用 `stream()` 一次**（`verify(chatModel, times(1)).stream(any())`），返回 `hasError()==true`，且无 `Thread.sleep` 退避（测试耗时 <1s 即证明无重试）；
   - 单次成功路径断言返回结果与 token 解析不变。
2. **`ResilientChatModelExecutorTest`（已有）**：保留——验证内层退避序列严格为 base=2s 起、≤maxAttempts=3、jitter 在 [0,0.5×delay] 内（用 `setBackoffWaiter` 无阻塞等待器断言序列长度与参数）。
3. **集成（Mock Provider）**：注入持续 5xx 的 Provider，观测日志退避序列仅出现在 `ResilientChatModelExecutor` 层；Prometheus 计数器（如 `aether.model.call.attempts`，若已埋点）单次请求 ≤3。
4. **回归**：`mvn -pl aether-domain test` 全绿；`ReActAgentTest`/`GraphExecutorHookTest` 中模型调用相关用例通过。

---

## 3. 缺陷三：默认无持久化

### 3.1 现状：三重根因链（已复核）

1. **无 Bean**：`PgSessionRepository.java:28` `@ConditionalOnProperty(name="aether.session.persistence", havingValue="true", matchIfMissing=false)` → 未显式配置时**不创建**（`RedisSessionRepository.java:25` 同）；
2. **无表**：`schema.sql`（`spring.sql.init.mode=always` 执行）**没有 `aether_session` DDL**——仅存在于 `docs/postgresql_schema.sql`（纯文档不执行）→ 即使开了 persistence 开关，save 也会因表不存在失败；
3. **静默**：`ChatService.java:90-91` `@Autowired(required=false)` 注入空；`:135-149` `createSession` 与仓储 save 用 `.exceptionally()` **吞掉异常只记 WARN** → 全链路无感知地"不持久化"。

### 3.2 目标设计

| 设计点 | 决策 | 理由 |
|---|---|---|
| 持久化介质 | **PostgreSQL（主）+ Redis（可选覆盖）** | 项目已依赖 pgvector/pg16 与 HikariCP，零新依赖；Redis 已有仓储实现，作为显式切换项保留 |
| 数据一致性级别 | **快照式最终一致**：每次持久化写入 AgentState 完整 JSON（`state_json`），`ON CONFLICT (session_id) DO UPDATE` 整行覆盖；异步写（专用有界池），读时读最新 | 会话是"整态快照 + 恢复"模型，无跨行事务需求；避免为低价值状态引入分布式事务（YAGNI） |
| 崩溃恢复 | 重启后客户端携带 `sessionId` → `findBySessionId` → `loadState`（必需字段校验，缺字段抛 `StateRestoreException` 响亮失败）→ 恢复 messages/status/permissionContext；PAUSED 状态可继续审批流 | 复用现有 `BaseAgent.saveState/loadState`，改动最小 |
| 写失败处理 | 不再静默：WARN + Micrometer counter `aether.session.persist.failures` + 可选 health degraded | 满足"默认安全"：失败可见、可告警 |
| 性能影响 | 异步写（不阻塞请求线程）；单行 JSON 写 <5ms；写队列有界（sessionPersistPool 2/4/1000）；读路径同步 JDBC 毫秒级；`state_json` 建议 JSONB + `updated_at` 索引（迁移可选） | 会话写频次 = 对话轮次/回合结束，量级远低于读写热点 |

**恢复流程时序**：

```
启动(零配置) ──► schema.sql 幂等建 aether_session ──► PgSessionRepository 默认激活
客户端 POST /create_session ──► INSERT (ACTIVE, state_json=NULL)
POST /chat_stream ──► 每轮结束 saveState() → UPSERT state_json（异步）
[进程崩溃/重启]
客户端携 sessionId 再次 /chat_stream ──► findBySessionId → loadState(校验必需字段)
   ├─ 正常 → 恢复消息历史/轮次/状态，继续对话
   └─ PAUSED(审批挂起) → 要求先 /confirm 提交回执后恢复执行
```

### 3.3 关键代码与配置

**① schema.sql 追加（根因 2 修复，幂等）**：

```sql
-- ============================================================
-- P0-3: 会话持久化表（aether_session）— 移植自 docs/postgresql_schema.sql
-- 幂等：重复执行不报错；配合 spring.sql.init.mode=always 每次启动执行
-- ============================================================
CREATE TABLE IF NOT EXISTS aether_session (
    id          BIGSERIAL   PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL UNIQUE,
    user_id     VARCHAR(64) NOT NULL,
    agent_id    VARCHAR(64) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    state_json  JSONB,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_session_user_agent ON aether_session(user_id, agent_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_status ON aether_session(status);
-- 同步提供 Flyway 风格脚本: data/sql/V4__aether_session.sql（内容同上，供已有库手动迁移）
```

> `state_json` 用 JSONB（原 TEXT）——支持后续按字段查询与 pgvector 记忆回填；存量 TEXT 列迁移为 JSONB 属可选 P2（`ALTER TABLE ... ALTER COLUMN state_json TYPE JSONB USING state_json::jsonb`）。

**② PgSessionRepository 默认启用（根因 1 修复）**：

```java
@Repository
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnProperty(
        name = "aether.session.persistence",
        havingValue = "true",
        matchIfMissing = true)          // ← 关键：默认即有 Bean（零配置启用）
public class PgSessionRepository implements SessionRepository { ... }
```

**③ Redis 仓储避免双实现冲突**：`RedisSessionRepository` 激活条件改为显式选择：
```java
@Repository
@ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
@ConditionalOnProperty(name = "aether.session.store", havingValue = "redis")
public class RedisSessionRepository implements SessionRepository { ... }
```

**④ 写失败显式化（根因 3 修复）**：

`PgSessionRepository.save`：
```java
}).exceptionally(ex -> {
    // 不再静默：显式告警 + 指标
    log.error("会话持久化失败: sessionId={}, status={}", entity.getSessionId(), entity.getStatus(), ex);
    SessionPersistenceMetrics.incrementFailures();   // Micrometer counter: aether.session.persist.failures
    return null;
});
```

`ChatService.createSession`（`:135-149`）：缺失仓储时不再无声——启动期检测 + 请求期日志：
```java
// @PostConstruct 启动检测（new 一个监听 ApplicationReadyEvent 的小组件）：
if (sessionRepository == null && persistenceIntendedByDefault) {
    log.warn("会话持久化未激活（无 SessionRepository Bean）：会话重启后不可恢复。"
            + "生产环境请确保 aether.session.persistence=true 且配置 PostgreSQL。");
}
```

**⑤ 配置语义**（`application.yml`，默认值即安全态）：
```yaml
aether:
  session:
    persistence: true      # 默认 true：有 pg 驱动即启用（matchIfMissing=true 时此键可省略）
    store: postgres        # postgres(默认) | redis | none（显式覆盖；none=关闭持久化）
```

### 3.4 风险与回滚

- **风险**：
  1. **存量库缺表**：升级前必须执行 `data/sql/V4__aether_session.sql`，或依赖 schema.sql 幂等建表（`spring.sql.init.mode=always` 场景自动建）；否则首个 save 失败（现在会显式告警 + 指标，不再静默，便于发现）；
  2. **异步写竞态**：两个并发回合先后 save，后写覆盖先写（快照语义内可接受；如需强序，可在 SessionEntity 加 `updated_at` 乐观锁——P2，YAGNI 当前不做）；
  3. **JSONB 迁移**：若线上已有 TEXT 列，`ALTER COLUMN` 在数据量大时有锁表窗口，择低峰执行；
  4. 引入 Redis 仓储时**必须显式 `aether.session.store=redis`**，否则双 Bean 冲突（Spring 启动报错，属响亮失败，可接受）。
- **回滚**：`git revert` 提交；恢复 `matchIfMissing=false` 即回到原行为；表保留不删（数据安全）。

### 3.5 修改前后行为对比

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 零配置启动（application.yml 默认） | 无持久化 Bean，会话全内存，**重启即丢** | Pg 默认激活，会话跨重启可恢复 |
| `persistence=true` 但表不存在 | save 失败被 `.exceptionally` 吞掉（静默） | schema.sql 幂等建表；若仍失败 → ERROR 日志 + `aether.session.persist.failures` 指标 |
| 对话中 `kill -9` 重启 | 会话列表为空、历史全丢 | 列表/消息/状态恢复至最后快照（至多丢一轮增量） |
| PAUSED 审批挂起后重启 | 挂起上下文丢失（状态恢复缺失） | `permissionContext` 随 state_json 恢复，/confirm 可继续 |
| 写失败可观测性 | 仅 debug 级 WARN | ERROR 日志 + Prometheus counter +（可选）health degraded |

### 3.6 验证方法

1. **单元（条件注解）**：新增 `PgSessionRepositoryConditionTest`，用 `ApplicationContextRunner` 断言：
   - 无任何 `aether.session.*` 配置 + pg 驱动在 classpath → `PgSessionRepository` Bean **存在**（`matchIfMissing=true` 生效）；
   - `aether.session.persistence=false` 或 `aether.session.store=none` → Bean 不存在。
2. **单元（SQL/绑定，延续项目 Mockito 风格）**：`PgSessionRepositoryTest` 验证 UPSERT 绑定参数（session_id/user_id/agent_id/status/state_json/时间戳）与软删除 SQL；`state_json` 写入后 `findBySessionId` 读回相等。
3. **集成（真实恢复链路，推荐 Testcontainers 或本地 pgvector）**：
   ```
   docker compose -f docker/docker-compose-secure.yml up -d postgres
   # 1) 零额外配置启动应用（仅 application.yml，不设 persistence 键）
   # 2) POST /api/v1/auth/login → POST /api/v1/create_session
   # 3) POST /api/v1/chat_stream 完成一轮对话
   # 4) kill -9 <pid>（模拟崩溃）
   # 5) 重启应用 → GET /api/v1/list_sessions?userId=1 断言会话存在
   # 6) GET /api/v1/session_messages?sessionId=xx 断言历史消息恢复
   # 7) （可选）审批流：配置危险工具 → 触发 permissionAsking → kill → 重启 → /confirm 恢复
   ```
4. **性能抽样**：压测 100 轮对话，观察 `aether.session.persist.*` 指标：写延迟 p99 <50ms、无请求线程阻塞（异步）、队列无积压。

---

## 4. 实施顺序与总体验收清单

### 4.0 实施记录（提交 1：统一线程池管理 —— 已完成 ✅）

> 实施日期：2026-08-17 ｜ 验证：`mvn -B test -DskipTests=false` 全量 **355 个测试全绿**，7 模块 BUILD SUCCESS

**已落地改动（源码）**：
- 新增 `AetherExecutorRegistry` + `AetherThreadPoolProperties`（aether-app/config）：8 个命名有界池（graph/tool/memory-io/session-persist/delegation/background-review/graph-trace/scheduled），`aether.thread-pools.*` 可配，容器统一 `destroyMethod="shutdown"`。
- `GraphExecutor`：删除 `newCachedThreadPool()` 与 GRAPHFLOW 裸 `new Thread`，注入 `graphPool`（字段级惰性单例兜底有界池，测试直 new 安全）。
- `StaleDelegationScanner` / `MemoryEmbeddingBackfillRunner` / `SubagentLifecycleService`：裸线程/自建池改为注入 `scheduledPool` / `memoryIoPool` / `delegationPool`（自建池才由本类关闭，共享池由容器关闭——新增 `ownsScheduler`/`ownsExecutor` 标志）。
- `RateLimitFilter`：`newSingleThreadScheduledExecutor`（泄漏）改为注入 `scheduledPool`。
- `PgvectorVectorStore` / `PgSessionRepository` / `RedisSessionRepository`：`CompletableFuture.runAsync` 显式传注入池（退出 ForkJoinPool.commonPool）。
- `application.yml`：新增 `aether.thread-pools.graph` 配置段。
- `aether-app/pom.xml`：surefire 2.6 → 继承 Boot 3.5.2（2.6 无法执行 JUnit 5，测试曾被静默跳过）；`skipTests` 改属性占位（默认 true 保持约定，`-DskipTests=false` 可覆盖）。
- 新增测试：`AetherExecutorRegistryTest`（池参数/有界/拒绝策略）、`GraphExecutorPoolTest`（50 并发 PARALLEL 节点线程数 ≤ 池上限且全部完成）。

**实施中发现并附带修复（历史遗留，非本次缺陷引入）**：
1. `AgentIntegrationTest`（@SpringBootTest，排除数据源）此前因 surefire 2.6 + skipTests 从未执行；其上下文加载失败源于仓储构造硬依赖 DataSource。修复：`AuditAspect`/`DataInitializer` 改为 `@Autowired(required=false)` 降级（无仓储时审计/初始化 no-op）；测试侧用 `@MockitoBean` 替换 3 个持久化仓储。附带收益：该集成测试现可**在真实容器中验证本次线程池注入正确性**（19/19 通过）。
2. 曾尝试 `@ConditionalOnBean(DataSource)` 于仓储类，因 Spring Boot 对组件扫描类条件评估顺序不可靠（存在生产误判风险）**已弃用**，仅保留逻辑降级方案。

**未纳入本提交（后续）**：`ToolExecutor`/`BackgroundReviewer`/`GraphExecutionRecorder` 内部有界池（已安全，参数统一待 P1）；`MemoryStore`/`RecallFlow`/`DefaultMemoryFacade`/`MemoryManager`/`SessionMemoryExtractor` 自建池（无界队列，P1 项）。

### 4.0.1 实施记录（提交 2：模型重试收口 —— 已完成 ✅）

> 实施日期：2026-08-17 ｜ 验证：`mvn -B test -DskipTests=false` 全量全绿（domain/infrastructure 先行模块通过，全量见 §4.2 复跑）

**已落地改动（源码）**：
- `ModelInvoker`（核心）：删除 `MAX_RETRIES`/`INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS` 常量、外层 for 重试循环、`Thread.sleep` 指数退避、`isRetryable()` 方法；`callWithStream` 退化为**单次调用 + 总超时**：`chatModel.stream(prompt).collectList().block(Duration.ofMillis(callTimeoutMs))`，超时/异常 → `ModelCallResult.error`。新增 `@Value("${aether.model.invoker.call-timeout-ms:120000}")` 字段（带初始值，测试直 new 兜底）+ `getCallTimeoutMs()` 单源访问器。
- `ReActAgent`（`:257`）：`.block(Duration.ofMinutes(2))` 硬编码 → `.block(Duration.ofMillis(modelInvoker.getCallTimeoutMs()))`，与 ModelInvoker 同源配置，不动 11 参构造。
- `DefaultModelErrorClassifier`：迁移原 `isRetryable` 的 mimo 400 特判——400 + model 含 "mimo" → `UNKNOWN`（走 Resilient 层抖动退避，仍受 maxAttempts=3 预算约束）；其余 Provider 400 → `FORMAT_ERROR`（终止）。
- `application.yml`：新增 `aether.model.invoker.call-timeout-ms: 120000`（connect-timeout-ms 键预留，当前由 `ModelProvider.buildOpenAiApi` 固化 30s，TODO 标记配置化）。
- 测试：`ModelInvokerTest` 改写——删除 7 个 `isRetryable` 断言；新增"持续 5xx 时 `verify(chatModel, times(1)).stream()` + 无退避（耗时 <1s）"、"Flux.never 按总超时快速返回"、"成功路径文本/工具调用解析不变"；`parseArguments` 用例保留。新增 `DefaultModelErrorClassifierTest`（mimo 400→UNKNOWN / 非 mimo 400→FORMAT_ERROR / timeout→TIMEOUT）。

**行为对比（同步路径）**：持续 5xx 无 fallback：12 次下游调用 ~71-95s → **3 次** ~14-20s（仅 Resilient 内层抖动退避 2+4+8s）；单次挂起：双层 sleep → 单层 `.block(120s)` 超时即错误事件；重试入口全仓库唯一（`ResilientChatModelExecutor.call`）。

**风险确认（§2.4 风险 2）**：mimo 400 语义已迁移至分类器（UNKNOWN→抖动退避），未恢复外层循环；`ResilientChatModelExecutorTest` 保留未动（内层退避序列断言仍有效）。

### 4.0.2 实施记录（提交 3：会话持久化默认启用 —— 已完成 ✅）

> 实施日期：2026-08-17 ｜ 验证：`mvn -B test -DskipTests=false` 全量 **363 个测试全绿**，7 模块 BUILD SUCCESS（含 AgentIntegrationTest 19/19）

**已落地改动（源码/配置/DDL）**：
- `schema.sql` 追加 `aether_session` 幂等 DDL（与 `docs/postgresql_schema.sql` 定义一致：VARCHAR(128)/TEXT，避免新旧库列定义不一致；JSONB 迁移留 P2）；同步新增 `data/sql/V4__aether_session.sql`（Flyway 风格，供存量库手动迁移）。
- `PgSessionRepository`：激活条件由 `persistence=true + matchIfMissing=false`（零配置无 Bean，根因 1）改为 **默认激活**——`@ConditionalOnExpression("'${aether.session.store:postgres}' == 'postgres' && '${aether.session.persistence:true}' == 'true'")`（`@ConditionalOnProperty` 非 `@Repeatable`，双键合并为 SpEL；零配置 / persistence=true / store=postgres → 激活；persistence=false / store=redis / store=none → 关闭）。构造改为注入 `JdbcTemplate`（对齐 `PgAsyncDelegationStore` 先例，可测）；save 失败 `.exceptionally` 增加 `SessionPersistenceMetrics.incrementFailures()`（不再静默）。
- 新增 `SessionPersistenceMetrics`：Micrometer counter `aether.session.persist.failures`（`MeterRegistry` 可选注入，无注册表时安全 no-op）。
- `RedisSessionRepository`：激活条件改为显式 `aether.session.store=redis`（与 Pg 互斥；当前工程未引入 spring-data-redis，本类默认不激活）。
- `ChatService`：新增 `@PostConstruct` 启动检测——无 `SessionRepository` Bean 时显式 WARN（不再无声降级），提示配置 PostgreSQL/Redis 或显式 `store=none`。
- `application.yml`：新增默认段 `aether.session.persistence: true` + `store: postgres`（默认即安全态）；`application-test.yml`：显式 `aether.session.persistence: false`（测试排除数据源，避免 Pg 仓储无 DataSource 创建失败）。
- 新增测试：`PgSessionRepositoryConditionTest`（ApplicationContextRunner：零配置默认激活 / persistence=false / store=redis / store=none 均关闭，锁定根因 1）、`PgSessionRepositoryTest`（mock JdbcTemplate：UPSERT 绑定 7 参 / 软删除绑定 / findBySessionId 行映射，对齐 schema 列）、`SessionPersistenceMetricsTest`（counter 递增 / 无注册表 no-op）。

**行为对比**：零配置启动：会话全内存、重启即丢 → Pg 默认激活、跨重启可恢复；写失败：`.exceptionally` 吞掉 → ERROR 日志 + `aether.session.persist.failures` 指标；无仓储启动：无声 → 启动期 WARN 提示。

**实施中调整（与方案差异，均为等价修正）**：
1. `@ConditionalOnProperty` 不可重复 → 双键条件合并为 `@ConditionalOnExpression`（语义不变：默认激活 + 双键关闭）。
2. Pg 仓储构造由 `(DataSource, Executor)` 改为 `(JdbcTemplate, Executor, Metrics)`——JdbcTemplate 由 Spring Boot 自动配置提供，生产行为不变，换来可 mock 单测（对齐项目既有 `PgAsyncDelegationStore` 模式）。
3. 未做 mock JDBC 之外的重型集成恢复链路测试（§3.6 ③ 需真实 PG，无 Testcontainers 基建），以条件注解 + 绑定参数单测 + 容器集成测试（AgentIntegrationTest）覆盖。

### 4.0.3 实施记录（code-review 审查后修复 —— 已完成 ✅）

> 实施日期：2026-08-17 ｜ 依据：Standards/Spec 双轴审查发现（§4.0.2 之后）｜ 验证：`mvn -B test -DskipTests=false` 全量 **365 个测试全绿**

**修复项**：
1. **`.gitignore` 移除 `/aether-app/src/test/`**：此前该目录被整目录忽略（仓库既有决策），AetherExecutorRegistryTest / AgentIntegrationTest / application-test.yml（F3 测试配置）均不随提交交付，"363 全绿"不可复现。已解除忽略（目录内仅测试源码与假 key 配置，无敏感信息），验收测试现可随提交交付。
2. **delegation 池参数文档对齐**：spec §1.2 表 / §1.3 示例 `delegationPool max 4→5`——实施保留旧 `SubagentLifecycleService.DEFAULT_POOL_SIZE=5` 生产语义（行为零变化），文档补记该决策。
3. **ChatService WARN 守卫**：新增 `persistenceIntendedByDefault` 守卫（store 缺省/postgres 且 persistence 缺省/true 且无仓储时才 WARN），显式 `store=none`/`persistence=false` 静默；文案去掉自相矛盾的建议（原提示"store=none 关闭本警告"实为不可达）。
4. **ModelInvoker 错误兜底**：`ModelCallResult.error(e.getMessage())` 在异常无 message 时 error 为 null → `hasError()` 误判成功；改为 `getMessage() != null ? getMessage() : getClass().getSimpleName()`；`catch` 中恢复中断位（`InterruptedException` 时 `Thread.currentThread().interrupt()`）。新增测试用例锁定。
5. **GRAPHFLOW 并发测试**：`GraphExecutorPoolTest` 新增 `fiftyGraphFlowNodesStayBoundedAndAllComplete`（50 节点 DAG + n0→n1 边，首批并发 49 > 池 max 8，断言线程收敛 + 50 全部完成），覆盖 GRAPHFLOW 裸线程替换点（`graphPoolOrDefault().execute` 路径）。
6. **小修**：`SessionPersistenceMetricsTest` 用 `METRIC_NAME` 常量替代硬编码字符串；`GraphExecutor` 兜底池注释声明与 `AetherThreadPoolProperties.DEFAULT("graph")` 手动同步（domain 不依赖 app 模块）；`AetherExecutorRegistry.scheduledPool` 注释说明 ScheduledThreadPoolExecutor 语义（core==max、无界队列，queueCapacity/keepAliveSeconds 配置项对该池无效）。

### 4.0.4 实施记录（未修项补修 —— 已完成 ✅）

> 实施日期：2026-08-17 ｜ 依据：§4.0.3"未修（判断性/既有决策）"清单全量补修 ｜ 验证：`mvn -B test -DskipTests=false` 全量 **365 个测试全绿**（含 AgentIntegrationTest 19/19）

**补修项**：
1. **`javax.annotation.*` → `jakarta.annotation.*` 全项目迁移**：43 个文件（Resource 44 处 + PostConstruct 6 处 + PreDestroy 4 处，含 `GraphExecutorHookTest` 的注解断言），消除同批改动中 javax/jakarta 混用。批处理采用无 BOM UTF-8 写入，未触碰其他内容；编译/测试全绿。
2. **ReActAgent 超时不回退（消除 240s 叠加）**：外层 `.block(callTimeoutMs)` 超时（Reactor `IllegalStateException("Timeout on blocking read...")`，递归 cause 链判断，含 `TimeoutException`）视为**预算耗尽**，直接返回 `ModelCallResult.error`，不再回退同步路径（同步路径自身还有一次 `block(callTimeoutMs)`，叠加最坏 2×预算且外层无法取消内层）；其他异常（缓存层故障等）仍保留回退语义。最坏耗时从 240s 收敛为单次预算 120s。
3. **`connect-timeout-ms` 声明化**：`application.yml` 由注释改为声明键 `connect-timeout-ms: 30000`（spec §2.3 ② 要求），TODO 标注当前由 `ModelProvider.buildOpenAiApi` 固化 30s、未接入配置。

**至此 §4.0.3"未修"清单清零**；后续仅存 spec 文档本身与实施记录的差异说明（均为等价/记录性偏差，无行为差异）。

### 4.1 提交拆分（每项可独立 revert）

| 顺序 | 提交 | 内容 | 验收 |
|---|---|---|---|
| 1 | `fix(p0-1): 统一线程池管理` | AetherThreadPoolProperties + AetherExecutorRegistry + 6 处调用点替换 + 测试 | F1 §1.6 全过 |
| 2 | `fix(p0-2): 模型重试收口至 ResilientChatModelExecutor` | ModelInvoker 单次化 + 超时配置 + ReActAgent 超时统一 + ModelInvokerTest 改写 | F2 §2.6 全过 |
| 3 | `fix(p0-3): 会话持久化默认启用` | schema.sql DDL + Pg 仓储默认激活 + 指标/告警 + Redis 显式切换 + 测试 | F3 §3.6 全过 |

### 4.2 全局回归

```bash
mvn clean package -DskipTests=false    # 全量编译 + 测试（父 POM surefire 默认 skipTests=true，须显式关闭）
# 或按模块： mvn -pl aether-domain,aether-infrastructure,aether-trigger test
```

### 4.3 上线后监控

- `jvm_threads_live_threads` / `aether.graph.pool.active`（新增指标）→ 验证 F1；
- `aether.model.call.attempts`（或日志退避序列）→ 验证 F2（单次请求 ≤3 次尝试）；
- `aether.session.persist.failures` / `aether.session.persistence.enabled`（health）→ 验证 F3。

---

*本方案所有参数均可调，默认值面向 2C4G 单实例；生产调参以压测为准。file:line 证据基于 2026-08-17 master 基线复核。*

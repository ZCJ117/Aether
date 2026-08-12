# Aether 编排协调模块 hermes 对齐改造设计

> 日期：2026-08-12
> 状态：已批准（用户确认方案 A 分层增强 + 方案 a 异步委派平行接入 + 分批落地 D2→D3→D1→D4）
> 范围：`aether` 后端编排与协调模块（分支 `feat/orchestration-hermes-alignment`）
> 参照：`hermes-agent-main/`（**只读，不可修改**）—— 所有对齐点均附 hermes 源文件路径:行号证据

---

## 1. 背景与目标

Aether 编排协调（GraphExecutor 图执行 + SubAgentOrchestrator 委派 + ResilientChatModelExecutor 容错）已在之前分支做过一轮 hermes 对齐，但**在四个维度上与 hermes 仍存在结构性差距**：无 turn 级恢复分支账本、无异步委派持久化、钩子体系不覆盖编排生命周期、无可视化观测入口。

**目标**：以 hermes-agent-main 为基准，对 Aether 编排协调做端到端优化，按「Java 语义移植」口径落地 —— **类职责与 hermes 一一对应，行为/参数/状态机保持一致，用 Spring/RxJava/SQLite 生态落地**。

**成功标准（可验证）**：
1. 每个维度批次附带 JUnit 测试，`mvn -pl 对应模块 test` 通过。
2. D2：`TurnRetryState` 按分类+账本输出恢复指令，`ResilientChatModelExecutor` 不再内联计数决策。
3. D3：`HookRegistry.invokeAll(HookPoint, HookContext)` 覆盖 14 个钩子点，单个 hook 异常不阻断主流程。
4. D1：异步委派落 SQLite（WAL），进程崩溃后启动 `recoverAbandoned` 重新入队、`restoreUndelivered` 回灌 completion。
5. D4：每子Agent直播日志可 `tail`、图级执行快照可回放、HTTP 端点可实时控制。
6. 现有同步委派路径（`SubAgentOrchestrator`）行为不变（方案 a）。

---

## 2. 澄清结论（决策依据）

| 问题 | 用户确认 |
|---|---|
| Q1 范围与节奏 | **一份设计 + 分批落地**：D2 异常重试 → D3 可扩展 → D1 多Agent协作 → D4 观测，每批独立可编译可测 |
| Q2 代码落点 | **新建独立分支** `feat/orchestration-hermes-alignment`（已创建），与未合并的 memory 特性隔离 |
| Q3 一致性口径 | **Java 语义移植**：类职责/状态机/参数表与 hermes 一一对应，行为一致而非逐行一致 |
| Q4 验收标准 | 每批附 JUnit 测试，`mvn -pl <模块> test` 通过为完成标准 |
| Q5 改造策略 | **方案 A 领域驱动·分层增强**：不新增模块，能力注入各层现有包 |
| Q6 D2 退避参数 | 保留 Aether 现有 `jitteredBackoff`（base=2s/max=30s/jitter=0.5），只新增自适应限流表 |
| Q7 D1 集成方式 | **方案 a**：异步委派作为平行新增能力，现有同步路径保持原样 |

---

## 3. 总体架构（方案 A 分层增强）

```
┌─ aether-domain ───────────────────────────┐   ┌─ aether-infrastructure ────────┐
│ model/failover/                           │   │ classifier/  增强分类器         │
│   + RecoveryBranch 枚举                   │   │ credential/  + RotatingCredentialPool
│   + TurnRetryState 恢复分支账本           │──▶│ deleg/       + SqliteAsyncDelegationStore
│   + RecoveryDirective                     │   │ observability/ + FileDelegationLiveLog
│   + RetryBackoff（自适应限流表）          │   └────────────────────────────────┘
│   + CredentialPool（domain 端口）         │
│ agent/hook/                               │
│   + HookPoint 枚举 / HookContext          │
│   + LifecycleHook / HookRegistry 扩展     │
│   + HookConfigLoader / ShellHook          │
│ subagent/                                 │
│   + SubagentState / SubagentLifecycleService
│   + AsyncDelegationService / CompletionBus
│   + LeaseManager / DelegationBudget / SpawnGate
│ agent/observability/                      │
│   + GraphExecutionRecorder / ExecutionControlService
└───────────────────────────────────────────┘
   ▲ 挂点注入（手术式，每处 1~2 行）
┌─ 热路径改动 ──────────────────────────────┐
│ GraphExecutor / ReActAgent.queryLoop /    │
│ ModelInvoker / SubAgentOrchestrator /     │
│ ChatService / AgentTracer / AgentEventPub.│
└───────────────────────────────────────────┘
```

设计原则（对齐 CLAUDE.md）：
- **简单优先**：每个维度只补 hermes 中真实存在且 Aether 缺失的能力，不加推测性内容。
- **手术式修改**：热路径改动点显式列出且每处最小；已存在的 AgentHook/InterventionHandler 等机制保持不动。

---

## 4. D2 异常处理与重试策略的健壮性

### 4.1 现状（已核实）

| 组件 | 现状 |
|---|---|
| `FailoverReason` | 15 种（AUTH_TRANSIENT/PERMANENT、BILLING、RATE_LIMIT、UPSTREAM_RATE_LIMIT、OVERLOADED、SERVER_ERROR、TIMEOUT、SSL_CERT、CONTEXT_OVERFLOW、PAYLOAD_TOO_LARGE、MODEL_NOT_FOUND、CONTENT_POLICY_BLOCKED、FORMAT_ERROR、UNKNOWN） |
| `ClassifiedError` | record，3 个恢复 hint：`retryable/shouldCompress/shouldFallback` |
| `ResilientChatModelExecutor` | 内联 while 循环：`retryCount + compressionAttempts`；`jitteredBackoff`(base=2/max=30/jitter=0.5)；fallback 链 + 60s 冷却 + 30s 耗尽冷却；AUTH_PERMANENT/CONTENT_POLICY_BLOCKED 立即终止 |
| `ModelErrorClassifier` SPI + `DefaultModelErrorClassifier` | 优先级管线：消息模式 → HTTP 状态码 → 传输异常 → UNKNOWN |

### 4.2 hermes 参照与缺口

| hermes 组件 | 证据 | Aether 缺口 |
|---|---|---|
| `TurnRetryState`（16 种恢复分支账本） | `agent/turn_retry_state.py` L33 | 无 turn 级决策中心，重试内联在 executor 循环 |
| `adaptive_rate_limit_backoff`（30/60/90/120s 表） | `agent/retry_utils.py` L108 | 429/过载也用同一指数退避，无 provider 专用曲线 |
| 凭据轮换 | `error_classifier.py` `shouldRotateCredential`；`agent_runtime_helpers.py` `recover_with_credential_pool` L872 | 无 `CredentialPool`，无轮换 |
| Provider 专用重试上限 | `retry_utils.py` `zai_coding_overload_retry_ceiling` L140 | 无 |
| 5 向恢复 hint | `error_classifier.py` L90-93 | 只有 3 向（缺 `shouldRotateCredential`/`shouldStripThinking`） |

### 4.3 设计

**① `RecoveryBranch` 枚举（新增，domain `model/failover/`）**
```
CREDENTIAL_ROTATION / PROVIDER_FALLBACK / ADAPTIVE_RATE_LIMIT_BACKOFF /
JITTERED_BACKOFF / CONTEXT_COMPRESSION / TIMEOUT_RECONNECT / TERMINATE
```

**② `TurnRetryState`（新增，domain）** — 对齐 `turn_retry_state.py`
- 每 turn 一个实例。账本：`Set<RecoveryBranch> attempted` + `Map<RecoveryBranch,Integer> attemptCount`
- 每分支限次：CREDENTIAL_ROTATION=1、CONTEXT_COMPRESSION=2、ADAPTIVE_RATE_LIMIT_BACKOFF=maxAttempts、PROVIDER_FALLBACK=fallback 链长度
- 核心方法：`nextDirective(ClassifiedError)` → `RecoveryDirective`；`markAttempted(branch)`；`reset()`
- 语义：同一 turn 内去重+限次，避免同一恢复动作被无限重复

**③ `RecoveryDirective`（新增 record）** — 决策输出
```java
record RecoveryDirective(RecoveryBranch branch, boolean shouldRetry,
        double backoffSec, boolean rotateCredential, boolean compress,
        boolean fallback, String reason) {}
```

**④ `ClassifiedError` 扩展（修改，手术式）** — 新增两个 hint 字段：`shouldRotateCredential`（AUTH_TRANSIENT/BILLING）、`shouldStripThinking`（Anthropic thinking 格式错，可选），同步更新 `of()`/`unknown()` 工厂。

**⑤ `RetryBackoff`（新增，对齐 `retry_utils.py`）**
- `jitteredBackoff(attempt)`：保留现有实现与数值（base=2/max=30/jitter=0.5，**用户已确认不改**）
- `adaptiveRateLimitBackoff(attempt)`：`{1:30s, 2:60s, 3:90s, ≥4:120s}`（对齐 L108），用于 RATE_LIMIT/OVERLOADED

**⑥ `CredentialPool` 接口 + `RotatingCredentialPool`（domain 接口 + infrastructure 实现）**
- 接口：`Optional<ModelConfig> rotate(ModelConfig current, String provider)`
- 实现：按 provider 轮询凭据列表，返回下一个可用 apiKey/baseUrl；通过现有 `ModelProvider.createChatModel()` 重建 ChatModel

**⑦ `ResilientChatModelExecutor` 核心循环改造（修改）** — 委托决策给 `TurnRetryState`：
```
catch e:
  classified = errorClassifier.classify(e)
  d = turnRetryState.nextDirective(classified)
  switch d.branch():
    JITTERED_BACKOFF         → sleep(jitteredBackoff)         ; continue
    ADAPTIVE_RATE_LIMIT_BACKOFF → sleep(adaptiveRateLimitBackoff); continue
    CONTEXT_COMPRESSION      → compressCallback.compress      ; continue
    CREDENTIAL_ROTATION      → credentialPool.rotate→重建Model; continue
    PROVIDER_FALLBACK        → tryActivateFallback            ; continue
    TERMINATE                → throw ResilientCallException
  每轮执行后 markAttempted(branch)；成功/fallback 切换 → reset()
```

### 4.4 数据流
```
异常 → ModelErrorClassifier(优先级管线) → ClassifiedError(5向hint)
     → TurnRetryState.nextDirective(账本去重+限次) → RecoveryDirective
     → executor 执行恢复动作（退避/压缩/轮换/fallback/终止）
```

### 4.5 测试
- `TurnRetryStateTest`：同 turn 轮换仅 1 次、压缩上限 2 次、分支耗尽转 fallback/TERMINATE
- `RetryBackoffTest`：自适应表 30/60/90/120 断言；jitteredBackoff 区间断言
- `DefaultModelErrorClassifierTest`：429 三分支、401→AUTH_TRANSIENT+shouldRotateCredential、内容策略+shouldStripThinking
- `ResilientChatModelExecutorTest`：MockChatModel 依次抛 RATE_LIMIT→AUTH_TRANSIENT → 轮换后成功；耗尽转 fallback；最终 TERMINATE
- `RotatingCredentialPoolTest`：轮询顺序、耗尽返回 empty

---

## 5. D3 可扩展性与配置化程度

### 5.1 现状（已核实）

| 组件 | 现状 |
|---|---|
| `AgentHook` 接口 | 7 个类型化钩子点：onBefore/AfterExecute、onError、onBefore/AfterModelCall、onBefore/AfterToolCall |
| `HookRegistry` | Spring Bean 自动发现 + 优先级排序 + `injectTo(BaseAgent)` + 运行时 `register(AgentHook)` |
| 内置 Hook | `LoggingHook` / `MetricsHook` / `SessionPersistenceHook` / `CompositeHook` |
| MCP 客户端 | `armory/matter/mcp/client/`：`TooMcpCreateService`、`DefaultMcpClientFactory`、Local/SSE/Stdio 三种 `*McpCreateService`；**无 `refreshTools`、无 `tools/list_changed` 监听** |

### 5.2 hermes 参照与缺口

| hermes 组件 | 证据 | Aether 缺口 |
|---|---|---|
| `VALID_HOOKS` 全生命周期钩子点 | `hermes_cli/plugins.py` L135+ | AgentHook 只有 agent 级 7 点，缺 API request / subagent / session / 图节点级 |
| `register_hook` / `invoke_hook` | `plugins.py` L1177/L1911/L2068 | HookRegistry 无统一 `invokeAll(HookPoint, ctx)` |
| `ShellHookSpec` / `register_from_config` / `_spawn` | `agent/shell_hooks.py` L162/L204/L433 | 无 shell 命令式 hook |
| MCP 运行时刷新 | `tools/mcp_tool.py` `_refresh_tools` L2075、`is_mcp_tool_parallel_safe` L5816 | MCP 静态装配，无运行时刷新、无并行安全标记 |

### 5.3 设计

**① `HookPoint` 枚举（新增，`agent/hook/`）** — 对齐 `VALID_HOOKS` + Aether 特有
```
PRE_TOOL_CALL / POST_TOOL_CALL / PRE_LLM_CALL / POST_LLM_CALL
PRE_API_REQUEST / POST_API_REQUEST / API_REQUEST_ERROR
SUBAGENT_START / SUBAGENT_STOP
ON_SESSION_START / ON_SESSION_END / ON_GRAPH_FINALIZE
ON_GRAPH_NODE_START / ON_GRAPH_NODE_END   ← Aether 特有（图节点生命周期）
```

**② `HookContext`（新增 record）** — 统一载荷：`agentId / sessionId / graphNodeId / turnNumber / request / response / error / durationMs`。

**③ `LifecycleHook` 接口（新增）**
```java
void onHook(HookPoint point, HookContext ctx);
int order();  // 越小越先
```

**④ `HookRegistry` 扩展（修改，手术式）** — 保留现有机制，新增全局注册表
```java
// 已有：init()/injectTo(Agent)/register(AgentHook)  ← 不动
// 新增：
void registerLifecycle(LifecycleHook hook);
void invokeAll(HookPoint point, HookContext ctx);   // 空列表短路 + 异常隔离
List<LifecycleHook> hooksFor(HookPoint point);
```
- 异常隔离：单 hook 抛异常 → 记日志，不阻断后续 hook 与主流程
- 挂点注入（每处 1~2 行）：`GraphExecutor`→ON_GRAPH_NODE_START/END、ON_GRAPH_FINALIZE；`SubAgentOrchestrator`→SUBAGENT_START/STOP；`ModelInvoker`/`ReActAgent.queryLoop`→PRE/POST_API_REQUEST、API_REQUEST_ERROR；`ChatService`→ON_SESSION_START/END

**⑤ 配置驱动注册（对齐 `shell_hooks.register_from_config`）** — 工作流 YAML 新增可选 `hooks:` 段
```yaml
hooks:
  - point: SUBAGENT_START
    command: "python scripts/notify.py"
    timeoutMs: 5000
```
`HookConfigLoader` 在 `AgentGraphCompiler` 装配阶段解析 → 注册 `ShellHook`。

**⑥ `ShellHookSpec` + `ShellHook`（对齐 `shell_hooks.py`）** — invoke 时 spawn 外部进程，stdin 传 JSON 序列化 `HookContext`，超时强杀，输出入日志。

**⑦ MCP 运行时刷新（对齐 `mcp_tool.py`）**
- `McpToolRegistry`（新增，domain 接口）：`Map<serverId, List<ToolSpec>>`，`refreshTools(serverId)` / `getTools()`
- `DefaultMcpClientFactory` / 三个 `*McpCreateService` 接入：重新拉取 `tools/list` → diff → 增量替换
- 先调研现有 SSE/stdio 通道是否暴露 notification 事件（`tools/list_changed`）；可接入则自动触发，否则降级为手动 `POST /api/mcp/refresh`
- `isToolParallelSafe(String toolName)` 标记（供 D1 并行分段复用，可选）

### 5.4 双钩子体系边界（已确认）
- **`AgentHook`**：agent 内部、类型化（`RuntimeContext`/`ToolResult`），注入到单个 Agent —— **保持不动**
- **`LifecycleHook`**：全局、编排级、按 `HookPoint` 注册 —— 新增

### 5.5 测试
- `HookRegistryTest`：按 order 顺序调用；单 hook 异常不阻断其它；空列表短路
- `HookConfigLoaderTest`：YAML `hooks:` 段解析 → 正确注册
- `ShellHookTest`：命令触发、JSON 上下文传递、超时杀进程
- `McpToolRefreshTest`：mock 客户端返回新工具集 → 断言增量更新（接入事件通道后）

---

## 6. D1 多 Agent 协作的协调机制

### 6.1 现状（已核实）

| 组件 | 现状 |
|---|---|
| `subagent/` 目录 | 4 文件：`ResultRefiner` / `SubAgentBoundary` / `SubAgentDelegationTool` / `SubAgentOrchestrator` |
| `SubAgentOrchestrator` | 同步委派：Semaphore(5) 限流、60s 超时 CancelToken、`taskId="t"+hashCode` |
| 委派持久化 | **无**（grep `AsyncDelegation|DelegationStore|CompletionBus` 零命中） |

### 6.2 hermes 参照与缺口

| hermes 组件 | 证据 | Aether 缺口 |
|---|---|---|
| `SubagentState` 状态机 + `SubagentLifecycleService` | `agent/subagent_lifecycle.py` L37/L186 | 无生命周期状态机 |
| 异步委派持久化 + 恢复 + 回灌 | `tools/async_delegation.py` L594/L849/L293/L344/L1269/L780 | 无异步队列、无启动恢复、无 completion 事件总线 |
| 启动回灌 completion | `tools/process_registry.py` L142/L173/L178 | 无 `completion_queue` 等价物 |
| 凭据租约 + 心跳 + stale 检测 | `tools/delegate_tool.py` L1978/L2000/L2015 | 无租约、无心跳 |
| 预算截断 + 结果合并 | `delegate_tool.py` L1897/L2616 | 无批量预算管线 |
| 递归深度熔断 / orchestrator 重挂工具集 | `delegate_tool.py` L153/L1313 | 无 SpawnGate |

### 6.3 设计（方案 a：异步平行新增，同步路径保留）

**① `SubagentState` 枚举（新增，`subagent/`）** — 对齐 hermes
```
QUEUED / PENDING / RUNNING / COMPLETED / FAILED / CANCELLED / TIMED_OUT / INTERRUPTED
```

**② `DelegationRecord`（新增）** — `id / parentSessionId / parentAgentId / taskPayload(JSON) / toolNames / state / attemptCount / createdAt / updatedAt / lastHeartbeatAt / resultSummary`

**③ `SubagentLifecycleService`（新增，domain）** — 对齐 `subagent_lifecycle.py`
- `launch(DelegationTask)` → QUEUED→RUNNING，持有 `TaskFuture` + `CancelToken`
- `wait(id, timeout)` / `cancel(id)` → RUNNING→CANCELLED；`result(id)`
- `heartbeat(id)` → 更新 `lastHeartbeatAt`；stale 检测 → RUNNING→TIMED_OUT
- 内部 `ConcurrentHashMap<String, SubagentRuntime>` + 状态转换守卫

**④ 异步委派持久化层（对齐 `async_delegation.py`）**
- domain 端口 `AsyncDelegationStore`：`save / findPendingStale / findBySession / markCompleted / markFailed / markInterrupted / updateHeartbeat / listBySession`
- infrastructure 实现 `SqliteAsyncDelegationStore`：**SQLite WAL**（对齐 hermes SQLite 语义），表 `async_delegations`
- `AsyncDelegationService`（domain）：
  - `dispatch(task)` → 落库 QUEUED → 提交线程池 → RUNNING → 完成 `markCompleted` + 推 completion 事件
  - `dispatchBatch(tasks)`；`recoverAbandoned()` → 启动扫描 QUEUED/PENDING stale → 重新入队（`attemptCount++`，幂等防重复）
  - `restoreUndelivered()` → 启动回灌 COMPLETED 未消费 completion 到 `CompletionBus`
  - `interruptForSession(sessionId)` / `interruptAll()` / `listBySession(sessionId)`

**⑤ `CompletionBus`（新增，domain）** — 对齐 `process_registry.completion_queue`（L173/L178）：`publish(DelegationCompletion)` / `subscribe(...)` / `subscribeFromPersistence()`

**⑥ `LeaseManager` + `ChildLease`（新增，domain）** — 对齐 `child_pool.acquire_lease()`（L1978）：`acquireLease(task)` 排他租约（同 session 并发上限）、失败挂起等待、`releaseLease` / 过期检测

**⑦ 批量预算管线（新增，domain）** — 对齐 `_apply_summary_budget`（L1897）/ `_finalize_child_results`（L2616）：`DelegationBudget`（maxTokensPerChild/maxSummaryChars 截断）、`ChildResultAggregator` 合并（复用 `ResultRefiner`）

**⑧ `SpawnGate`（新增，domain）** — 对齐 `set_spawn_paused`（L153）：深度计数 + `setSpawnPaused(bool)`；子Agent继承 orchestrator 角色时重挂 `delegate_to_subagent` 工具但受深度限制

### 6.4 集成边界（已确认：方案 a）
- 现有 `SubAgentOrchestrator` 同步路径**保持原样**（`delegate_to_subagent` 工具行为不变）
- 异步委派作为平行新能力，`SubAgentDelegationTool` 增加异步模式选项
- 崩溃恢复能力作用于新增异步委派；同步路径的图级恢复标注为已知局限（不在本批范围）

### 6.5 测试
- `SubagentLifecycleServiceTest`：状态机转换、并发 cancel+complete 守卫
- `SqliteAsyncDelegationStoreTest`：CRUD + WAL；写入 PENDING 重建 store 后 `recoverAbandoned` 重入队且 `attemptCount++`
- `AsyncDelegationServiceTest`：dispatch 落库→执行→completion；dispatchBatch；interruptForSession；restoreUndelivered 回灌
- `CompletionBusTest`：发布订阅 + 启动回灌去重
- `LeaseManagerTest`：排他租约 + 并发上限
- `DelegationBudgetTest`：截断边界

---

## 7. D4 可视化 / 调试支持度

### 7.1 现状（已核实）
`agent/observability/`：`AgentTracer`（OTel span：agent.turn/model.call/tool.call）、`AgentMetrics`、`TokenUsage`。`event/`：`AgentEventPublisher`（log.info 结构化 JSON）。**无直播日志、无图级快照/回放、无实时控制接口、无 MDC 结构化关联。**

### 7.2 hermes 参照与缺口

| hermes 组件 | 证据 | Aether 缺口 |
|---|---|---|
| 每子Agent 直播日志 | `tools/delegation_live_log.py` | 无 |
| TUI 实时控制（interrupt/list/set_spawn_paused） | `tools/delegate_tool.py` L183/L206/L153 | 无（HTTP 也没有） |
| MoA trace | `agent/moa_trace.py` | 无图级 trace 快照/回放 |
| 后台自评审 | `agent/background_review.py` L974 | 无 |
| 委派查询 | `async_delegation.py` `list_async_delegations` L1269 | 无查询 API |

### 7.3 设计

**① `DelegationLiveLog`（新增，`agent/observability/`）** — 对齐 `delegation_live_log.py`
- infrastructure 文件实现：每子Agent append-only 直播日志 `cache/delegation/live/<id>/task-<n>.log`，可 `tail -f`
- `open(subagentId)` / `append(subagentId, line)`（时间戳前缀）/ `flush()` / `close()` / `tail(id, n)`
- 与 `SubagentLifecycleService` 联动：launch 时 open，COMPLETED/FAILED 时写最终摘要并 close

**② `GraphExecutionRecorder`（新增，domain）** — 对齐 `moa_trace.py` 语义
- 每次 `GraphExecutor.execute` 生成 `graphExecutionId`（MDC 携带）
- `recordNodeEvent(GraphFlowState, NodeStatus, start/end/error)` → 有序节点事件序列
- `getExecutionTrace(graphExecutionId)` → 可重放事件列表；内存队列 + 可选异步落盘

**③ `ExecutionControlService` + HTTP 端点（新增，domain + aether-trigger）** — 对齐 TUI 能力
```
GET  /api/orchestration/active-subagents          → listActiveSubAgents()
POST /api/orchestration/subagents/{id}/interrupt  → interruptSubAgent(id)
POST /api/orchestration/spawn/pause               → setSpawnPaused(bool)
GET  /api/orchestration/delegations?sessionId=    → listAsyncDelegations(sessionId)
```
复用 D1 的 `SubagentLifecycleService.cancel` / `SpawnGate` / `AsyncDelegationService.listBySession`。

**④ 图级 trace 增强（修改，手术式）** — `AgentTracer` 增加 `graph.execute` / `graph.node.<type>.<id>` span；`AgentEventPublisher` 事件增加 `graphExecutionId` 字段；统一 MDC（`graphExecutionId`/`sessionId`/`subagentId`）→ 日志可 grep/join

**⑤ 后台自评审（可选 stretch）** — `BackgroundReviewer` 对齐 `background_review.py`：图执行结束后后台质量评审，写回事件流；**默认关闭**，配置启用

### 7.4 测试
- `DelegationLiveLogTest`：append→flush→reopen 读回；多子Agent目录隔离
- `GraphExecutionRecorderTest`：record 序列 → getExecutionTrace 重放顺序正确
- `ExecutionControlServiceTest`：interrupt/list 委托到 lifecycle；spawn pause 生效

---

## 8. 测试与验收策略

- 每个维度批次独立：新文件 + 单测 + 热路径修改，`mvn -pl aether-domain test`（涉及 infrastructure 的批次加 `mvn -pl aether-infrastructure test`）通过为完成标准。
- 批间不互相阻塞：D2 完成后即可合入工作区；后续批次在此之上增量。
- 回归注意：`ResilientChatModelExecutor`（D2）影响所有模型调用，需全量回归；D3/D1/D4 的挂点改动需验证默认空实现不改变行为。

## 9. 落地顺序与批次划分

| 批次 | 维度 | 主要交付 | 依赖 |
|---|---|---|---|
| 批1 | D2 异常重试 | `RecoveryBranch` / `TurnRetryState` / `RecoveryDirective` / `RetryBackoff` / `CredentialPool`+impl / `ClassifiedError` 扩展 / `ResilientChatModelExecutor` 改造 | 无 |
| 批2 | D3 可扩展配置 | `HookPoint` / `HookContext` / `LifecycleHook` / `HookRegistry.invokeAll` / `HookConfigLoader` / `ShellHook` / MCP 刷新 | 无 |
| 批3 | D1 多Agent协作 | `SubagentState` / `SubagentLifecycleService` / `AsyncDelegationStore`+SQLite / `AsyncDelegationService` / `CompletionBus` / `LeaseManager` / `DelegationBudget` / `SpawnGate` | sqlite-jdbc 依赖 |
| 批4 | D4 可视化调试 | `DelegationLiveLog` / `GraphExecutionRecorder` / `ExecutionControlService`+HTTP / `AgentTracer` 增强 | 批3 的 lifecycle |

## 10. 风险与注意事项

1. **热路径侵入**（D2/D3）：`ResilientChatModelExecutor`、`GraphExecutor`、`ModelInvoker` 是高频路径 → 挂点/决策逻辑必须低开销（空列表短路、无锁账本）。
2. **新增依赖**（D1）：`org.xerial:sqlite-jdbc` → 根 pom + aether-infrastructure pom；SQLite WAL 文件路径需配置化。
3. **恢复幂等**（D1）：`recoverAbandoned` 需按 `attemptCount` 上限 + 状态 CAS 防重复执行；`restoreUndelivered` 回灌需去重。
4. **状态并发**（D1）：同一 `DelegationRecord` 的 cancel/complete/heartbeat 需原子转换。
5. **MCP 事件通道**（D3）：需先调研现有 SSE/stdio 客户端是否暴露 notification；不可行则降级手动刷新。
6. **日志 IO**（D4）：直播日志异步缓冲 + 定期 flush，不阻塞子Agent主流程。
7. **鉴权**（D4）：HTTP 端点复用现有 JWT 过滤链。
8. **hermes 只读约束**：本方案所有对齐仅参考 `hermes-agent-main/` 源码，**不对其做任何修改**。

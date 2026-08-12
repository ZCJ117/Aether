# Batch 4（D4 可视化调试）设计文档

> 批次：Batch 4 ｜ 维度：D4 可视化 / 调试支持度 ｜ 分支：`feat/orchestration-hermes-alignment`
> 本设计在总设计 `2026-08-12-aether-orchestration-hermes-alignment-design.md` §7 基础上细化，落实 Batch 3 遗留项承接。

## 1. 背景与目标

Aether 编排协调模块向 `hermes-agent-main` 对齐，Batch 1（D2）/Batch 2（D3）/Batch 3（D1）已完成并全量测试通过。本批落实 D4 可视化调试：

- 每子Agent 直播日志（`tail -f` 可看子Agent干活）
- 图级执行 trace 快照与回放
- 实时控制接口（list / interrupt / spawn pause / delegation 查询）
- 日志 MDC 结构化关联（grep/join 能力）
- 图级 trace 增强 + 可选后台自评审

**对齐参照（只读）：** `hermes-agent-main/tools/delegation_live_log.py`、`agent/moa_trace.py`、`tools/delegate_tool.py`（TUI 控制面）、`tools/async_delegation.py`（list_async_delegations）。⚠️ `hermes-agent-main/` **不可修改，只能读取**。

## 2. 澄清结论（决策依据）

| 决策 | 结论 | 依据 |
|---|---|---|
| 交付范围 | 核心三件套 + ④ trace 增强 + ⑤ BackgroundReviewer + Batch 3 遗留项承接（全量） | 用户拍板 |
| 直播日志粒度 | **生命周期 + 事件流**（assistant 文本 + toolResult + 终态摘要） | 用户选择；runAgent 的 blockingForEach 已在遍历事件，同步写成本低 |
| 图 trace 存储 | **内存 + 可选文件落盘**（opt-in，对齐 moa_trace.py） | 用户选择；无内存无界风险 |
| detectStale 调度 | 自建单线程 `ScheduledExecutorService`（代码库无 `@EnableScheduling`） | 技术核实 |
| 方案选型 | **方案 A：分层 + 端口模式**（沿用 Batch 3 port+impl + ObjectProvider 惯例） | 见 §3 |

## 3. 方案选型

**方案 A（采用）：分层 + 端口模式**
- `DelegationLiveLog` = domain 端口 + infrastructure 文件实现（同 `AsyncDelegationStore`/`PgAsyncDelegationStore`）；`SubagentLifecycleService` 用 `ObjectProvider<DelegationLiveLog>` 可选注入，无 Bean 时 no-op。
- `GraphExecutionRecorder` 落 domain `agent/service/agent/observability/`。
- `ExecutionControlService` 落 domain；`OrchestrationController` 落 aether-trigger。
- `StaleDelegationScanner` 用自建 `ScheduledExecutorService`。

否决方案 B（全塞 domain 直接文件 IO：破坏 infra 惯例）、方案 C（纯 OTel span：无法满足 tail-f/replay 诉求）。

## 4. 组件设计

### ① `DelegationLiveLog`（端口 + infrastructure 文件实现，对齐 delegation_live_log.py）

| 层 | 文件 | 职责 |
|---|---|---|
| domain 端口 | `aether-domain/.../agent/service/subagent/DelegationLiveLog.java`（interface） | `open(id, goal)` / `append(id, role, line)` / `flush(id)` / `close(id, summary)` / `tail(id, n)` |
| infra 实现 | `aether-infrastructure/.../deleg/FileDelegationLiveLog.java`（@Component） | 文件落地，见下 |

**文件布局**：`<base>/<delegationId>/task-0.log`（`base` = 属性 `aether.delegation.live-log-dir`，默认 `./cache/delegation/live`）。每次 dispatch = 单个子Agent任务 → `task-0.log`（对齐 hermes 每 task 一个 writer）。

**对齐 hermes 的纪律（必须逐条落实）：**
1. **崩溃安全**：append-mode 每次写入即 open+write+close，无长句柄（子Agent崩溃不丢已写行）。
2. **永不向上抛**：任何写失败首次触发即禁用该 writer（内部 `_ok=false`），降级 debug log；writer 失效不影响子Agent主流程。
3. **单行折叠 + 截断**：`assistant` 600 字符 / `result` 400 / 其他 500，超长加 `…(+N chars)` 标注。
4. **留存清理**：`open` 时机会性 prune 超 7 天的 `<delegationId>` 目录。
5. **时间戳行格式**：`HH:mm:ss role| line`（对齐 hermes `event()`）。

**接线（SubagentLifecycleService）**：
- `launch`：`open(id, goal)` 写 header（delegation id / goal / started）。
- `runAgent.blockingForEach`：已收集的 `textDelta` → `append(id, "assistant", text)`；`toolResult` → `append(id, "result", name + ok/err + output)`。
- COMPLETED / FAILED：`append(id, "final", 摘要)` + `close(id, summary)`。
- cancel / detectStale：`append(id, "final", "取消"/"超时")` + `close`。

**测试（@TempDir，不碰真实文件系统）**：append→读回、tail(id,n)、目录隔离、崩溃安全（重开读回）、留存 prune。lifecycle 集成用 fake 端口验证接线。

### ② `GraphExecutionRecorder` + ④ trace 增强

**`GraphExecutionRecorder`**（domain @Component，`agent/service/agent/observability/`，对齐 moa_trace.py opt-in 语义）：
- `String beginExecution(String sessionId)` → 生成 `graphExecutionId`（`gx-<8hex>`），写 MDC(`graphExecutionId`/`sessionId`)，注册执行上下文。
- `void recordNodeEvent(graphExecutionId, NodeEvent)` / `void endExecution(graphExecutionId, Throwable error)` / `List<NodeEvent> getExecutionTrace(graphExecutionId)`。
- `NodeEvent` record：`graphExecutionId, nodeName, agentType, status(GraphFlowState.NodeStatus), startedAt, finishedAt, durationMs, error`。
- **内存有界**：`ConcurrentHashMap<String, List<NodeEvent>>` + 保留最近 N=200 次执行（属性 `aether.graph.trace.retention`，默认 200），超出逐出最旧。
- **可选文件**：`aether.graph.trace.persistence=true` 时，单线程 executor 异步 append JSONL 到 `<aether.graph.trace.dir=./cache/graph-traces>/<graphExecutionId>.jsonl`（对齐 moa_trace.py `save_moa_turn` 的 best-effort：失败 debug log 不上抛）。

**接线（GraphExecutor，`@Autowired(required=false)` 可空注入，镜像 interventionHandler 模式）**：
- `execute()` 开头 `beginExecution`、末尾/异常分支 `endExecution`，`try/finally` 清理 MDC。
- `executeGraphFlow` 状态转换处 `recordNodeEvent`：L631 RUNNING、L647 SKIPPED、L677 COMPLETED、L719 FAILED。

**④ 手术式 trace 增强（纯增量，不改签名）**：
- `AgentTracer` 新增 `startGraphExecution(graphExecutionId, sessionId)`（span `graph.execute`）与 `startGraphNode(graphExecutionId, nodeName, agentType)`（span `graph.node.<type>.<id>`），及对应 end helper。
- `AgentEventPublisher.toJson` 合并当前 MDC 上下文：`graphExecutionId`/`sessionId`/`subagentId` 存在则并入 JSON。**不改任何 publish 方法签名**（避免触碰全部调用点）。
- MDC：`GraphExecutor.execute` set `graphExecutionId`+`sessionId`；`SubagentLifecycleService.runAgent` set `subagentId`；均在 try/finally 中 remove。

### ③ `ExecutionControlService` + HTTP 端点（复用 D1，对齐 delegate_tool.py TUI）

**`ExecutionControlService`**（domain @Service，`agent/service/subagent/`）：
- `List<ActiveSubAgentView> listActiveSubAgents()` — 组装 `lifecycle.activeIds()` + `status(id)` + `taskOf(id)`；`ActiveSubAgentView` record = `{id, status, sessionId, goal, parentAgentId}`（parentAgentId 恒 null，见 §5）。
- `boolean interruptSubAgent(String id)` → `lifecycle.cancel(id)`（对齐 hermes `interrupt_subagent` 返回是否命中）。
- `boolean setSpawnPaused(boolean)` → `spawnGate.setSpawnPaused`（对齐 hermes `set_spawn_paused`）。
- `List<DelegationRecord> listDelegations(String sessionId)` → sessionId 非空走 `asyncDelegationService.listBySession(sessionId)`；为空返回 active 运行时视图（id + status）。

**`OrchestrationController`**（aether-trigger 新文件，`http/OrchestrationController.java`，走现有 JWT 过滤链 + `Response<T>` 包装，风格对齐 `AgentServiceController`）：
```
GET  /api/orchestration/active-subagents          → listActiveSubAgents()
POST /api/orchestration/subagents/{id}/interrupt  → interruptSubAgent(id)
POST /api/orchestration/spawn/pause               → setSpawnPaused({paused})
GET  /api/orchestration/delegations?sessionId=    → listDelegations(sessionId)
```
DTO 用触发层内联 record（不污染 aether-api）。

### 承接 Batch 3 遗留项

| 遗留项 | 处置 |
|---|---|
| ① detectStale 无调度触发 | **新增 `StaleDelegationScanner`**（domain @Component，单线程 `ScheduledExecutorService`，@PreDestroy 关闭）；配置 `aether.delegation.stale-timeout`（默认 `PT10M`）+ `aether.delegation.stale-scan-interval-ms`（默认 `60000`），周期 `lifecycle.detectStale(timeout)` 并日志命中 id。**默认启用**（挂起子Agent占租约/池线程正是要解决的）。测试用手动触发 + 可注入 scheduler。 |
| ③ runtimes 无界增长 | `SubagentLifecycleService` 终态保留上限（默认 100，属性 `aether.subagent.terminal-retention`），超出逐出最旧终态；active 永不逐出。逐出后 `status/result(id)` 返回 empty。 |
| ⑤ 心跳长工具误判 | 保持按事件刷新；stale 窗口放大为可配置（默认 PT10M）。**残余限制**：无工具级 heartbeat 无法完全区分长工具调用与真挂死，javadoc 注明。 |
| ② ChildResultAggregator/DelegationBudget | **仍延后（YAGNI）**：Aether 为单任务 dispatch，无 batch fan-out，强行接线为推测性代码。 |
| ④ parentAgentId 恒 null | **维持现状**：ToolContext 无当前 agentId，javadoc 注明。 |

### ⑤ `BackgroundReviewer`（可选 stretch，默认关闭）

- domain `observability/BackgroundReviewer.java`，`@ConditionalOnProperty(name="aether.graph.background-review.enabled", havingValue="true", matchIfMissing=false)`。
- `submit(graphExecutionId, goal, finalOutput)`：丢入单线程池做模型评审；结果写回 `GraphExecutionRecorder`（追加一条 `NodeStatus.COMPLETED` REVIEW 节点事件）+ `AgentEventPublisher` 发布评审事件。
- 模型调用复用现有 ChatService 模型路径（实现计划里钉死具体接线）。默认关闭 → prod 无影响。

## 5. 错误处理与边界

- **直播日志写失败**：writer 首次失败即禁用，降级 debug log，绝不上抛进 agent 循环（对齐 hermes 纪律 2）。
- **图 trace 落盘失败**：best-effort，debug log，不影响主流程（对齐 moa_trace.py）。
- **MDC 泄漏**：`graphExecutionId`/`sessionId`/`subagentId` 全部 try/finally remove。
- **stale 判定**：`detectStale` 完成 future → `AsyncDelegationService.finalizeDelegation`（whenComplete）→ markTerminal + publish + releaseLease，租约自动释放（Batch 3 已验证）。
- **调度器空转**：无 RUNNING 子Agent时扫描为 O(1) 空遍历，不建新任务。
- **残余限制（诚实声明）**：长工具调用心跳边界（§4 承接项⑤）；parentAgentId 恒 null；ChildResultAggregator 无调用方。

## 6. 测试策略

- `FileDelegationLiveLogTest`（@TempDir）：append→读回、tail(id,n)、目录隔离、崩溃安全 reopen、留存 prune。
- lifecycle 直播日志集成：fake `DelegationLiveLog` 端口 → launch 后事件 append、COMPLETED/FAILED 摘要 close。
- `GraphExecutionRecorderTest`：record 序列 → getExecutionTrace 顺序；有界逐出；文件 toggle。
- `GraphExecutor` 集成：execute 后 recorder 收到节点事件（fake recorder）。
- `ExecutionControlServiceTest` + `OrchestrationControllerTest`：interrupt/list/pause 委托到 lifecycle/spawnGate；4 端点 `Response<T>` 包装。
- `StaleDelegationScannerTest`：手动触发 detectStale、配置生效。
- `BackgroundReviewerTest`：默认关 → 无 Bean；开 → 出评审事件。
- `AgentEventPublisher` MDC 富化：MDC 带 graphExecutionId → JSON 含字段。

## 7. 验收标准

- `mvn -pl aether-domain -am test`、`mvn -pl aether-infrastructure -am test`、`mvn -pl aether-trigger -am test` 全绿。
- 默认配置（不带 persistence/background-review 属性）下应用可启动，无 Critical（沿用 ObjectProvider 可选注入惯例）。
- 本批改动全部与 hermes 实现语义一致，`hermes-agent-main/` 零修改。

## 8. 风险与注意事项

1. **热路径侵入**：`GraphExecutor.execute` / `SubagentLifecycleService.runAgent` 是高频路径 → recorder/live-log 调用必须低开销（空短路、有界结构）。
2. **日志 IO**：直播日志 append-mode 每次 open/write/close，量大的子Agent会频繁 IO → 用单行小 buffer（对齐 hermes `_stream_buf`），不改异步缓冲架构。
3. **MDC 线程传递**：子Agent在 `SubagentLifecycleService` 的固定线程池上跑，MDC 必须在 `runAgent` 线程内 set/remove（不在 launch 线程）。
4. **控制器鉴权**：复用现有 JWT 过滤链，不新增开放端点。
5. **调度器生命周期**：`StaleDelegationScanner` @PreDestroy 关闭，避免测试/关闭时泄漏线程。
6. **hermes 只读约束**：所有对齐仅参考源码，不对 `hermes-agent-main/` 做任何修改。

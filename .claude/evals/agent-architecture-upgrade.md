# EVAL DEFINITION: agent-architecture-upgrade

**基线提交:** `03f6c86` — Agent 架构全面升级
**跨度:** P0-1, P0-6, P1-1, P1-2, P1-3, P1-4, P1-5, P1-6
**定义日期:** 2026-06-17

---

## 1. 回归评估（Release-Critical）

**阈值:** pass^3 = 100%（单次运行，所有测试必须通过）

| # | 评估项 | 评分方式 | 预期结果 |
|---|---|---|---|
| R1 | Maven 构建成功（全量编译） | `mvn clean compile -q` | 零错误退出 |
| R2 | 所有现有单元测试通过 | `mvn test -pl aether-domain -q` | 所有 58+ 测试通过 |
| R3 | `AgentEdgeType.fromYamlType()` 拒绝未知类型 | `GraphExecutorTest.agentEdgeTypeShouldThrowForUnknown` | 抛出 `AppException` |
| R4 | `AgentEdgeType.fromYamlType(null)` 安全返回 null | `GraphExecutorTest.agentEdgeTypeShouldReturnNullForNull` | 返回 `null` |
| R5 | 路由逻辑：空 edges → 单 Agent 路径 | `ChatServiceTest.emptyEdgesShouldRouteToSingleAgent` | edges 为空 |
| R6 | 路由逻辑：非空 edges → 多 Agent 路径 | `ChatServiceTest.nonEmptyEdgesShouldRouteToMultiAgent` | edges 非空 |
| R7 | `AgentConfig.fromNodeDef()` 保留所有字段 | `ChatServiceTest.agentConfigFromNodeDefShouldPreserveAllFields` | 正确映射 7 个字段 |
| R8 | `ExecutionState` forkSource 隔离子状态 | `GraphExecutorTest.executionStateShouldForkSource` | 子修改不影响父 |
| R9 | `ExecutionState.resolveTemplate()` 多键替换 | `GraphExecutorTest.executionStateShouldResolveTemplateWithMultipleKeys` | `{method}`, `{data}` 均替换 |
| R10 | `AgentState` 防御性拷贝 | `ReActAgentTest.agentStateShouldHaveDefensiveCopy` | `getMessages()` 返回新副本 |

---

## 2. 能力评估 — P0-1：Agent 工厂 + 核心合约

**阈值:** pass@3 >= 90%

### C1.1：Agent 接口合约完整性
- **验证内容:** `Agent` 接口定义了全部 8 个必需方法（`getId`, `getName`, `getDescription`, `getConfig`, `execute`, `getState`, `saveState`, `loadState`, `getCapabilities`）
- **评分方式:** 代码 —— `grep -c` 统计接口方法签名
- **已有测试:** `ReActAgentTest`（8 个测试）覆盖 `AgentConfig`, `AgentState`, `RuntimeContext`

### C1.2：ReActAgent 主循环完整流程
- **验证内容:** `ReActAgent.queryLoop()` 正确执行 Phase1→Phase4 循环，包括：
  - Phase 1：`ContextManager.applyToolResultBudget()` + `microCompact()` + `autoCompactIfNeeded()`
  - Phase 2：`ModelInvoker.callWithStream()` 带中间件链 `chain.applyModelCall()`
  - Phase 3：无 tool_use → 发送 `RuntimeEvent.done()` + `emitter.onComplete()`
  - Phase 4：`ToolExecutor.executeBatch()`，`tool_result` 转为 `TurnMessage.toolResult()`
- **评分方式:** 代码 —— 检查 `queryLoop()` 代码结构的静态分析
- **关键文件:** `ReActAgent.java:106-288`

### C1.3：AgentConfig 不可变性
- **验证内容:** `AgentConfig` 使用 `@Value` / `@Builder` —— 构造后字段不能修改；`toolNames` 是不可变列表
- **评分方式:** 代码 —— `grep "List.of()"` 在 `AgentConfig.java` 中
- **已有测试:** `ChatServiceTest.agentConfigShouldBeImmutableAfterBuild` + `agentConfigEqualityShouldWork`

### C1.4：AgentFactory 按类型创建 Agent
- **验证内容:** `DefaultAgentFactory.create(AgentConfig)` 根据 `config.getAgentType()` 返回正确的 Agent 子类（`"react"` → `ReActAgent`，未识别的类型 → 抛出 `AppException`）
- **评分方式:** 代码 —— 检查 `DefaultAgentFactory.java:104` 中的 map/switch 语句
- **关键文件:** `DefaultAgentFactory.java`

---

## 3. 能力评估 — P1-1：GraphFlow DAG 路由

**阈值:** pass@3 >= 90%

### C2.1：拓扑排序正确处理 DAG
- **验证内容:** `GraphExecutor.executeGraphFlow()` 算法正确：
  - 入口节点（parentCount == 0）首先执行
  - 子节点等待所有父节点完成后执行
  - 循环依赖（无入口节点）正确报错
- **评分方式:** 代码 —— 检查 `executeGraphFlow()` 中的拓扑排序逻辑
- **关键文件:** `GraphExecutor.java:280-463`

### C2.2：条件边评估（SpEL）
- **验证内容:** `ConditionEvaluator.evaluate()`：
  - `null` / `"true"` / 空字符串 → `true`（无条件，始终激活）
  - `"output.contains('x')"` → 正确评估 SpEL 表达式
  - 无效表达式 → `false`（不激活），不抛出异常
- **评分方式:** 代码 —— 检查 `ConditionEvaluator.evaluate()` 返回逻辑
- **关键文件:** `ConditionEvaluator.java:25-44`

### C2.3：激活语义（all vs any）
- **验证内容:** `executeGraphFlow()` 步调逻辑：
  - `activation = "all"`（默认）→ 所有父节点完成后才激活子节点
  - `activation = "any"` → 任一父节点完成后即激活子节点
- **评分方式:** 代码 —— 检查 `GraphExecutor.java:413-416` 中的激活逻辑
- **关键文件:** `GraphExecutor.java:412-428`

### C2.4：退出条件用于循环边
- **验证内容:** 循环边 `exitCondition` 满足时，`childState` 设为 `SKIPPED` 而非重新入队
- **评分方式:** 代码 —— 检查 `GraphExecutor.java:420-425`
- **关键文件:** `GraphExecutor.java:417-429`

### C2.5：AgentNodeDef 默认 agentType = "react"
- **已有测试:** `GraphExecutorTest.agentNodeDefShouldDefaultAgentTypeToReact`

### C2.6：GraphFlowState 就绪检测
- **验证内容:** `GraphFlowState.isReady()` 仅在 `status == PENDING` 且 `completedParents >= totalParents` 时返回 true
- **评分方式:** 代码 —— 检查 `GraphFlowState.java:43-45`
- **关键文件:** `GraphFlowState.java`

---

## 4. 能力评估 — P1-2：中间件洋葱链

**阈值:** pass@3 >= 90%

### C3.1：MiddlewareChain 按优先级排序
- **验证内容:** `MiddlewareChain.use()` → `middlewares.sort(Comparator.comparingInt(AgentMiddleware::priority))` —— 数值越小优先级越高
- **评分方式:** 代码 —— 检查 `MiddlewareChain.java:43` 的排序逻辑
- **关键文件:** `MiddlewareChain.java:41-47`

### C3.2：五个拦截点均正确调度
- **验证内容:** `MiddlewareChain` 中 5 个方法均遍历中间件：
  - `applySystemPrompt()` → 顺序遍历（外层先 → 内层后）
  - `applyReasoning()` → 顺序遍历
  - `applyActing()` → 顺序遍历
  - `wrapAgent()` → **逆序**遍历（内层先包装 → 外层后包装）—— 洋葱模型
  - `applyModelCall()` → **逆序**遍历（洋葱模型）
- **评分方式:** 代码 —— 验证正向/反向循环方向
- **关键文件:** `MiddlewareChain.java:53-116`

### C3.3：中间件异常隔离
- **验证内容:** `applySystemPrompt()`, `applyReasoning()`, `applyActing()` 中的异常被单独捕获（`try/catch` + `log.warn`），不中断整个链
- **评分方式:** 代码 —— 检查 catch 块
- **关键文件:** `MiddlewareChain.java:60, 85, 112`

### C3.4：RateLimitMiddleware 时间窗口限流
- **验证内容:** `RateLimitMiddleware.onAgent()` 在 `maxCallsPerWindow` 超限时返回 `RuntimeEvent.error()` 流，而不是调用 `next.get()`
- **评分方式:** 代码 —— 检查 `RateLimitMiddleware.java:51-56`
- **关键文件:** `RateLimitMiddleware.java:38-58`

### C3.5：PermissionMiddleware 工具过滤
- **验证内容:** `PermissionMiddleware.onActing()` 为每个 tool call 构建 `PermissionContext`，调用 `permissionEngine.check()`，过滤掉 `DENY` 请求
- **评分方式:** 代码 —— 检查 `PermissionMiddleware.java:38-78`
- **关键文件:** `PermissionMiddleware.java`

---

## 5. 能力评估 — P1-3：权限引擎

**阈值:** pass@3 >= 90%

### C4.1：BYPASS 模式绕过所有检查
- **验证内容:** `PermissionEngine.check(ctx, BYPASS)` 直接返回 `ALLOW`，不遍历规则
- **评分方式:** 代码 —— 检查 `PermissionEngine.java:43-46`
- **关键文件:** `PermissionEngine.java:41-62`

### C4.2：规则链 first-match-wins
- **验证内容:** 遍历 `rules` 列表，第一个返回非 null 决策的规则胜出
- **评分方式:** 代码 —— 检查 `PermissionEngine.java:49-56`

### C4.3：默认 DENY（安全优先）
- **验证内容:** 无规则匹配时返回 `DENY`
- **评分方式:** 代码 —— 检查 `PermissionEngine.java:58-61`

### C4.4：内置 ReadOnlyAllowRule 始终允许只读工具
- **验证内容:** `ctx.isReadOnly() == true` → `ALLOW`（优先级 10）
- **评分方式:** 代码 —— 检查 `PermissionEngine.java:68-78`

### C4.5：内置 PlanModeDenyWriteRule 阻止计划模式写操作
- **验证内容:** `ctx.getMode() == PLAN && !ctx.isReadOnly()` → `DENY`（优先级 20）
- **评分方式:** 代码 —— 检查 `PermissionEngine.java:80-93`

---

## 6. 能力评估 — P1-4：Memory 门面

**阈值:** pass@3 >= 90%

### C5.1：MemoryFacade 合约完整性
- **验证内容:** 接口定义了 6 个必需方法：`remember()`, `rememberMany()`, `recall()`, `search()`, `drain()`, `clear()`, `stats()`
- **评分方式:** 代码 —— `grep -c` 统计方法签名
- **关键文件:** `MemoryFacade.java`

### C5.2：DefaultMemoryFacade.remember() LLM 编码
- **验证内容:** 当 `options.useLLMEncoding() == true` 时调用 `encodingFlow.encode(content)`；当为 false 时使用 `EncodingFlow.EncodeResult.defaults()`
- **评分方式:** 代码 —— 检查 `DefaultMemoryFacade.java:41-46`
- **关键文件:** `DefaultMemoryFacade.java:37-110`

### C5.3：DefaultMemoryFacade 记忆合并
- **验证内容:** 当 `encoded.shouldConsolidate()` 且相似度 ≥ 阈值时，合并内容、合并分类、平均重要性
- **评分方式:** 代码 —— 检查 `DefaultMemoryFacade.java:49-74`
- **关键文件:** `DefaultMemoryFacade.java:48-75`

### C5.4：MemoryScope 作用域隔离
- **验证内容:** `MemoryScope` 枚举区分 `global()` / `user()` / `session()` / `agent()` 及 `isPrivate()`
- **评分方式:** 代码 —— 检查 `MemoryScope.java`
- **关键文件:** `MemoryScope.java`

### C5.5：RecallOptions 默认权重合理
- **验证内容:** 默认权重 `semanticWeight=0.6, recencyWeight=0.3, importanceWeight=0.1`（语义 > 时效 > 重要性）
- **评分方式:** 代码 —— 检查 `MemoryFacade.RecallOptions` 默认构造函数
- **关键文件:** `MemoryFacade.java:67-78`

---

## 7. 能力评估 — P1-5：可观测性

**阈值:** pass@3 >= 90%

### C6.1：AgentTracer Span 生命周期
- **验证内容:** `AgentTracer.startAgentTurn()` 返回的 `SpanScope` 实现 `AutoCloseable`（`close()` → `scope.close()` + `span.end()`）
- **评分方式:** 代码 —— 检查 `AgentTracer.SpanScope` 记录
- **关键文件:** `AgentTracer.java:107-113`

### C6.2：AgentTracer Span 属性设置
- **验证内容:** `startAgentTurn()` 设置 `agent.id`, `session.id`, `turn.number` 属性；`startModelCall()` 设置 `model.name`；`startToolCall()` 设置 `tool.name`, `tool.call_id`
- **评分方式:** 代码 —— 检查 Span 属性
- **关键文件:** `AgentTracer.java:38-61`

### C6.3：AgentTracer 结束方法记录指标
- **验证内容:** `endModelCall()` 记录 `token.input/output/total` 和 `cost.usd`；`endToolCall()` 记录 `tool.success` 和 `tool.error`
- **评分方式:** 代码 —— 检查 `AgentTracer.java:67-85`

### C6.4：AgentMetrics Micrometer 集成
- **验证内容:** `AgentMetrics.init()` 注册 7 个指标（2 个 Counter + 3 个 Timer / `turnCounter`, `errorCounter`, `toolCallCounter`, `toolErrorCounter`, `turnLatency`, `modelCallLatency`, `toolCallLatency`）
- **评分方式:** 代码 —— 检查 `AgentMetrics.java:33-66`
- **关键文件:** `AgentMetrics.java`

### C6.5：MetricsHook 自动注入
- **验证内容:** `MetricsHook` 使用 `@Component` 注解 → 自动装配为 Spring Bean；`onAfterExecute()` 调用 `metrics.recordTurn()`；`onError()` 调用 `metrics.recordError()`
- **评分方式:** 代码 —— 检查 `MetricsHook.java` 注解和实现
- **关键文件:** `MetricsHook.java`

---

## 8. 能力评估 — P1-6：Hooks 系统

**阈值:** pass@3 >= 90%

### C7.1：AgentHook 生命周期钩子合约
- **验证内容:** 接口定义了全部 8 个生命周期方法（`onBeforeExecute`, `onAfterExecute`, `onError`, `onBeforeModelCall`, `onAfterModelCall`, `onBeforeToolCall`, `onAfterToolCall`, `priority`）
- **评分方式:** 代码 —— 检查 `AgentHook.java`
- **关键文件:** `AgentHook.java`

### C7.2：CompositeHook 委托模式
- **验证内容:** `CompositeHook` 持有钩子列表，每个生命周期方法遍历所有钩子并委托调用
- **评分方式:** 代码 —— 检查 `CompositeHook.java`
- **关键文件:** `CompositeHook.java`

### C7.3：ReActAgent 钩子调用时机
- **验证内容:** `ReActAgent.execute()` 中：
  - `hooks` 由 `BaseAgent` 通过 `HookRegistry` 注入
  - `onBeforeExecute` / `onAfterExecute` 在 execute() 首尾调用
  - `onBeforeModelCall` / `onAfterModelCall` 在模型调用前后调用（带耗时记录）
  - `onBeforeToolCall` / `onAfterToolCall` 在工具调用前后调用（带耗时记录）
- **评分方式:** 代码 —— 检查 `ReActAgent.java` 中的钩子调用点
- **关键文件:** `ReActAgent.java:65-103, 163-178, 220-238`

---

## 成功指标

| 类别 | 指标 | 阈值 |
|---|---|---|
| 回归评估 | `mvn clean compile` + 所有现有测试通过 | pass^3 = 100% |
| 能力评估（7 个模块） | P0-1, P1-1, P1-2, P1-3, P1-4, P1-5, P1-6 | pass@3 >= 90% |
| P0-1 Agent 核心 | C1.1–C1.4（4 项） | pass@3 >= 90% |
| P1-1 GraphFlow DAG | C2.1–C2.6（6 项） | pass@3 >= 90% |
| P1-2 中间件链 | C3.1–C3.5（5 项） | pass@3 >= 90% |
| P1-3 权限引擎 | C4.1–C4.5（5 项） | pass@3 >= 90% |
| P1-4 Memory 门面 | C5.1–C5.5（5 项） | pass@3 >= 90% |
| P1-5 可观测性 | C6.1–C6.5（5 项） | pass@3 >= 90% |
| P1-6 Hooks 系统 | C7.1–C7.3（3 项） | pass@3 >= 90% |
| **总计** | **33 项能力检查 + 10 项回归检查** | |

---

## 评分方式图例

- **代码方式:** 确定性 —— 检查特定文件中的特定代码模式（grep / 静态分析）
- **构建方式:** 确定性 —— `mvn clean compile` / `mvn test`
- **模型方式:** LLM 评判 —— 用于无法用简单 grep 验证的行为（目前不使用；所有评分均为确定性代码/构建检查）

---

## 运行命令

```bash
# 当前检查状态
# 回归：/eval check agent-architecture-upgrade --regression
# 能力：/eval check agent-architecture-upgrade --capability
# 全部：/eval check agent-architecture-upgrade

# 生成报告
# /eval report agent-architecture-upgrade
```

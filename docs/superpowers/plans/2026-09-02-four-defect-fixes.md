# 四缺陷端到端修复计划（2026-09-02）

> 范围：`aether-domain` 内四个已定位缺陷。每个缺陷给出根因分析、修复方案、改动范围、验证方式与风险。
> 依据 CLAUDE.md 原则：手术式修改、简单优先；所有改动行均可追溯到对应缺陷。

---

## 问题一：ModelCallCache 缓存 key 32 位哈希碰撞 → 错误响应误命中

**文件**：`aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/ModelCallCache.java` L62-67

### 根因
```java
int hash = Objects.hash(modelName, messages.stream().map(m -> m.getText() != null ? m.getText() : "").toList());
return modelName + ":" + hash;
```
- `Objects.hash` 为 32 位 int。按生日界估算，缓存中约 77k 条不同对话即有 ~50% 概率出现一对碰撞；碰撞后 `get()` 直接返回**另一段对话的响应**（含 toolCalls），属于静默错误响应。
- 另一处加剧因素：key 只取 `getText()`、不含消息 role——user 消息与 tool_result 文本相同时也会同 key（本轮一并纳入 role 消除）。

### 影响面（读写路径）
- 写：`ModelInvoker.callWithStreamCached` / `callWithStreamCachedAsync`（未命中后 `put`）。
- 读：同上两处 `get`；L1 Caffeine（60s TTL）+ L2 Redis（`ModelCacheStore`，key 为不透明字符串）。
- key 格式变更后旧 L2 条目不可达，由 TTL 自然淘汰——无需迁移。

### 修复方案
`cacheKey` 改为 **SHA-256 全量摘要**（16 进制），输入 = modelName + 每条消息的 `(role, text)`，分隔符隔断拼接歧义；保留 `modelName + ":"` 前缀以维持日志可读性与按模型隔离的形状。SHA-256 由 JVM 规范保证存在，碰撞概率工程上可忽略，且计算成本相对 LLM 调用可忽略。

### 改动范围
- `ModelCallCache.cacheKey` 方法体 + 类头 javadoc 的 Key 描述（仅此两处）。

### 验证
`ModelCallCacheTest` 新增：
1. **碰撞回归**：`UserMessage("Aa")` 与 `UserMessage("BB")`（String.hashCode 相同，旧实现必碰撞）→ 新 key 必不相同。
2. 稳定性：同输入两次调用 key 相同。
3. 区分度：不同 modelName / 不同文本 → key 不同。

### 风险
- 低。key 为纯内部不透明字符串；put/get 同源。旧缓存条目一次性失效（可接受）。
- 遗留缺口（不在本次范围）：systemPrompt 未参与 key——同一 messages 不同 system prompt 理论上仍会误命中；后续跟进项。

---

## 问题二：ResilientChatModelExecutor.stream() 聚合为单帧 → 真流式 TTFT 失效

**文件**：`aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/ResilientChatModelExecutor.java` L221-231

### 根因
```java
public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> { ChatResponse response = call(prompt); return Flux.just(response); ... });
}
```
`stream()` 委托给同步 `call()`（内部走完整 failover 循环），把整个流聚合成**一帧**在结束后下发。下游 `ModelInvoker.callWithStreamingAsync`（真流式路径，`deltaSink` 逐 chunk 下推 SSE）订阅的是该 `stream()`——于是 TTFT 退化为"整轮生成完成时间"，O5 真流式灰度开关形同虚设。

### 约束分析
- 流式重试只能发生在**首帧之前**：一旦帧已下发，重放会导致下游收到重复内容（无幂等序号机制），因此首帧后失败只能原样传播。
- `call()` 的 failover 账本（`TurnRetryState`）各分支均有次数上限（退避 ≤ maxAttempts、压缩 2、凭据 1、fallback = 链长），重试必然终止，递归重建流不会无限循环。

### 修复方案
`stream()` 改为订阅级 failover：每次尝试 `currentChatModel.stream(prompt)` 逐帧透传；
- 首帧前失败 → 按 `TurnRetryState.nextDirective` 执行与 `call()` 相同的恢复分支（退避/压缩/凭据轮换/fallback 切换），然后重建流重试；
- 首帧后失败 → 原样传播（不重放）；
- 流完成 → 与 `call()` 对齐的 fallback 恢复标记（`fallbackActivated=false`）。

逐帧透传使 `callWithStreamingAsync` 的 `deltaSink` 恢复"chunk 到达即下发"语义，TTFT ≈ 首网络包延迟。退避等待复用既有 `backoffWaiter` 测试缝隙（与 `call()` 行为一致）。

### 改动范围
- `ResilientChatModelExecutor`：`stream()` 重写 + 新增私有 `streamWithRecovery()`；`call()` 中 maxAttempts 计算提为私有方法 `resolveMaxAttempts()`（两处共用）；imports 增 `AtomicBoolean`；`stream()` javadoc 重写。

### 验证
`ResilientChatModelExecutorTest` 新增三个用例：
1. 正常流：上游 3 帧 → 下游按序收 3 帧（不再聚合成 1 帧）。
2. 首帧前失败：第 1 次订阅抛 RATE_LIMIT 异常 → 退避后重试成功 → 收到全部帧且上游被调用 2 次。
3. 首帧后失败：先发 1 帧再 error → 下游收 1 帧 + error，上游仅被调用 1 次（验证不重放）。

### 风险
- 中。ModelInvoker 三条路径（真流式 / 缓冲 async / 同步 collectList）均兼容多帧流，无接口变更。
- `backoffWaiter` 阻塞等待发生在错误信号线程：与 `call()` 现状一致；若上游为纯 Netty event loop 线程存在阻塞风险——生产路径 ReActAgent 订阅在调用线程、错误信号亦来自阻塞式 HTTP 客户端，风险可控。如后续接入纯响应式 provider，可换 `Mono.delay` 非阻塞退避（跟进项）。

---

## 问题三：persistState 伪持久化 + PAUSED 状态被覆写

**文件**：`aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/impl/ReActAgent.java` L903-911、L150-153

### 根因
1. **伪持久化**：`persistState` 调 `saveState()` 后把结果丢弃，仅打日志——挂起瞬间没有任何快照落盘。生产唯一落盘点是 `SessionPersistenceHook.onAfterExecute`（execute 收尾后），但此时：
2. **PAUSED 覆写**：`queryLoop` 内 `handlePermissionSuspend` 置 PAUSED 后 `return`；`execute()` 收尾**无条件** `state.setStatus(IDLE)`（L152）——内存状态被覆写，且紧随其后的 `onAfterExecute` 持久化的快照 status=IDLE。恢复侧 `ChatService` 依赖 `status==PAUSED && hasPendingAsking()` 判定挂起，覆写后确认恢复协议（H4）整体失效：`EvalEngines.runPermission` L201-204 被迫在测试里手工补设 PAUSED 绕过此 bug。

### 修复方案
1. **真实持久化**：`ReActAgent` 增加可选 `SessionRepository`（setter 注入，对齐既有 `hookRegistry`/`modelCallObservability` 的可选注入模式；`SessionPersistenceHook` 已示范 domain 内直接使用该接口，无分层问题）。`persistState` 序列化 `saveState()` → `SessionEntity(status="ACTIVE", stateJson)` 异步落盘（介质=SessionRepository→PostgreSQL/Redis，格式=saveState JSON，与 SessionPersistenceHook 完全同构；实体级 status 沿用 "ACTIVE"，agent 的 PAUSED 存于 stateJson——与现有语义一致）。未注入时降级为 debug 日志（单测/无持久化场景不破坏）。
2. **状态保护**：`execute()` 收尾仅当 `status==RUNNING` 才置 IDLE；PAUSED 保持不变。随后 `onAfterExecute` 持久化的快照自然携带 PAUSED，双写内容一致。
3. **接线**：`DefaultAgentFactory` react 工厂 `resolveBean(SessionRepository.class)` 注入。

### 改动范围
- `ReActAgent`：字段 + setter、`persistState` 实现、`execute()` L152 守卫、import。
- `DefaultAgentFactory`：react 工厂注入一行。
- `EvalEngines` 的手工补设 PAUSED 变为冗余但无害（状态本就保持 PAUSED），不改。

### 验证
新增 `ReActAgentStatePersistenceTest`：
1. **挂起落盘 + 状态保护**：脚本化 ModelInvoker 返回工具调用 + 测试用中间件登记 ASKING → execute 结束后：内存 status 仍为 PAUSED；`SessionRepository.save` 捕获的实体 stateJson 解析后 `status=="PAUSED"` 且 permissionContext 非空。
2. **恢复回路**：新 ReActAgent 实例 `loadState(挂起快照)` → 带 confirmResults 的 ctx execute → 批准的工具真实触达 ToolExecutor、流程走完出现 done 事件（对齐 ChatService.handleConfirm 生产协议）。

### 风险
- 低-中。挂起路径新增一次异步写（失败仅记 error 日志，不阻断挂起流程，与 SessionPersistenceHook 容错语义一致）。
- 挂起时 persistState 与 SessionPersistenceHook 会各写一次相同快照（内容一致，幂等 upsert，可接受）。

---

## 问题四：MiddlewareChain.applyActing 吞异常 → 权限链 fail-open

**文件**：`aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/middleware/MiddlewareChain.java` L105-116

### 根因
`applyActing` 对每个中间件的 `onActing` 包 try/catch：异常仅 `log.warn` 后**继续用上一个 result 走完链路**。对 `PermissionMiddleware`（priority=20，权限校验）而言，`permissionEngine.check()` 一旦抛异常（引擎故障/数据不可用），请求会**未经过滤地放行**给 ToolExecutor——fail-closed 设计被中间件层的 fail-open 击穿。其余拦截点（onSystemPrompt/onReasoning）fail-open 属可接受的降级（提示词变换失败不应杀死运行），不在本次范围。

### 修复方案
`applyActing` 改为 fail-closed：任一中间件异常 → `log.error`（含堆栈，便于审计）→ **立即中断链路**（后续中间件不再执行）→ 返回**空列表**（= 拒绝本批全部工具调用，与 PermissionMiddleware DENY 的"不放行"语义一致）。

### 改动范围
- 仅 `MiddlewareChain.applyActing` 方法体 + javadoc。

### 验证
新增 `MiddlewareChainTest`：
1. 中间件 onActing 抛异常 → 返回空列表，且**后续中间件未被调用**（短路验证）。
2. 无中间件 → 请求原样透传。
3. 正常链按优先级依次变换。

### 风险
- 低-中。行为变化点：中间件异常时本批工具调用从"放行"变"全部拒绝"——这正是修复目标。
- 已知遗留（与 DENY 路径共有的既有缺口，不在本次范围）：被拒绝的 toolCallId 不回注 ToolResult 消息，下一轮 assistant tool_calls 与 tool 结果配对可能不完整，provider 可能报 400 → 走 modelResult.error 退出。O10 护栏已示范"拒绝结果仍回注保持配对"，建议后续统一对 DENY/异常路径补配对回注。

---

## 执行顺序与验证汇总

| 步骤 | 内容 | verify |
|---|---|---|
| 1 | 问题一修复 + 测试 | `ModelCallCacheTest` 全绿 |
| 2 | 问题四修复 + 测试 | `MiddlewareChainTest` 全绿 |
| 3 | 问题二修复 + 测试 | `ResilientChatModelExecutorTest` 全绿 |
| 4 | 问题三修复 + 测试 | `ReActAgentStatePersistenceTest` 全绿 |
| 5 | 模块回归 | `mvn -pl aether-domain -am test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false` |

> 命令备注：本仓库 surefire 3.5.2 在 `-am` 反应堆下单测筛选必须带 `-Dsurefire.failIfNoSpecifiedTests=false`，否则无匹配测试的模块（aether-types 等）报错。

**统一验收标准**：四个新测试类全绿 + aether-domain 模块既有测试无回归。

---

## 验证结果（2026-09-02 实测）

| 范围 | 结果 |
|---|---|
| 四个针对性测试类（22 用例） | **22/22 通过** |
| aether-domain 全模块回归 | **526/526 通过**，BUILD SUCCESS |
| aether-app（下游，含 eval harness） | **98/98 通过**，BUILD SUCCESS |

> 环境备注：本机 JDK21 需 `-DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"` 保证 Mockito inline mock 自附加；当日系统提交内存耗尽曾导致 fork JVM 崩溃（表象为 MockMaker 初始化失败），释放内存后恢复。

### 验证过程中发现的两处既有隐患（本次未改动，仅记录）

1. **queryLoop L239-241 自清空隐患**：`state.messagesMutable().clear(); addAll(messages);` 若 ContextManager 的 `applyToolResultBudget`/`microCompact` 返回**同一列表实例**（而非新列表），会把整段历史清空。现有实现返回新列表故无实际问题，但这是一个脆弱契约（测试 mock 曾踩中）。
2. **恢复路径不发 toolResult SSE 事件**：`applyConfirmResults` 批准工具后只把结果写回 `state.messages`，不发射 `toolResult` 事件（主循环 Phase 4 才发）；前端在恢复轮次看不到该工具的执行结果事件。与 EvalEngines 断言口径一致（按工具触达判定），属既有行为。

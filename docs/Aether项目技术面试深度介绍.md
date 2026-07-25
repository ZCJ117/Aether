# Aether 项目技术面试深度介绍

> 基于项目实际源码生成，所有内容均可追溯到具体文件与代码片段。

---

## 1. 项目一句话概述（30 秒电梯演讲）

**Aether 是一个企业级多 Agent 协作架构**——面向需要复杂 AI 工作流编排的开发者/企业，通过 **YAML 配置驱动 + DDD 六边形架构 + 自研 Agent 引擎**，让用户无需编码即可定义和运行多 Agent 协作流程（串行/并行/循环/DAG），并支持异构模型混用、MCP/Skills 工具集成、多层记忆系统和 OpenTelemetry 全链路可观测。

---

## 2. 项目背景与要解决的实际问题

### 2.1 核心痛点

- **多 Agent 编排门槛高**：AutoGen、CrewAI 等框架多为 Python 生态，Java 企业级方案缺失。构建一个多 Agent 工作流需要大量样板代码。
- **异构模型管理复杂**：不同 Agent 适合不同模型（GPT-4o 擅长推理，Claude 擅长长文本，DeepSeek 性价比高），但缺乏统一的配置化切换机制。
- **工具集成碎片化**：MCP 协议工具、Skills 技能库、本地函数需要统一注册和调度，且需处理并发安全。
- **上下文窗口爆炸**：多轮对话 + 大量工具调用结果容易超出 LLM 上下文限制，导致对话中断或成本飙升。
- **企业级可观测性缺失**：缺少分布式追踪、指标采集、结构化日志，线上问题难以定位。

### 2.2 解决思路

设计一个"YAML 即工作流"的 Agent 平台：开发者只需编写 YAML 配置描述 Agent 角色、工具和工作流拓扑，框架在启动时自动装配完整的运行时，请求时按图编排执行。

---

## 3. 完整技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 17 | 主开发语言 |
| 框架 | Spring Boot | 3.4.3 | 应用框架 + DI |
| 构建 | Maven | 3.x | 多模块构建管理 |
| AI SDK | Spring AI (BOM) | 1.1.0-M3 | LLM 调用抽象层 |
| 工具协议 | MCP SDK + spring-ai-agent-utils | 0.4.2 | MCP 工具 / Skills 集成 |
| 响应式流 | RxJava 3 | 3.1.9 | Agent 执行流（Flowable） |
| 缓存 | Guava | 32.1.3-jre | 本地缓存 |
| JSON | Jackson 2.x | — | domain 层序列化 |
| JSON (config) | FastJSON | 2.0.28 | 配置层解析 |
| 数据库 | MySQL + MyBatis + HikariCP | 8.0.28 / 3.0.4 | 会话持久化 |
| 向量数据库 | PostgreSQL + Pgvector | — | 语义记忆存储 |
| 可观测性 | OpenTelemetry + Micrometer + Prometheus | — | 链路追踪 + 指标 |
| 日志 | Logback + LogstashEncoder | 7.4 | 结构化 JSON 日志 |
| 测试 | JUnit 5 + Mockito | — | 单元测试 |
| 设计框架 | xfg-wrench-starter-design-framework | 3.0.0 | 策略树/责任链模式 |
| 前端 | Vue 3 + Pinia + Vue Router + Vite + Axios | — | 聊天 UI |
| 部署 | Docker + bash 构建脚本 | — | 容器化部署 |

---

## 4. 系统架构

### 4.1 DDD 六边形架构（分层视图）

```
┌──────────────────────────────────────────────────────┐
│  trigger (HTTP 层)                                    │
│  AgentServiceController + MdcFilter                   │
│  POST /api/v1/chat, /api/v1/chat_stream (SSE)        │
└────────────────────┬─────────────────────────────────┘
                     │ depends on
┌────────────────────▼─────────────────────────────────┐
│  aether-api (接口 + DTO)                              │
│  IAgentService, ChatRequestDTO, ChatResponseDTO       │
│  Response<T> 统一响应包装                               │
└────────────────────┬─────────────────────────────────┘
                     │ depends on
┌────────────────────▼─────────────────────────────────┐
│  aether-domain ★★★ 核心业务层                          │
│  ┌──────────────────────────────────────────────┐    │
│  │ Agent 接口体系 (Agent → BaseAgent → ReActAgent)│    │
│  │ + Hook 生命周期 (7 个拦截点)                    │    │
│  │ + Middleware 洋葱 (5 个拦截层)                  │    │
│  │ + Permission 权限引擎 (规则链)                  │    │
│  ├──────────────────────────────────────────────┤    │
│  │ 模型层: ModelProvider SPI + ProviderRegistry   │    │
│  │ 运行时: ModelInvoker + ContextManager +        │    │
│  │         ToolExecutor + GraphExecutor           │    │
│  │ 记忆: MemoryFacade + RecallFlow + VectorStore  │    │
│  │ 会话: SessionRepository (Port 接口)            │    │
│  │ 事件: AgentEvent (10 种多态事件)                │    │
│  │ 可观测: AgentTracer + AgentMetrics              │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  aether-infrastructure (适配器层) ── 实现 domain 端口   │
│  MySqlSessionRepository / RedisSessionRepository      │
│  PgvectorVectorStore                                  │
│                                                       │
│  aether-types (枚举/异常/常量)                          │
│  ResponseCode, AppException, Constants                │
└──────────────────────────────────────────────────────┘
```

**依赖规则：** `trigger → api → domain`；`infrastructure` 实现 `domain` 端口；`domain` 禁止反向依赖 `trigger`/`infrastructure`。

### 4.2 启动时装配流程（ApplicationReadyEvent）

```
ApplicationReadyEvent 触发
  → AiAgentAutoConfig.onApplicationEvent()
    → ArmoryService.acceptArmoryAgents(tables)         — YAML List 迭代
      → DefaultArmoryFactory.armoryStrategyHandler() → RootNode
        → AiApiNode         — 解析 baseUrl/apiKey/model，构建 ModelConfig
                              通过 ModelProviderRegistry 解析 ModelProvider
        → ChatModelNode     — 构建 MCP/Skills ToolCallbacks
                              调用 provider.createChatModelWithTools()
                              注册 Tool 到 ToolRegistry（适配 McpToolAdapter/SkillsToolAdapter）
                              注册 ChatModel Bean 到 Spring 容器
                              注册 Per-Agent ChatModel Bean（P0-3 异构模型）
        → AgentNode         — 收集 agent 名称列表
        → AgentWorkflowNode — 按 type 路由到 workflow 子节点
            → SequentialAgentNode / ParallelAgentNode / LoopAgentNode
              (各记录元数据后回环到 AgentWorkflowNode 处理下一个 workflow)
        → CompilerNode      — AgentGraphCompiler.compile(config)
                              注册 AgentGraph 到 AgentRegistry
```

**关键设计决策（Trade-off）：**

| 决策 | 为什么这么做 | 还考虑过什么 |
|------|-------------|-------------|
| 启动时一次性装配 | 运行时零开销，配置错误启动即发现 | 运行时动态创建 Agent（更灵活但增加请求延迟） |
| 策略树链式装配 | 每个节点职责单一，可独立测试和替换 | 直接在 Compiler 中一次性处理（简单但不可扩展） |
| DynamicContext 传递共享状态 | 避免节点间参数传递的复杂性 | 每个节点返回独立结果后合并（类型不安全，容易遗漏） |
| 策略树（xfg-wrench）框架 | 提供标准化的责任链 + 策略路由能力 | 纯手写责任链（重复代码多，缺少统一抽象） |

### 4.3 请求时路由（ChatService）

```
POST /api/v1/chat → AgentServiceController → ChatService.handleMessage()
  │
  ├─ graph.getEdges() 为空 → 单 Agent 路径
  │   1. AgentFactory.create(agentConfig)           — 创建 Agent 实例
  │   2. sessionRepository.findBySessionId()         — 恢复会话
  │   3. injectMemory()                               — 注入记忆（语义搜索 + 文件回退）
  │   4. agent.execute(ctx)                           — ReAct 主循环
  │      └─ queryLoop() → Think-Act-Observe (最大 100 轮)
  │
  └─ graph.getEdges() 非空 → 多 Agent 路径
      GraphExecutor.execute(graph)
        ├─ SEQUENTIAL → 串行执行，{outputKey} 模板传递
        ├─ PARALLEL   → CachedThreadPool 并发 + CountDownLatch(10min)
        ├─ LOOP       → 循环迭代至收敛（连续输出相同）
        └─ GRAPHFLOW  → 拓扑排序 + 就绪队列 + SpEL 条件路由
```

---

## 5. 核心模块详解

### 5.1 Agent 接口体系

**文件位置：** `aether-domain/.../agent/core/Agent.java` 等

#### 模块职责
定义 Agent 的统一契约：身份标识、执行入口、生命周期钩子、状态序列化、能力声明。

#### 核心类协作

```
Agent (Interface)
  ├─ getId(), getName(), getDescription()      — 身份
  ├─ execute(RuntimeContext) → Flowable<RuntimeEvent>  — 执行（RxJava 流式输出）
  ├─ onBeforeExecute / onAfterExecute / onError       — 生命周期（default 空方法）
  ├─ saveState() → Map / loadState(Map)              — 状态序列化
  └─ getCapabilities() → List<String>               — 能力声明
      ↑
BaseAgent (Abstract Class)
  ├─ AgentConfig config (immutable @Value)           — 构建时固化
  ├─ AgentState state (mutable)                      — 运行时状态
  ├─ List<AgentHook> hooks (优先级排序)               — 横切关注点
  ├─ addHook() → 自动排序 | saveState() → LinkedHashMap
  └─ loadState() → 反序列化 + 重置状态
      ↑
ReActAgent (Concrete) ※ 核心
  ├─ ChatModel, ModelInvoker, ToolExecutor, ContextManager
  ├─ execute() → 构建 MiddlewareChain → queryLoop()
  └─ queryLoop() → 5 阶段循环 (详见 5.2)
```

#### 设计 Trade-off

- **`Agent` 接口非常薄（6 个方法）**：参考了 AutoGen 的 `ChatAgent` 协议和 CrewAI 的 `BaseAgent`。保持最小接口使得未来扩展 `FlowAgent`、`UserProxyAgent` 等实现只需要满足 6 个方法。
- **`BaseAgent.saveState()` 返回 `LinkedHashMap`**：保序性确保反序列化时消息顺序不丢失，代价是比 `HashMap` 稍微多占内存。
- **`AgentState` 双模式访问**：`getMessages()` 返回 `List.copyOf()` 防御性副本（给 ContextManager/ModelInvoker 读），`messagesMutable()` 返回原始引用（给 ReActAgent 内部写）。这防止了外部代码意外修改对话历史——灵感来自 AgentScope Java 的 AgentState 设计。

### 5.2 ReActAgent 主循环（最核心模块）

**文件位置：** `aether-domain/.../agent/impl/ReActAgent.java`（约 250 行）

#### 执行流程（5 阶段循环）

```
while (turnCount < 100):
  ┌─ Phase 1: ContextManager 三层压缩 ────────────────────┐
  │  1a. 消息数量截断: >500 条 → 保留首条 + 最近 200 条      │
  │  1b. applyToolResultBudget(): 单结果 >50KB → 截断到 500 字│
  │  1c. microCompact(): 合并连续同角色消息 + 冗余编辑去重    │
  │  1d. autoCompactIfNeeded(): Token 阈值触发 LLM 摘要压缩  │
  ├─ Phase 2: Reasoning (模型调用) ─────────────────────────┤
  │  2a. convertToSpringMessages()                         │
  │  2b. chain.applyReasoning()                            │
  │  2c. onBeforeModelCall() Hook                          │
  │  2d. chain.applyModelCall() → ModelInvoker (重试+退避)  │
  │  2e. onAfterModelCall() Hook                           │
  ├─ Phase 3: Exit Check ──────────────────────────────────┤
  │  无 tool_use → emit done + 退出循环                     │
  ├─ Phase 4: Acting (工具执行) ────────────────────────────┤
  │  4a. onBeforeToolCall() Hook                           │
  │  4b. chain.applyActing() → PermissionEngine 权限检查    │
  │  4c. ToolExecutor.executeBatch()                       │
  │  4d. onAfterToolCall() Hook                            │
  │  4e. feed tool_result 回对话历史                         │
  └─ 连续 3 轮全部工具失败 → 强制退出 (安全阀)               │
```

#### 关键设计决策

| 设计点 | 为什么 | Trade-off |
|--------|--------|-----------|
| **上下文先压缩再发模型** | 防止超 token 限制导致 API 400 错误 | 压缩后的语义可能丢失细粒度信息 |
| **microCompact 去重文件编辑** | 多次编辑同一文件时只保留最终版本，节省 token | 需要正确识别工具类型和多文件路径 |
| **最大 100 轮硬限制** | 防止失控循环耗尽 API 额度 | 复杂任务可能需要更多轮次 |
| **连续失败检测（3 次）** | 防止模型反复调用已损坏的工具陷入死循环 | 阈值可能太敏感或太迟钝 |
| **`ToolResponseMessage` 而非 `UserMessage`** | 符合 OpenAI/Anthropic API 规范，避免 400 错误 | 需要在转换逻辑中显式处理 |
| **RxJava `Flowable` 作为返回类型** | 支持背压的流式输出到 SSE，前端实时渲染 | 增加了响应式编程的复杂度 |

### 5.3 洋葱中间件体系

**文件位置：** `aether-domain/.../agent/middleware/AgentMiddleware.java` 等

#### 五层拦截 + 洋葱组合

```
请求进入
  │
  ├─ onSystemPrompt(String) → String     ← 线性链（提示词变换）
  │     └─ RateLimitMiddleware: 注入时间/用户偏好
  │
  ├─ onAgent(Agent, ctx, next)           ← 洋葱外层（最外层包装）
  │     ├─ GracefulShutdownMiddleware (p=5)  : 关闭期间拒绝新请求
  │     └─ RateLimitMiddleware (p=10)        : 滑动窗口限流
  │
  ├─ onReasoning(List<Message>)          ← 线性链（消息变换）
  │     └─ 注入 RAG 上下文 / 记忆
  │
  ├─ onModelCall(next)                    ← 洋葱内层（模型调用包装）
  │     └─ Token 计数 / 缓存 / fallback 切换模型
  │
  └─ onActing(List<ToolCallRequest>)     ← 线性链（工具过滤）
        └─ PermissionMiddleware: 权限检查 + 过滤
```

#### MiddlewareChain 的关键实现

`wrapAgent()` 和 `applyModelCall()` 使用**反向迭代 + Supplier 包装**实现洋葱：

```java
// 简化逻辑：从高优先级（内层）向低优先级（外层）包裹
Supplier<Flowable<RuntimeEvent>> wrapped = coreSupplier;
for (int i = middlewares.size() - 1; i >= 0; i--) {
    AgentMiddleware m = middlewares.get(i);
    Supplier<Flowable<RuntimeEvent>> inner = wrapped;
    wrapped = () -> m.onAgent(agent, ctx, inner);
}
return wrapped.get();
```

这与 Koa/Express 的 `compose()` 模式相同——最内层是核心逻辑，每个中间件包裹一层。

#### 内置中间件详解

**RateLimitMiddleware**（`agent/middleware/impl/RateLimitMiddleware.java`）：
- 基于 `ConcurrentHashMap<String, WindowCounter>` 的滑动窗口计数
- Key 为 `userId:agentId`，窗口过期后自动重置
- 触发时限流返回 `RuntimeEvent.error("请求过于频繁")`
- 优先使用 `synchronized(counter)` 而非 `java.util.concurrent.locks.Lock`（这里窗口计数器是 HashMap 值对象，锁竞争低）

**GracefulShutdownMiddleware**（同目录 `GracefulShutdownMiddleware.java`）：
- `AtomicBoolean shuttingDown` 控制
- 关闭期间拒绝新请求，已在执行的请求不受影响
- `initiateShutdown()` 可由外部（如 K8s preStop hook）调用

**PermissionMiddleware**（同目录 `PermissionMiddleware.java`）：
- 实现 `AgentMiddleware.onActing()`，在每个工具执行前检查权限
- 从 `RuntimeContext.metadata` 中读取 `permissionMode`
- 调用 `PermissionEngine.check()` → `ALLOW`/`DENY`/`ASK_USER`
- `ASK_USER` 当前实现为"暂允许 + 日志记录"（完整 HITL 需要暂停执行流）

### 5.4 模型提供商可插拔

**文件位置：** `aether-domain/.../model/ModelProvider.java` 等

#### SPI 接口

```java
public interface ModelProvider {
    String providerName();              // "openai", "anthropic", "dashscope"
    boolean supports(String modelId);   // 能否处理该 modelId
    ChatModel createChatModel(ModelConfig config);
    ChatModel createChatModelWithTools(ModelConfig config, List<ToolCallback> tools);
    default String defaultCompletionsPath() { return "v1/chat/completions"; }
}
```

#### 内置 Provider

| Provider | supports() 逻辑 | 说明 |
|----------|----------------|------|
| OpenAI | `modelId` 包含 "gpt" 或不匹配其他任何 provider | **兜底 provider**（注册表排序时排最后） |
| Anthropic | `modelId` 包含 "claude" | 适配 Anthropic API |
| DashScope | `modelId` 包含 "qwen" 或 "dashscope" | 阿里云百炼平台 |

#### 注册表设计

`ModelProviderRegistry`：
- `@PostConstruct` 自动发现所有 `ModelProvider` Spring Bean
- OpenAI 排序在最后（`CopyOnWriteArrayList` + 自定义排序），作为 fallback
- `resolve(modelId)` → 遍历 provider 调用 `supports()` → 返回第一个匹配
- 支持运行时 `register()` 动态添加

#### 为什么用 `supports(modelId)` 而非 `providerName`

因为配置侧只指定 `model`（如 `deepseek-chat`），而非 `provider: openai`。`supports()` 机制让使用者无需了解 provider 概念，只需写模型名——框架自动路由到正确的后端。这与 Spring 的 `@Conditional` 理念类似。

### 5.5 多 Agent 图执行器

**文件位置：** `aether-domain/.../executor/GraphExecutor.java`

#### 四种执行模式

| 模式 | 算法 | 同步机制 | 收敛条件 |
|------|------|----------|----------|
| SEQUENTIAL | for 循环 subAgents，`state.resolveTemplate()` 解析 `{outputKey}` | 单线程 N/A | 遍历完 subAgents |
| PARALLEL | `CachedThreadPool` + `CountDownLatch(10min)` | `synchronized(emitter)` + `CopyOnWriteArrayList` | Latch 归零 |
| LOOP | 外层 for(maxIterations)，内层串行执行 subAgents | 单线程 | `currentOutput.equals(previousOutput)` |
| GRAPHFLOW | 拓扑排序 + `ConcurrentLinkedQueue` 就绪队列 + 50 轮安全上限 | `ConcurrentHashMap` 输出 + `GraphFlowState` | 所有节点 COMPLETED |

#### GraphFlow DAG 执行细节（P1-1）

```
1. 构建邻接表 (children: Map<nodeId, List<AgentEdge>>)
   和入度表 (parentCount: Map<nodeId, Integer>)
2. 找到入度 == 0 的节点作为 entry nodes
3. 创建 GraphFlowState per node (AtomicInteger 跟踪父节点完成数)
4. 主循环 (max 50 iterations):
   a. 收集所有 status==PENDING && 所有父节点已完成的节点到就绪队列
   b. 并发执行就绪节点 (CachedThreadPool)
   c. 收集输出到 ConcurrentHashMap
   d. 对每个完成节点的出边评估 ConditionEvaluator
      - activation="all" (fan-in): 所有父节点完成才激活
      - activation="any": 任意父节点完成即激活
      - exitCondition 为 true: 标记子节点为 SKIPPED
   e. 标记节点为 COMPLETED/FAILED
5. 收集最终输出 → 合并到 globalState → emit done
```

#### Parallel 模式的事件安全转发

`GraphExecutor.executeParallel()` 中的关键代码段（简化）：

```java
CountDownLatch latch = new CountDownLatch(subAgents.size());
for (String agentName : subAgents) {
    parallelPool.submit(() -> {
        try {
            ExecutionState subState = mainState.forkSource();
            Agent agent = agentFactory.create(agentConfig);
            agent.execute(ctx).subscribe(event -> {
                synchronized (emitter) { emitter.onNext(event); }
            });
            subStates.add(subState);
        } finally { latch.countDown(); }
    });
}
latch.await(10, TimeUnit.MINUTES); // P0-6: 超时保护
```

**关键点：** `synchronized(emitter)` 保证并发事件的发送线程安全；`forkSource()` 为每个子 Agent 创建独立的 `ExecutionState` 副本，避免并发写入冲突。

### 5.6 上下文压缩引擎（三层压缩）

**文件位置：** `aether-domain/.../context/ContextManager.java`

#### 三层压缩详解

```
Layer 1: applyToolResultBudget()   [成本: O(n), 无损压缩]
  ├─ 扫描所有 tool_result 消息
  ├─ 单条结果 > 50,000 字符 → 截断为 500 字符 + 警告
  └─ 目的: 大搜索结果/文件内容不直接撑爆上下文

Layer 2: microCompact()            [成本: O(2n), 无损压缩]
  ├─ Pass 1: 构建 path → lastWriteIndex 映射
  │   └─ 扫描所有 tool_use 消息，识别文件编辑工具（Edit/Write/BashTool）
  │       通过 extractPath() 从参数 JSON 中提取文件路径
  ├─ Pass 2: 保留最后一次写入 + 非编辑工具消息
  └─ 目的: 对同一文件多次编辑，只保留最终版本

Layer 3: autoCompactIfNeeded()     [成本: 需 LLM 调用, 有损压缩]
  ├─ 触发条件: currentTokens > (contextWindow - 20000) * 0.9
  │             且 messages > 20 条
  ├─ 保留最近 15 条消息作为"近期上下文"
  ├─ 将历史消息送 LLM 生成摘要
  ├─ 如果 LLM 调用失败 → fallbackSummary()（字符串拼接 100 字预览）
  └─ 返回 AutoCompactResult(摘要文本, 压缩消息数)
```

#### extractPath() 多策略解析

```java
// 策略 1: JSON 字段匹配
extractPathFromJsonField(input, "file_path")
extractPathFromJsonField(input, "filePath")
extractPathFromJsonField(input, "path")

// 策略 2: Bash 命令解析 (BashTool 专属)
extractPathFromCommand(input)  // 正则匹配命令参数中的文件路径

// 策略 3: 正则回退
extractPathFromText(input)     // 匹配 /path/to/file.ext 模式
```

### 5.7 权限与安全

**文件位置：** `aether-domain/.../agent/permission/PermissionEngine.java`

#### 四级权限模式

| 模式 | 行为 | 使用场景 |
|------|------|----------|
| `DEFAULT` | 正常权限检查（规则链评估） | 普通用户交互 |
| `PLAN` | 只允许只读工具（`PlanModeDenyWriteRule` 拒绝写入） | 规划阶段预览 |
| `ACCEPT_EDITS` | 信任模式，编辑操作自动通过 | 受信任的工作流 |
| `BYPASS` | 完全跳过权限检查 | 开发者调试 |

**权限模式传递路径：**
```
前端/API 调用 → RuntimeContext.metadata.put("permissionMode", "PLAN")
  → PermissionMiddleware.onActing() → 读取 metadata
    → PermissionEngine.check(ctx, mode) → 规则链 → ALLOW/DENY/ASK_USER
```

#### 规则链评估算法

```java
public PermissionDecision check(PermissionContext ctx, PermissionMode mode) {
    if (mode == PermissionMode.BYPASS) return ALLOW;  // 完全跳过
    for (PermissionRule rule : rules) {                 // 优先级排序
        PermissionDecision decision = rule.evaluate(ctx, mode);
        if (decision != null) return decision;          // 第一个非 null 为最终决定
    }
    return PermissionDecision.DENY;                     // 默认拒绝
}
```

**安全设计原则：Default-Deny。** 如果没有规则显式允许，一律拒绝。只有 `ReadOnlyAllowRule`（只读工具自动放行）和 `BYPASS` 模式例外。

### 5.8 多层记忆系统

**文件位置：** `aether-domain/.../memory/`

| 层次 | 存储后端 | 实现类 | 生命周期 |
|------|----------|--------|----------|
| Working | `AgentState.messages` (Ring Buffer 500→200) | ReActAgent.queryLoop() | 单次对话 |
| Short-Term | `AgentState.rollingSummary` | ContextManager.autoCompact() | 单次会话 |
| Long-Term | `VectorStore` (Pgvector 可选) | DefaultMemoryFacade + RecallFlow | 跨会话持久化 |
| Episodic | 后台 Fork Agent | SessionMemoryExtractor | 跨会话模式提取 |

#### 记忆写入流程（remember）

```
DefaultMemoryFacade.remember(content, scope, options)
  │
  ├─ [可选] EncodingFlow.encode(content) → LLM 推断
  │     ├─ categories (分类)
  │     ├─ importance (重要性 0-1)
  │     └─ shouldConsolidate (是否与已有记忆合并)
  │
  ├─ [if shouldConsolidate] 搜索相似记忆
  │     └─ 相似度 > consolidationThreshold (默认 0.85)
  │        → 合并内容 (拼接 + \n\n) + 平均重要性 + 合并分类
  │        → upsert 到 VectorStore
  │
  └─ [else] 创建新 MemoryRecord → upsert
```

**单线程写入器：** `storeExecutor = Executors.newSingleThreadExecutor()` 序列化所有写入操作，防止并发 upsert 同一记录导致不一致。

#### 记忆召回流程（recall: Shallow vs Deep）

```
Shallow Recall (单次搜索 + 加权重排):
  query → embed() → VectorStore.search(2*maxResults)
  → rerankByWeight()
    score = 0.6 * semanticScore + 0.3 * recencyScore + 0.1 * importanceScore
  → top maxResults

Deep Recall (查询分解 + 并行搜索 + 重排):
  query → decomposeQuery() (按中英文句号/分号拆分)
  → 并行 CompletableFuture 子查询搜索
  → 去重合并 (LinkedHashMap keyed by record ID)
  → llmRerank() (当前为 score-based 降级实现)
  → top maxResults
```

**时间衰减函数：** `recencyScore` = 1 天内 1.0, 7 天内 0.8, 30 天内 0.5, 更早 0.2。

**优雅降级：** 如果 `EmbeddingModel` 不可用，`embed()` 返回零向量（维度 1280），所有向量相同 → 搜索降级为基于 scope/keyword 的匹配。

### 5.9 可观测性三件套

| 信号 | 技术 | 关键文件 | 内容 |
|------|------|----------|------|
| **Logs** | LogstashEncoder JSON | `logback-spring.xml` | 结构化 JSON 日志 + MDC(agentId/sessionId/correlationId) |
| **Traces** | OpenTelemetry | `AgentTracer.java` | 3 级 Span: agent.turn → agent.model.call → agent.tool.call |
| **Metrics** | Micrometer + Prometheus | `AgentMetrics.java` | Counters(轮次/错误/工具调用) + Timers(P50/P95/P99) |

#### AgentTracer Span 层级

```
agent.turn (INTERNAL)
  ├─ Attributes: agent.id, session.id, turn.number
  ├─ agent.model.call (CLIENT)
  │     ├─ Attributes: model.name, token.input/output/total, cost.usd
  │     └─ agent.tool.call (CLIENT)
  │           └─ Attributes: tool.name, tool.call_id, tool.success
  └─ SpanScope implements AutoCloseable (try-with-resources 自动关闭)
```

#### 结构化事件体系（AgentEvent）

10 种 Jackson 多态事件，通过 `@JsonTypeInfo(property = "eventType")` 区分：

`agent.started → turn.started → model.call.started → tool.call.started → tool.call.completed → model.call.completed → turn.completed → compact.triggered → agent.completed → error.occurred`

每个事件都携带 `eventId(UUID)`, `timestamp(Instant)`, `agentId`, `sessionId`, `correlationId`。

### 5.10 会话持久化

**双实现设计：**

```
SessionRepository (domain 端口接口)
  ├─ MySqlSessionRepository    — JdbcTemplate + INSERT ON DUPLICATE KEY UPDATE
  │   └─ @ConditionalOnClass(DataSource) + @ConditionalOnProperty("aether.session.persistence=true")
  └─ RedisSessionRepository    — 反射调用 RedisTemplate (无编译期依赖)
      └─ @ConditionalOnClass(RedisTemplate) + @ConditionalOnProperty(...)
```

**持久化时机：** `SessionPersistenceHook` 在 `onAfterExecute` / `onError` 时异步保存 `Agent.saveState()` 的 JSON 到数据库。会话恢复时从 DB 读取 `state_json` 字段，反序列化为 `Map<String, Object>`，调用 `agent.loadState(savedState)`。

---

## 6. 数据库设计与数据流

### 6.1 MySQL 会话表

```sql
-- 表名: aether_session (定义于 MySqlSessionRepository.java)
CREATE TABLE aether_session (
    session_id   VARCHAR(64) PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL,
    agent_id     VARCHAR(64) NOT NULL,
    status       VARCHAR(20) DEFAULT 'ACTIVE',   -- ACTIVE / ARCHIVED / ERROR
    state_json   TEXT,                            -- Agent.saveState() JSON
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);

-- 写入: INSERT INTO aether_session (...) ON DUPLICATE KEY UPDATE ...
```

### 6.2 Pgvector 记忆表

```sql
-- 表名: aether_memories (定义于 PgvectorVectorStore.java)
CREATE TABLE aether_memories (
    id            VARCHAR(64) PRIMARY KEY,
    content       TEXT NOT NULL,
    embedding     vector(1280),
    scope_path    VARCHAR(512),
    categories    JSONB,
    metadata      JSONB,
    importance    FLOAT DEFAULT 0.5,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_accessed TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引: IVF_FLAT, cosine 距离, 100 lists
CREATE INDEX ON aether_memories
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

### 6.3 核心数据流

```
用户发送消息
  → MdcFilter 注入 correlationId
  → AgentServiceController 接收
  → ChatService.handleMessage()
    ├─ [记忆注入] injectMemory() → MemoryFacade 或 MemoryStore
    ├─ [会话恢复] SessionRepository.findBySessionId() → loadState()
    ├─ [Agent 执行] agent.execute(ctx) → ReActAgent.queryLoop()
    │     ├─ Phase 1: ContextManager 压缩 → 仅影响内存中 messages
    │     ├─ Phase 2: ModelInvoker → LLM API → 结果写入 AgentState.messages
    │     ├─ Phase 3: 无工具 → 流式返回 RuntimeEvent → SSE → 前端渲染
    │     └─ Phase 4: 有工具 → ToolExecutor → ToolRegistry → 工具结果写回
    ├─ [状态持久化] SessionPersistenceHook.onAfterExecute()
    │     → CompletableFuture.runAsync() → save() to MySQL/Redis
    └─ [记忆提取] SessionMemoryExtractor.onAfterExecute()
          → 单线程 Executor → MemoryFacade.remember()
```

---

## 7. 亮点与难点分析

### 7.1 技术难点

#### 难点 1：多 Agent 并行执行中的数据隔离与事件合并

**问题：** 3+ 个 Agent 并发运行时，共享 `ExecutionState` 和 RxJava `FlowableEmitter`，需要保证：
- 每个 Agent 看到独立的状态副本
- 事件发射到同一个 SSE 流且不发生交错损坏
- 所有 Agent 的结果正确合并

**解决方案：**
- `ExecutionState.forkSource()` — 浅拷贝 `finalOutputs`（`ConcurrentHashMap`），`StringBuilder` 缓冲区独立创建
- `synchronized(emitter)` — 保护 `emitter.onNext()` 调用
- `CopyOnWriteArrayList` — 收集子状态引用，无锁读取
- `CountDownLatch(10分钟)` — P0-6 添加的超时保护，防止某个子 Agent 卡死导致请求永久挂起

**代码证据：** `GraphExecutor.java:executeParallel()` 方法。

#### 难点 2：上下文窗口精确控制

**问题：** 不同模型 tokenizer 不同（GPT-4o 128K, Claude 200K, DeepSeek 64K），无法用字符数统一估算。工具调用结果差异巨大（可能几百到几十万字符）。

**解决方案：**
- `TokenEstimator` 提供模型感知的 token 估算
- 三层渐进压缩：无损（截断/去重）→ 有损（LLM 摘要）
- 保留 20K 输出余量（`MAX_OUTPUT_TOKENS_FOR_SUMMARY`）确保摘要 LLM 调用本身不会超限
- 阈值公式 `(contextWindow - 20000) * 0.9` 提供 10% 安全边界

**代码证据：** `ContextManager.java:autoCompactIfNeeded()`。

#### 难点 3：SSE 流式对话的事件序列化

**问题：** 单个 HTTP 请求需要同时流式输出文本增量（textDelta）、工具调用进度（toolCall/toolResult）、生命周期事件（compactBoundary/error/done）。不同类型事件需要不同序列化格式。

**解决方案：**
- 统一的 `RuntimeEvent` 类型 + `EventType` 枚举区分
- Controller 中 `serializeEvent()` 实现类型 dispatch — 每个 `EventType` 构建不同的 payload Map
- SSE 格式 `data: {json}\n\n`
- 前端 `sendMessageStream()` 使用 `fetch() + ReadableStream`（而非 Axios）进行 SSE 解析

**代码证据：** `AgentServiceController.java:chatStream()` 和 `serializeEvent()`。

### 7.2 性能优化

| 优化项 | 具体措施 | 效果 |
|--------|----------|------|
| 工具执行 | 按 `isConcurrencySafe()` 分区，安全组并发 (CachedThreadPool)，不安全组串行 | 减少总工具执行时间 |
| 上下文压缩 | microCompact 去重同一文件的多次编辑 | 减少 LLM 输入 token 数 20-30% |
| 模型调用 | 指数退避重试 (1s→2s→4s, 上限 15s)，智能识别可重试错误 | 提升 API 调用成功率 |
| 会话持久化 | `CompletableFuture.runAsync()` 异步写入 | 不阻塞 Agent 主循环 |
| Bean 延迟注入 | `@Lazy` 注入 ChatModel（装配阶段动态注册） | 避免循环依赖和启动顺序问题 |
| 日志异步化 | Logback `AsyncAppender` + `neverBlock=true` | 日志不阻塞业务线程 |

### 7.3 异常处理策略

| 场景 | 处理方式 | 代码位置 |
|------|----------|----------|
| LLM API 可重试错误 (Timeout/503/429) | 指数退避重试 3 次，退避 1s→2s→4s，上限 15s | `ModelInvoker.isRetryable()` |
| LLM API 不可重试 (400/401/403/404) | 立即失败，中断循环 | `ModelInvoker.isRetryable()` |
| 工具执行超时 (60s) | `ToolResult.error("Tool execution timed out")`，不中断 Agent | `ToolExecutor.executeConcurrently()` |
| 连续工具失败 (3 轮) | 强制退出循环，emit error 事件 | `ReActAgent.queryLoop():consecutiveToolFailures` |
| LLM 摘要压缩失败 | `fallbackSummary()` — 简单字符串拼接（100 字预览） | `ContextManager.autoCompactIfNeeded()` |
| 中间件异常 | 每个中间件 catch 异常，log warning，继续执行 | `MiddlewareChain` 各 apply 方法 |
| SessionMemory 提取异常 | swallow + log warning，不影响 Agent 执行 | `SessionMemoryExtractor.onAfterExecute()` |
| Embedding 不可用 | 零向量回退，搜索降级为 scope/keyword 匹配 | `RecallFlow.embed()` |
| 并行 Agent 超时 (10min) | CountDownLatch.await() 超时后继续，收集部分结果 | `GraphExecutor.executeParallel()` |

---

## 8. 个人贡献分析

基于代码规模（55+ 源文件、6 个 Maven 模块、9 个测试类、Vue 3 前端）、架构复杂度（DDD + DAG + 洋葱中间件 + 多层记忆 + 可观测）以及 README 中明确列出的 5 个开源框架参考（AutoGen / AgentScope Java / CrewAI / MetaGPT / cc-haha），可合理推断一个**资深全栈开发**可能负责的部分：

### 8.1 架构设计（核心贡献）
- DDD 六边形架构的模块拆分（6 模块 + 1 前端）和依赖方向约束
- Agent 接口体系设计（参考 5 个框架后的综合方案）
- YAML 配置驱动的装配流水线设计（策略树 + DynamicContext）
- 请求时路由（ChatService）的单/多 Agent 分派逻辑

### 8.2 Agent 引擎实现（核心贡献）
- ReActAgent 主循环的 Think-Act-Observe 五阶段设计
- ContextManager 三层压缩算法（特别是 microCompact 两遍扫描去重）
- ModelInvoker 的流式调用 + 指数退避重试 + 智能错误分类
- GraphExecutor 四种执行模式（特别是 P1-1 GraphFlow DAG 拓扑排序）

### 8.3 横切关注点体系
- AgentHook 7 拦截点 + HookRegistry 自动发现
- 洋葱中间件体系（AgentMiddleware + MiddlewareChain 的组合/洋葱双模式）
- PermissionEngine 规则链 + 四级权限模式

### 8.4 模型层抽象
- ModelProvider SPI 接口设计
- ModelProviderRegistry 的 supports() 匹配 + 回退机制
- P0-2/ P0-3 重构（从硬编码 `new OpenAiApi()` 到 Provider SPI + Per-Agent 模型）

### 8.5 可观测性
- OpenTelemetry 三级 Span 层级设计
- Micrometer 指标（P50/P95/P99 直方图 + Token 计数器）
- 10 种 AgentEvent 多态事件体系
- MDC 全链路 correlationId 注入

### 8.6 记忆系统
- MemoryFacade 统一门面设计
- RecallFlow 的 Shallow/Deep 双模式 + 加权重排 + 时间衰减
- SessionMemoryExtractor 后台异步记忆提取

### 8.7 前端
- Vue 3 + Pinia 聊天应用架构
- SSE 流式事件的 fetch + ReadableStream 解析
- 后端宕机检测 + 错误模态框
- 中文文本渲染优化 (`cleanMessageText()`)

---

## 9. 面试模拟问答

### Q1：这个项目和其他 Agent 框架（LangChain、AutoGen）相比，最大区别是什么？

**A:** 三个核心差异：
1. **YAML 配置驱动**而非 Python 代码驱动——非技术人员可以配置多 Agent 工作流，无需写代码
2. **启动时编译 + 运行时零开销**——不像 LangChain 每次请求都解析链定义，Aether 在 `ApplicationReadyEvent` 一次性将所有 YAML 编译为 `AgentGraph` IR，请求时直接执行
3. **深层企业级集成**——原生支持 Spring Boot 生态（MyBatis、HikariCP、Micrometer、OpenTelemetry），可直接嵌入现有 Java 微服务体系

### Q2：ReActAgent 的循环为什么设计为最大 100 轮？如果用户的任务确实需要更多轮怎么办？

**A:** 100 轮是一个**安全上限**：
- 每轮包含一次 LLM API 调用（有成本）和可能的多次工具调用
- 100 轮对应约 100 次 API 调用 + 100 次工具执行，已经覆盖非常复杂的任务
- 如果确实需要更多轮，可以通过 YAML 的 LOOP 工作流将任务拆分为 subtask——每个 subtask 各自有 100 轮限制
- 另外有**连续 3 轮全部工具失败**的提前退出机制，不需要等到 100 轮
- 未来可以将其做成可配置项（`MAX_TURNS` 从 YAML 读取而非硬编码）

### Q3：PARALLEL 模式下如果某个子 Agent 崩溃了，其他 Agent 会怎样？

**A:**
- 每个子 Agent 在独立线程（`CachedThreadPool`）中执行，不会影响其他 Agent
- `CountDownLatch.await(10, TimeUnit.MINUTES)` 有超时保护——超时后主流程继续，不会无限等待
- 子 Agent 的异常通过 `Flowable` 的 error 事件发射，被 `handleMessageStream()` 中的 subscriber 捕获
- **当前限制：** 没有自动重试机制——失败的子 Agent 结果丢失，需要建立重试策略或 fallback agent。这是改进方向之一。

### Q4：ContextManager 的 microCompact 如何识别文件编辑操作？如果工具名不一样怎么办？

**A:**
- 使用**白名单机制**：`EDIT_TOOL_NAMES` 常量包含 `Edit`, `FileEdit`, `Write`, `FileWrite`, `FileEditTool`, `FileWriteTool`, `BashTool`
- 路径提取使用**三层策略**：
  1. 从 JSON 参数中提取 `file_path` / `filePath` / `path` 字段
  2. 对 BashTool，用正则从 command 字符串中提取文件路径
  3. 通用正则回退匹配 `path/to/file.ext` 模式
- 如果工具名不在白名单中，该操作被保留且不被去重，这是安全策略——宁可多保留一些消息，也不错删关键操作

### Q5：Per-Agent 异构模型的 ChatModel Bean 如何避免与全局 ChatModel 冲突？

**A:**
- 全局 ChatModel 注册为 Bean name `"chatModel"`
- Per-Agent ChatModel 注册为 `"chatModel-{agentName}"`（如 `"chatModel-researcher"`）
- `ChatModelNode` 的 `registerPerAgentChatModel()` 方法遍历 agent 列表，只为有自定义 `model` 配置的 agent 创建独立 Bean
- 消费端（`ReActAgent`）通过 `@Lazy` 注入加运行时选择——如果有 dedicated bean 就使用，否则 fallback 到全局
- `AgentNodeDef.modelRef` 字段标识使用的模型名，`AgentGraphCompiler.compileAgentDefs()` 中完成 modelRef 的赋值（优先用 agent 级 model，回退 module 级 chat-model）

### Q6：权限系统的 ASK_USER 模式当前实现是"暂允许"，如果要实现真正的 Human-in-the-Loop，你会怎么设计？

**A:**
需要三部分改造：
1. **执行暂停机制**：`PermissionMiddleware.onActing()` 返回 `ASK_USER` 时，暂停 ReActAgent 的 `queryLoop()`（`agent.pause()` 设置 status=PAUSED），保存 `PermissionContext` 到等待队列
2. **前端交互**：SSE 下发 `requireUserConfirmation` 事件（含 toolName / toolInput / rationale），前端展示确认对话框。用户通过 WebSocket 或轮询返回确认结果
3. **恢复机制**：确认结果通过 `resumeWithDecision(decision)` 写入 agent 的 `AgentState.attributes`，恢复 `queryLoop()` 继续执行。需要超时兜底（如 5 分钟未响应则 DENY）
4. **架构已经预留了扩展点**：`PermissionDecision.ASK_USER` 枚举值、`PermissionContext` 包含完整上下文信息、`AgentState.AgentStatus.PAUSED` 状态

### Q7：GraphFlow DAG 中如果出现循环依赖（A→B→A），系统如何处理？

**A:**
- 拓扑排序阶段：如果图中存在**结构循环**（不考虑条件），入度归零算法无法完成——因为循环中每个节点入度始终 ≥ 1。当前实现会卡在 while 循环的 50 次最大迭代中，最终因"某些节点无法达到"而无法完成
- **条件循环**（如 `reviewer → writer, condition="output.contains('需修改')"`）是合法的一通过 `exitCondition` 终止：
  - 每次 `reviewer` 完成后，`ConditionEvaluator` 检查 exit condition
  - 如果 `exitCondition` 为 true，子节点标记为 SKIPPED 而非再次入队
- **改进方向：** 启动时的 `AgentGraphCompiler` 应该检测结构循环并在启动阶段报错，而非运行时才发现

### Q8：向量数据库从 Pgvector 换到 Milvus 需要改哪些地方？

**A:**
只需实现新的 `VectorStore` 适配器：
1. 新建 `MilvusVectorStore implements VectorStore`，实现 6 个方法（`upsert`, `search`, `upsertBatch`, `delete`, `deleteByScope`, `dimension`）
2. 添加条件 Bean 注解（`@ConditionalOnProperty("aether.memory.milvus.enabled=true")`)
3. domain 层零改动——`DefaultMemoryFacade` 只依赖 `VectorStore` 接口（符合依赖倒置原则）

### Q9：系统的流量激增时，最可能的瓶颈在哪里？如何解决？

**A:**
按可能性排序：
1. **LLM API 调用**——受限于外部 API 的 rate limit 和延迟。解决：`RateLimitMiddleware` 已做本地限流；可加请求队列 + 优先级调度
2. **线程池耗尽**——`CachedThreadPool` 无上限，极端情况可能创建过多线程。解决：改用有界线程池 + 背压（RxJava `Flowable` 已支持）
3. **MySQL 会话写入**——大量异步写入 `CompletableFuture.runAsync()` 使用公共 ForkJoinPool。解决：专用写入线程池 + 批量写入
4. **上下文压缩 LLM 调用**——`autoCompact` 本身要调 LLM。解决：缓存摘要结果、使用更便宜的模型做摘要

### Q10：工具执行为什么按 isConcurrencySafe() 分区？如果所有工具都标记为不安全会怎样？

**A:**
- 分区是为了**最大化并行度同时保证数据安全**。文件写入类工具如果并发可能造成竞态条件；只读工具（搜索、计算）则可以安全并行
- 如果全标记不安全 → 全部串行执行，无性能损失但延迟增加
- 如果全标记安全但实际不安全 → **数据损坏风险**。安全标记由工具开发者负责，框架不做自动判断
- 还有一个**60 秒超时保护**——`executeConcurrently()` 中每个 CompletableFuture 都有独立的 timeout 处理

### Q11：为什么要自己实现 Agent 引擎而不是直接用 LangChain4j 或 Spring AI 的 Agent？

**A:**
- LangChain4j 和 Spring AI 的 Agent 抽象层次较高，缺少对**多 Agent 编排拓扑**（DAG/条件路由/fan-in-fan-out）的原生支持
- 需要**异构模型混合编队**——不同 Agent 用不同模型供应商，现有框架的 per-agent model 配置支持较弱
- 需要**企业级横切关注点**——Hook 体系、洋葱中间件、权限引擎这些是自研的核心原因
- 但底层仍然使用了 Spring AI 的 `ChatModel` 抽象和 MCP 客户端——**站在巨人肩膀上做编排层**

### Q12：项目中 9 个测试类主要覆盖什么？如果你要补充测试，会优先加什么？

**A:**
现有测试覆盖（基于实际 test 文件）：
- `ReActAgentTest`：AgentConfig 不可变性、AgentState 消息管理、防御性拷贝、RuntimeContext fork、loadState 反序列化
- `GraphExecutorTest`：AgentGraph IR 模型、AgentEdge 类型解析、ExecutionState 模板解析/fork/merge
- `ModelInvokerTest`：重试逻辑、错误分类
- `ToolExecutorTest`：工具执行、并发/串行分区
- `ContextManagerTest`：三层压缩
- `ChatServiceTest`：单/多 Agent 路由
- `SessionRepositoryTest`：CRUD 操作
- `ModelProviderTest`：Provider supports() 匹配
- `AgentIntegrationTest`：端到端集成

**优先补充：**
1. **MiddlewareChain 的洋葱组合测试**——确保反向包裹顺序正确
2. **GraphFlow DAG 的端到端测试**——模拟真实的条件路由和 fan-in 场景
3. **PermissionEngine 规则链边界测试**——各种 mode + rule 组合
4. **并发压力测试**——PARALLEL 模式下的线程安全验证

### Q13：模型调用结果中的 tool_use 块转换为 ToolResponseMessage 非常关键，如果转错了会怎样？

**A:**
- 转为 `UserMessage` → OpenAI API 收到非标准格式 → 返回 **HTTP 400** 错误
- 漏掉 `tool_call_id` → API 无法关联请求和结果 → 返回 400
- `TurnMessage` 的 `convertToSpringMessages()` 方法中专门有 tool_result → `ToolResponseMessage` 的转换分支
- CLAUDE.md 中有**明确的禁止规则**："禁止 `tool_result` 转为 `UserMessage`（必须 `ToolResponseMessage`）"——这是最容易出错的点之一

---

## 10. 项目当前不足与改进方向

| 领域 | 不足 | 改进方向 | 紧迫度 |
|------|------|----------|--------|
| **测试覆盖** | 缺少 MiddlewareChain/Permission/GraphFlow 集成测试；无并发压力测试 | 补充单元测试 + 集成测试 + 压力测试 | 高 |
| **GraphFlow DAG** | 缺少循环依赖检测（启动时）；50 轮硬上限可能不够 | 添加编译期 DAG 校验（Kahn's algorithm 后检查未处理节点） | 高 |
| **错误恢复** | 并行 Agent 失败无自动重试；ASK_USER 是 fake 实现 | 添加子 Agent 重试策略；实现完整的 HITL 暂停-恢复机制 | 中 |
| **记忆系统** | Deep recall 的 LLM 重排是 stub；stats() 返回硬编码 0 | 接入真实 LLM 重排；实现正确的统计信息聚合 | 中 |
| **会话恢复** | loadState 不恢复 currentTurn（隐含重置）；无跨会话的对话延续 | 完整恢复 turn count + rolling summary；支持跨会话继续对话 | 中 |
| **文档** | 缺少 API 文档（OpenAPI/Swagger）；缺少架构决策记录 (ADR) | 集成 SpringDoc；编写关键设计的 ADR | 中 |
| **前端** | 认证为硬编码 admin/admin；错误处理较简单 | JWT + 数据库用户系统；完善错误恢复 UX | 低 |
| **部署** | bash 脚本构建 Docker；缺少 K8s 部署清单和健康检查端点 | K8s Deployment + Service + HPA；liveness/readiness probe | 低 |
| **成本监控** | Token 用量记录在 Span 属性中但无聚合展示 | Token 成本仪表板 + 按 Agent/用户/日期聚合 | 低 |

---

## 11. 部署与运维

### 11.1 构建

```bash
# Maven 全量构建
mvn clean install

# 仅编译 domain 模块
mvn clean compile -pl aether-domain -am

# 打包可执行 JAR
mvn clean package -pl aether-app -am

# Docker 构建
cd aether-app && bash build.sh
```

**Maven 多 Profile：**
- `dev`（默认）：`-Xms1G -Xmx1G`，激活 dev profile
- `test`：`-Xms1G -Xmx1G`
- `prod`：`-Xms6G -Xmx6G`，激活 prod profile

### 11.2 运行时配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| Server Port | 8091 | `server.port` |
| DB Pool | 25 max, 15 min-idle, 30s timeout | HikariCP |
| Core Threads | 20 | `thread.pool.executor.config.corePoolSize` |
| Max Threads | 50-200 | `thread.pool.executor.config.maxPoolSize` |
| Queue Size | 5000 | `thread.pool.executor.config.blockQueueSize` |
| Reject Policy | CallerRunsPolicy (dev) / AbortPolicy (prod) | 可配置 |

### 11.3 扩容建议

- **水平扩展：** 无状态服务（会话状态在 MySQL/Redis），可通过 K8s Deployment replicas 扩展。注意 LLM API key 的 rate limit 是瓶颈。
- **会话持久化：** 切换到 `RedisSessionRepository` 以支持多实例会话共享（当前 MySQL 实现也可跨实例）。
- **向量存储：** Pgvector 支持读写分离，可配置 read replica 用于记忆搜索。
- **监控：** Prometheus 端点已暴露在 `/actuator/prometheus`，可直接对接 Grafana Dashboard。

### 11.4 前端部署

```bash
cd docs/dev-ops/AIagent_frontend
npm install
npm run build          # 生产构建到 dist/
# 将 dist/ 部署到 Nginx/CDN，API base 通过 VITE_API_BASE 配置
```

**开发模式：** Vite 代理 `/api` → `http://127.0.0.1:8091`

---

> 本文档基于 aether 项目 commit `be81149` 时的源码生成，所有技术细节均可追溯到具体文件和代码行。建议面试前通读关键源文件（至少 `ReActAgent.java`, `GraphExecutor.java`, `ContextManager.java`, `ChatService.java`, `MiddlewareChain.java`）以应对深入追问。

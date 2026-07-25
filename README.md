# Aether: 企业级多 Agent 协作架构

Aether — 企业级 AI Agent 架构，基于 Spring Boot 3.4.3 + 自研 Agent 引擎 + DDD 六边形架构。YAML 配置驱动多 Agent 编排，支持 MCP/Skills 工具集成、**Agent 级工具作用域**、异构模型混合调用、DAG 条件路由、洋葱中间件体系、权限引擎、多层记忆系统和 OpenTelemetry 可观测性。

设计参考 AutoGen、AgentScope Java、CrewAI、MetaGPT、cc-haha 五大开源 Agent 框架，累计 60+ 源文件、9 个测试类（93 个测试用例）。

---

## 一、核心特性

### 2.1 Agent 引擎与编排

- **Agent 接口驱动**：统一的 `Agent` 核心接口（身份标识 → 执行 → 生命周期 → 状态序列化 → 能力声明），通过 `AgentFactory` 注册表按 `agentType` 创建不同实现
- **ReActAgent 主循环**：Think-Act-Observe 四阶段执行—上下文压缩 → 模型调用 → 退出判断 → 工具执行，最大 100 轮，连续 3 轮工具全失败自动熔断
- **Agent 级工具作用域**（借鉴 AgentScope Java + cc-haha）：YAML 中 `toolNames: [read, write]` 显式声明每个 Agent 可用的工具子集，编译期校验工具存在性，装配期创建 per-agent ChatModel Bean（独立工具集）。`"*"` 通配符 = 全部工具（向后兼容）
- **四种多 Agent 协作模式**：
  - `SEQUENTIAL` — 串行流水线，`{outputKey}` 模板跨 Agent 传递结果
  - `PARALLEL` — 并发执行，CountDownLatch 同步 + 事件安全转发 → 结果合并
  - `LOOP` — 循环迭代至收敛（连续两轮输出相同）
  - `GRAPHFLOW` — **DAG 图编排**：拓扑排序 + 就绪队列调度 + SpEL 条件路由 + fan-in/fan-out + 条件循环退出
- **Per-Agent 异构模型**：不同 Agent 可配置不同模型（GPT-4o + Claude Sonnet + DeepSeek 混合编队）
- **上下文压缩引擎**：三层压缩（工具结果截断 → 冗余清理 → LLM 摘要压缩），Token 估算驱动自动触发。LLM 摘要调用通过 `internalLlmCall` 事件透明化
- **{outputKey} 编译期校验**（借鉴 MetaGPT ActionNode）：启动时验证 Agent instruction 中所有 `{key}` 引用均在上下游 Agent 的 `outputKey` 中有定义。`{memory}` 等运行时占位符自动豁免。未解析引用 → `AgentCompileException` 启动失败

### 2.2 模型提供商可插拔

- **ModelProvider SPI**：`providerName()` + `supports(modelId)` + `createChatModel(config)` + `createChatModelWithTools()`
- **内置三个 Provider**：OpenAI（兜底，兼容 DeepSeek/Qwen 等所有 OpenAI 协议）、Anthropic、DashScope
- **ModelProviderRegistry**：Spring Bean 自动发现，按 `supports()` 匹配，注册表可动态扩展
- **ModelConfig**：从 YAML 的 `ai-api` + `chat-model` 节点统一映射

### 2.3 横切关注点体系

- **AgentHook 系统**（7 个拦截点）：`onBeforeExecute` / `onAfterExecute` / `onError` / `onBeforeModelCall` / `onAfterModelCall` / `onBeforeToolCall` / `onAfterToolCall`
- **HookRegistry**：Spring Bean 自动发现 + 优先级排序 + 批量注入到所有 Agent
- **CompositeHook**：组合模式，一组相关 Hook 作为整体注册
- **洋葱中间件系统**（5 个拦截层）：
  - `onSystemPrompt` — 系统提示词变换（注入时间/偏好）
  - `onAgent` — 最外层（限流、优雅关闭）
  - `onReasoning` — 模型调用前（注入记忆/上下文）
  - `onModelCall` — 模型调用包装（缓存/切换模型）
  - `onActing` — 工具执行拦截（权限检查/参数改写）
- **内置中间件**：`RateLimitMiddleware`（滑动窗口限流）、`GracefulShutdownMiddleware`（优雅关闭）、`PermissionMiddleware`（权限门控）

### 2.4 权限与安全

- **四级权限模式**：`DEFAULT`（正常检查）→ `PLAN`（只允许只读工具）→ `ACCEPT_EDITS`（信任模式）→ `BYPASS`（开发者模式）
- **PermissionEngine 规则链**：`PermissionRule` 接口 + 优先级排序 + 链式评估（第一个非 null 的结果作为最终决策，默认 DENY）
- **内置规则**：`ReadOnlyAllowRule`（只读工具放行）、`PlanModeDenyWriteRule`（计划模式下禁止写入）

### 2.5 可观测性

- **AgentTracer**：OpenTelemetry Span 管理—`agent.turn` → `agent.model.call` → `agent.tool.call` 三级 Span 层级，记录 token 用量和成本
- **AgentMetrics**：Micrometer 指标采集—计数器（turns/errors/toolCalls）+ 直方图（延迟 p50/p95/p99）+ Token 用量累加
- **结构化日志**：10 种 Jackson 多态 AgentEvent + LogstashEncoder JSON 日志 + MDC 自动注入 `agentId`/`sessionId`/`correlationId`
- **MdcFilter**：Servlet Filter 为每个 HTTP 请求注入 `X-Correlation-Id` 贯穿全链路

### 2.6 多层记忆系统

| 层次 | 实现 | 说明 |
|------|------|------|
| Working Memory | `AgentState.messages`（Ring Buffer，500→200 修剪） | 当前对话轮次 |
| Short-Term Memory | `AgentState.rollingSummary` + `ContextManager` 维护 | 会话滚动摘要 |
| Long-Term Memory | `VectorStore`（Pgvector）+ 语义搜索 | 持久化知识库 |
| Episodic Memory | `SessionMemoryExtractor`（后台异步 Fork Agent） | 经验模式提取 |

- **MemoryFacade 统一门面**：`remember()`（LLM 推断元数据）+ `recall()`（自适应召回，Shallow/Deep 双模式）
- **RecallFlow**：Shallow（单次语义搜索 + 时间衰减）→ Deep（LLM 查询分解 → 多子查询并行搜索 → 去重合并 → LLM 重排序）
- **MemoryScope 层级隔离**：`"crew/research/agent/analyst"`，支持祖先路径遍历
- **{memory} 占位符注入**：通过 `ChatService.injectMemory()` 注入记忆到 Agent instruction。instruction 缺少 `{memory}` 占位符时 → WARN 日志提示（不静默丢弃）

### 2.7 会话持久化

- **SessionRepository 接口**：`save()` / `findBySessionId()` / `deleteBySessionId()` / `listByUserId()`
- **双实现**：`MySqlSessionRepository`（JdbcTemplate + UPSERT）+ `RedisSessionRepository`
- **SessionPersistenceHook**：在 `onAfterExecute` / `onError` 时异步持久化 `Agent.saveState()` JSON
- **会话恢复**：请求带 `sessionId` → 数据库加载 `state_json` → 反序列化 → `agent.loadState()` 恢复
- **会话隔离**：`createSession()` 每次调用生成新 UUID（不复用 userId 缓存），多客户端/标签页独立会话互不干扰

### 2.8 工具生态

- **MCP 协议工具**：`DefaultMcpClientFactory` 按传输类型路由（SSE / Stdio / Local），经 `McpToolAdapter` 适配到 `Tool` 接口
- **Skills 技能库**：`ToolSkillsCreateService`，支持 resource 和 directory 两种来源，经 `SkillsToolAdapter` 适配
- **Agent 级工具作用域**：每个 Agent 可配置 `toolNames` allowlist，装配阶段按名过滤 ToolCallback 创建独立 ChatModel Bean（`"chatModel-{agentName}"`）。未配置或 `"*"` = 全部工具（向后兼容）
- **MCP 连接复用**：`ConcurrentHashMap` 按 `name@baseUri` 缓存 ToolCallback，同一 MCP 端点仅创建一次连接（避免重复 SSE/Stdio 连接）
- **ToolExecutor 并发编排**：安全组并发 + 不安全组串行，60s 超时保护
- **指数退避重试**：`ModelInvoker` 自动重试 3 次（1s → 2s → 4s），智能识别 Connection Reset / Timeout / 503 / 429。HTTP 400 仅非标准 API（mimo）可重试，标准 Provider 的 400 不重试

### 2.9 测试覆盖

9 个测试文件（JUnit 5 + Mockito）：`ReActAgentTest` / `ModelInvokerTest` / `ToolExecutorTest` / `ContextManagerTest` / `GraphExecutorTest` / `ChatServiceTest` / `ModelProviderTest` / `SessionRepositoryTest` / `AgentIntegrationTest`

---

## 二、技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.4.3 |
| 构建 | Maven | 3.x |
| AI SDK | Spring AI (BOM) | 1.1.0-M3 |
| Agent 引擎 | **自研 Agent 接口 + ReActAgent + GraphExecutor** | — |
| 工具集成 | MCP SDK + spring-ai-agent-utils | 0.4.2 |
| 响应式 | RxJava 3 | — |
| 缓存 | Guava | 32.1.3-jre |
| JSON | Jackson (domain 层) / FastJSON 2.0.28 (config 层) | — |
| 数据库 | MySQL + MyBatis + HikariCP | 8.0.28 / 3.0.4 |
| 向量数据库 | Pgvector（可选） | — |
| 可观测性 | OpenTelemetry + Micrometer + Prometheus | — |
| 日志 | Logback + LogstashEncoder（JSON） | 7.4 |
| 设计模式框架 | xfg-wrench-starter-design-framework | 3.0.0 |
| 前端 | Vue 3 + Pinia + Vue Router + Vite + Axios | — |
| 测试 | JUnit 5 + Mockito | — |

---

## 三、项目结构

```
aether/
├── pom.xml                              # 根 POM，管理 6 个子模块 + 全部依赖版本
│
├── aether-api/          # ★ API 层：服务接口 + DTO
│   └── src/main/java/cn/zcj/aether/api/
│       ├── IAgentService.java           # 对外服务接口定义
│       ├── dto/                         # ChatRequestDTO, ChatResponseDTO, CreateSessionRequestDTO 等
│       └── response/Response.java       # 统一响应包装类
│
├── aether-app/          # ★ 启动引导 + 配置
│   └── src/main/
│       ├── java/cn/zcj/aether/
│       │   ├── Application.java                      # Spring Boot 启动类
│       │   └── config/
│       │       ├── AiAgentAutoConfig.java             # ApplicationReadyEvent 监听器（装配入口）
│       │       ├── ThreadPoolConfig.java              # 线程池配置
│       │       ├── ThreadPoolConfigProperties.java    # 线程池属性
│       │       └── HttpClientConfig.java              # HTTP 客户端（连接超时 30s，读取超时 300s）
│       └── resources/
│           ├── application.yml                        # 激活 dev profile
│           ├── application-dev.yml                    # 数据库、线程池、OTel、Prometheus 配置
│           ├── logback-spring.xml                     # JSON 日志 Appender
│           └── agent/
│               ├── agents.yml                         # ★ 主要 Agent 配置
│               ├── test-agent.yml                     # 测试 Agent + 串行工作流示例
│               ├── demo.yml                           # 演示配置
│               └── parallel_research_app.yml          # 并行研究 Agent 配置示例
│
├── aether-domain/      # ★★★ 核心业务层（禁止反向依赖 trigger/infrastructure）
│   └── src/main/java/cn/zcj/aether/domain/agent/
│       ├── model/
│       │   ├── entity/                  # ChatCommandEntity, ArmoryCommandEntity
│       │   ├── graph/                   # AgentGraph, AgentNodeDef, AgentEdge, AgentEdgeType
│       │   └── valobj/                  # AiAgentConfigTableVO, AgentModelConfig
│       │       ├── enums/               # AgentTypeEnum
│       │       └── properties/          # AiAgentAutoConfigProperties
│       └── service/
│           ├── armory/                  # ★ 策略树装配链（YAML → Agent 运行实例）
│           │   ├── IArmoryService.java
│           │   ├── ArmoryService.java
│           │   ├── AbstractArmorySupport.java
│           │   ├── AgentRegistry.java   # Agent 注册中心（agentId → AgentGraph）
│           │   ├── factory/DefaultArmoryFactory.java  # DynamicContext 含 ModelConfig/Provider
│           │   ├── node/                # 策略树节点链
│           │   │   ├── RootNode.java
│           │   │   ├── AiApiNode.java           # 调用 ModelProviderRegistry
│           │   │   ├── ChatModelNode.java        # 支持 Per-Agent ChatModel Bean
│           │   │   ├── AgentNode.java
│           │   │   ├── AgentWorkflowNode.java    # 支持 GRAPHFLOW 类型
│           │   │   ├── CompilerNode.java
│           │   │   └── workflow/                 # LoopAgentNode / ParallelAgentNode / SequentialAgentNode
│           │   └── matter/
│           │       ├── mcp/              # MCP 客户端工厂（SSE / Stdio / Local）
│           │       └── skills/           # Skills 技能工具
│           │
│           ├── agent/                    # ★ Agent 接口体系
│           │   ├── core/                 # Agent, BaseAgent, AgentConfig, AgentState, RuntimeContext, AgentResult
│           │   ├── impl/ReActAgent.java  # 标准 Think-Act-Observe 循环
│           │   ├── hook/                 # AgentHook, HookRegistry, CompositeHook,
│           │   │   │                     # LoggingHook, SessionPersistenceHook
│           │   ├── middleware/           # AgentMiddleware, MiddlewareChain
│           │   │   └── impl/             # RateLimitMiddleware, GracefulShutdownMiddleware, PermissionMiddleware
│           │   ├── permission/           # PermissionMode, PermissionDecision, PermissionContext,
│           │   │                         # PermissionRule, PermissionEngine
│           │   └── observability/        # AgentTracer, AgentMetrics, TokenUsage
│           │
│           ├── model/                    # ★ 模型提供商抽象
│           │   ├── ModelProvider.java    # SPI 接口
│           │   ├── ModelConfig.java
│           │   ├── ModelProviderRegistry.java
│           │   └── impl/                 # OpenAIProvider, AnthropicProvider, DashScopeProvider
│           │
│           ├── session/                  # ★ 会话持久化
│           │   ├── SessionEntity.java
│           │   └── SessionRepository.java
│           │
│           ├── memory/                   # ★ 多层记忆系统
│           │   ├── MemoryFacade.java     # 统一门面
│           │   ├── DefaultMemoryFacade.java
│           │   ├── MemoryRecord.java
│           │   ├── MemoryScope.java      # 层级命名空间
│           │   ├── MemorySlice.java      # 跨作用域视图
│           │   ├── MemorySearchResult.java
│           │   ├── VectorStore.java      # 向量存储抽象
│           │   ├── RecallFlow.java       # Shallow/Deep 自适应召回
│           │   ├── EncodingFlow.java     # LLM 编码管线
│           │   ├── SessionMemoryExtractor.java  # 后台记忆提取
│           │   └── MemoryStore.java      # 文件存储（保留向后兼容）
│           │
│           ├── event/                    # ★ 结构化事件
│           │   ├── AgentEvent.java       # 10 种事件 record，Jackson 多态
│           │   └── AgentEventPublisher.java
│           │
│           ├── executor/                 # ★ 多 Agent 图执行器
│           │   ├── GraphExecutor.java    # SEQ/PAR/LOOP + GRAPHFLOW DAG 执行
│           │   ├── GraphFlowState.java   # DAG 节点运行时状态
│           │   ├── ConditionEvaluator.java  # SpEL 条件求值
│           │   └── ExecutionState.java   # 模板解析 + 输出合并
│           │
│           ├── chat/ChatService.java     # ★ 对话入口：单/多 Agent 路由 + 记忆注入 + 会话恢复
│           ├── compiler/AgentGraphCompiler.java  # YAML → AgentGraph IR
│           ├── runtime/                  # 运行时引擎
│           │   ├── AgentRuntime.java     # @Deprecated 服务聚合（逻辑已迁移到 ReActAgent）
│           │   ├── ModelInvoker.java     # LLM 调用 + 指数退避重试
│           │   ├── RuntimeEvent.java     # 运行时事件类型
│           │   └── TurnMessage.java      # 轮次消息封装
│           ├── context/                  # 上下文管理（三层压缩）
│           └── tool/                     # 工具系统（Tool/ToolRegistry/ToolExecutor/Adapters）
│
├── aether-infrastructure/  # 基础设施层
│   └── src/main/java/cn/zcj/aether/repository/
│       ├── SessionStore.java             # 会话存储
│       ├── MySqlSessionRepository.java   # MySQL 持久化
│       ├── RedisSessionRepository.java   # Redis 持久化
│       └── PgvectorVectorStore.java      # Pgvector 向量存储
│
├── aether-trigger/         # HTTP 触发层
│   └── src/main/java/cn/zcj/aether/trigger/http/
│       ├── AgentServiceController.java   # ★ REST API 入口
│       └── filter/MdcFilter.java         # MDC trace 注入
│
├── aether-types/           # 类型定义层
│   └── src/main/java/cn/zcj/aether/types/
│       ├── common/Constants.java
│       ├── enums/ResponseCode.java
│       └── exception/AppException.java
│
└── docs/dev-ops/
    └── AIagent_frontend/                 # Vue 3 聊天前端
```

---

## 四、架构设计

### 5.1 DDD 六边形架构

```
trigger (HTTP 控制器 + MdcFilter)
    ↓ depends on
api (服务接口 + DTO)
    ↓ depends on
domain (核心业务逻辑) ← infrastructure (适配器实现，实现 domain 的端口)
    ↓ depends on
types (枚举 / 异常 / 常量)
```

### 5.2 Agent 装配流程（ApplicationReadyEvent 触发）

```
AiAgentAutoConfig.onApplicationEvent()
  → ArmoryService.acceptArmoryAgents(tables)
    → DefaultArmoryFactory.armoryStrategyHandler()
      → RootNode → AiApiNode (ModelProviderRegistry 创建 ChatModel)
        → ChatModelNode (遍历 agents[] → registerPerAgentChatModel())
          → AgentNode (收集 agent 名列表)
            → AgentWorkflowNode (按 type 路由: sequential/parallel/loop/graphflow)
              → CompilerNode (编译 AgentGraph → 注册到 AgentRegistry)
```

### 5.3 请求时路由（ChatService）

```
POST /api/v1/chat → ChatService.handleMessage()
  │
  ├─ 单 Agent（graph.edges 为空）
  │     → AgentFactory.create(agentConfig)
  │     → sessionRepository.findBySessionId() 恢复会话
  │     → injectMemory() 注入记忆
  │     → agent.execute(ctx)
  │           │
  │           ├─ MiddlewareChain 构建
  │           │     ├─ RateLimitMiddleware.onAgent()
  │           │     ├─ PermissionMiddleware.onActing()
  │           │     └─ GracefulShutdownMiddleware.onAgent()
  │           │
  │           ├─ Hook 触发
  │           │     ├─ onBeforeExecute → onAfterExecute
  │           │     ├─ onBeforeModelCall → onAfterModelCall
  │           │     └─ onBeforeToolCall → onAfterToolCall
  │           │
  │           └─ queryLoop()
  │                 ├─ Phase 1: ContextManager 三层压缩
  │                 ├─ Phase 2: chain.applyModelCall() → ModelProvider
  │                 ├─ Phase 3: 无 tool_use → emit done + 退出
  │                 └─ Phase 4: chain.applyActing() → PermissionEngine
  │
  └─ 多 Agent（graph.edges 非空） → GraphExecutor.execute(graph)
        ├─ SEQUENTIAL → 串行推进，{outputKey} 模板传递
        ├─ PARALLEL   → CachedThreadPool 并发，CountDownLatch，事件同步转发
        ├─ LOOP       → 循环迭代至收敛
        └─ GRAPHFLOW  → 拓扑排序 + 就绪队列 + SpEL 条件路由
```

### 5.4 Agent 执行全景（中间件 + Hook 集成）

```
agent.execute(ctx)
  │
  ├── onBeforeExecute(ctx)                    ← Hook 触发
  │     ├── LoggingHook: 发布 AgentStarted 事件 + 设置 MDC
  │     └── SessionPersistenceHook: 加载已保存状态
  │
  ├── MiddlewareChain 构建                     
  │     ├── GracefulShutdownMiddleware (p=5)
  │     ├── RateLimitMiddleware (p=10)
  │     └── PermissionMiddleware (p=20)
  │
  ├── queryLoop() 主循环
  │     │
  │     ├── [每轮循环]
  │     │   ├── EventPublisher.publishTurnStarted()     
  │     │   ├── ContextManager 三层压缩
  │     │   │
  │     │   ├── onBeforeModelCall()                     Hook
  │     │   ├── chain.applyReasoning(messages)          中间件
  │     │   ├── chain.applyModelCall(...)               中间件
  │     │   │     └── ModelInvoker → ModelProvider
  │     │   ├── onAfterModelCall()                      Hook
  │     │   │
  │     │   ├── [if toolCalls]
  │     │   │   ├── onBeforeToolCall()                  Hook
  │     │   │   ├── chain.applyActing()                 中间件
  │     │   │   │     └── PermissionMiddleware → PermissionEngine.check()
  │     │   │   ├── ToolExecutor.executeBatch()
  │     │   │   └── onAfterToolCall()                   Hook
  │     │   │
  │     │   └── EventPublisher.publishTurnCompleted()   
  │     │
  │     └── AgentTracer Span 包裹每个阶段
  │
  ├── onAfterExecute(ctx, result)             ← Hook 触发
  │     ├── LoggingHook: 发布 AgentCompleted 事件
  │     ├── MetricsHook: recordTurn()
  │     ├── SessionPersistenceHook: 异步 saveState() 
  │     └── SessionMemoryExtractor: 后台提取记忆   
  │
  └── onError(ctx, error)                     ← Hook 触发
        ├── LoggingHook: 发布 ErrorOccurred 事件
        └── SessionPersistenceHook: 持久化错误状态
```

---

## 五、Agent YAML 配置

### 6.1 基础单 Agent 配置

```yaml
ai:
  agent:
    config:
      tables:
        Agent01:
          app-name: Agent01
          agent:
            agent-id: 000001
            agent-name: 旅游规划智能体
          module:
            ai-api:
              base-url: https://api.deepseek.com
              api-key: ${DEEPSEEK_API_KEY}
              completions-path: v1/chat/completions
            chat-model:
              model: deepseek-chat
            agents:
              - name: Agent01
                instruction: "你是一个专业的旅游规划助手..."
                outputKey: travel_plan
            runner:
              agent-name: Agent01
```

### 6.2 多 Agent 工作流配置

```yaml
# SEQUENTIAL: 串行流水线
agent-workflows:
  - type: sequential
    name: research-pipeline
    subAgents: [researcher, writer]

# PARALLEL: 并发执行
agent-workflows:
  - type: parallel
    name: multi-analysis
    subAgents: [tech_analyst, business_analyst, risk_analyst]

# LOOP: 循环迭代
agent-workflows:
  - type: loop
    name: iterative-refinement
    maxIterations: 5
    subAgents: [writer, reviewer]
```

### 6.3 Per-Agent 异构模型与工具作用域

```yaml
agents:
  - name: researcher
    instruction: "你是研究员..."
    outputKey: findings
    model:                                  # ← Agent 专属模型
      modelId: gpt-4o

  - name: analyst
    instruction: "你是分析师..."
    outputKey: analysis
    model:                                  # ← 不同模型
      modelId: claude-sonnet-4-6
      apiKey: ${ANTHROPIC_API_KEY}
      baseUrl: https://api.anthropic.com

  - name: writer
    instruction: "你是撰写者..."
    # 不配置 model → 使用全局 chat-model

  - name: code-assistant
    instruction: "你是代码助手，只能读写文件..."
    toolNames: [read_file, write_file, edit_file]  # ← Agent 级工具作用域
    # 不配置 model → 使用全局 chat-model，但只暴露三个文件工具
```

### 6.4 GraphFlow DAG 条件路由配置

```yaml
agent-workflows:
  - type: graphflow                    # ← DAG 图流模式
    name: research-pipeline
    entry-point: researcher

    nodes:                             # 声明所有节点
      - id: researcher,  agent: researcher
      - id: analyst,     agent: analyst
      - id: writer,      agent: writer
      - id: reviewer,    agent: reviewer

    edges:                             # 声明所有边
      - from: researcher, to: analyst                        # 无条件边

      - from: researcher, to: writer
        condition: "output.contains('简单查询')"              # SpEL 条件边

      - from: analyst, to: writer
        activation: all                                       # fan-in

      - from: reviewer, to: writer
        condition: "output.contains('需修改')"                 # 循环边
        exit-condition: "output.contains('通过')"             # 退出条件
```

---

## 六、快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+（可选——会话持久化需要）
- PostgreSQL + Pgvector（可选——语义记忆需要）

### 构建与启动

```bash
# 1. 构建项目
mvn clean install
mvn clean package -pl aether-app -am

# 2. 配置 YAML
# 编辑 aether-app/src/main/resources/agent/agents.yml：
#   - 配置 ai-api（base-url 和 api-key）
#   - 配置 chat-model（model 名称）
#   - 按需配置 agents[].model（Per-Agent 模型）
#   - 按需配置 agent-workflows（多 Agent 编排）

# 3. 启动
mvn spring-boot:run -pl aether-app
```

### 验证

```bash
# 查询可用 Agent 列表
curl http://localhost:8091/api/v1/query_ai_agent_config_list

# 同步对话
curl -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"agentId":"000001","userId":"test","message":"你好"}'

# 流式对话
curl -X POST http://localhost:8091/api/v1/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"agentId":"000001","userId":"test","message":"写一段 Hello World"}'

# 观察结构化 JSON 日志
cat logs/aether-agent.json | jq .

# 查看 Prometheus 指标
curl http://localhost:8091/actuator/prometheus | grep aether_agent
```

### 前端启动

```bash
cd docs/dev-ops/AIagent_frontend
npm install
npm run dev
```

---

## 七、API 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/query_ai_agent_config_list` | 查询所有可用 Agent 配置 |
| GET/POST | `/api/v1/create_session` | 创建会话（agentId, userId） |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat_stream` | 流式对话（SSE） |
| GET | `/actuator/prometheus` | Prometheus 指标端点 |

统一响应格式 `cn.zcj.aether.api.response.Response<T>`：

```json
{ "code": "0000", "info": "成功", "data": "..." }
```

---

## 八、关键配置阈值

| 配置项 | 位置 | 值 |
|---|---|---|
| HTTP 连接超时 | `HttpClientConfig.java` | 30s |
| HTTP 读取超时 | `HttpClientConfig.java` | 300s (5min) |
| 并行工具超时 | `ToolExecutor.executeConcurrently()` | 60s |
| 并行 Agent 超时 | `GraphExecutor.executeParallel()` | 10min |
| 模型调用重试 | `ModelInvoker.java` | 3 次，退避 1s→2s→4s，上限 15s |
| 可重试错误 | `ModelInvoker.isRetryable()` | Connection reset, Broken pipe, Timeout, 503, 502, 429 |
| HTTP 400 重试 | `ModelInvoker.isRetryable()` | 仅 mimo Provider 可重试（非标准 API 瞬时错误），其他 Provider 视为客户端错误 |
| 不可重试错误 | `ModelInvoker.isRetryable()` | 401, 403, 404 |
| 上下文压缩阈值 | `ContextManager.java` | `(contextWindow - 20000) × 0.9` |
| 工具结果截断 | `ContextManager.applyToolResultBudget()` | >50000 字符 → 500 字符 + 警告 |
| 最大轮次 | `ReActAgent.MAX_TURNS` | 100 |
| 连续工具失败熔断 | `ReActAgent.queryLoop()` | 连续 3 轮全部工具调用失败 → 强制退出 |
| 消息修剪 | `ReActAgent.queryLoop()` | >500 条 → 保留第一条 + 最近 200 条 |
| RateLimit 窗口 | `RateLimitMiddleware` | 可配置（默认每窗口 10 次） |

---

## 九、设计参考来源

| 参考框架 | 语言 | 借鉴的设计 |
|---------|------|-----------|
| **AutoGen** (Microsoft) | Python | Agent 协议 + DiGraph & GraphFlowManager + AssistantAgent Per-Agent 模型 + OTel Span |
| **AgentScope Java** (阿里) | Java | AgentState 双模式访问 + Hook 系统 + MiddlewareBase 五层洋葱 + AgentEvent 多态 + **Per-Agent Toolkit 深拷贝**（→ Agent 级工具作用域） + PermissionEngine |
| **CrewAI** | Python | BaseAgent 可序列化实体 + BaseLLM 类层次 + **EncodingFlow/RecallFlow 记忆管线**（→ llmRerank 真实实现） + CheckpointConfig + EventBus（→ internalLlmCall 事件化） |
| **MetaGPT** | Python | RoleContext.llm per-role + Working/LongTerm Memory 分层 + ProjectRepo 持久化 + **ActionNode 编译期校验**（→ {outputKey} 启动时校验） |
| **cc-haha** | TypeScript | cost-tracker token 核算 + SessionMemory 后台 Fork Agent + **显式 allow/deny 工具列表**（→ toolNames YAML 配置） + PermissionMode 四级模式 |

---

## 十、代码规范与约束

- **Java**: 4 空格缩进，Lombok（`@Data`/`@Builder`/`@Slf4j`）替代手写样板
- **日志**: `@Slf4j` + `log.info()`；禁止 `System.out.println`
- **REST 响应**: `Response<T>` 统一包装
- **业务错误**: `AppException(ResponseCode.XXX)` 抛出
- **JSON**: domain 层使用 Jackson `ObjectMapper`，禁止 `com.alibaba.fastjson`
- **ChatModel 延迟注入**: 使用 `@Lazy`，因 ChatModel 在装配阶段动态注册
- **tool_result 格式**: 必须转为 `ToolResponseMessage`，禁止转为 `UserMessage`
- **禁止硬编码密钥**: API key 只允许在 `application-dev.yml` 中
- **提交信息**: 中文简洁命令式

---

## 十一、下一步扩展方向

| 方向 | 说明 |
|------|------|
| **FlowAgent (Orchestrator Agent)** | LLM 驱动的智能编排——自主规划、动态分配子任务、结果合成。架构已预留 `agentType="flow"` 和 `AgentFactory` 注册表 |
| **UserProxyAgent** | 人类在回路——执行到需确认步骤时暂停，等待人工输入后继续 |
| **Agent 群聊 (Group Chat)** | Speaker Selection 动态选择下一个发言 Agent，而非预定义工作流拓扑 |
| **可观测性 v2** | AgentTracer Span 更深度集成到 ReActAgent 各阶段 + Grafana Dashboard |
| **记忆系统强化** | Milvus 向量数据库支持大规模记忆 + 记忆遗忘曲线 + 冲突检测 |

---

## License

Apache License, Version 2.0

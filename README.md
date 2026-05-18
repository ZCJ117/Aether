# Aether: 企业级 AI Agent 架构

Aether ———— 企业级 AI Agent 架构  — 基于 Spring Boot 3.4.3 + 自研 AgentRuntime + DDD 分层架构。YAML 配置驱动多 Agent 编排，支持 MCP/Skills 工具集成和流式对话。

## 项目简介

Aether是一个面向企业级应用的 AI Agent 开发架构。项目采用 DDD 六边形架构设计，核心运行引擎为**自研 AgentRuntime**，提供完整的 YAML 配置化智能体定义、多工作流编排、MCP/Skills 工具集成、流式对话和会话管理能力。

### 核心价值

- **配置即 Agent**：通过 YAML 文件声明式定义智能体，无需编写代码即可完成 Agent 装配
- **自研 AgentRuntime**：完整的主循环引擎，包含多层上下文压缩、指数退避重试、并发安全工具编排
- **多 Agent 编排**：支持串行 (SEQUENTIAL)、并行 (PARALLEL)、循环 (LOOP) 三种工作流模式
- **工具生态**：即插即用 MCP 协议工具（SSE / Stdio / Local）和 Skills 技能库

## 核心特性

- **策略树装配链**：启动时自动将 YAML 配置编译为可运行的 Agent 实例（RootNode → AiApiNode → ChatModelNode → AgentNode → AgentWorkflowNode → CompilerNode）
- **AgentRuntime 主循环**：四阶段循环执行 — 上下文管理 → 模型调用 → 退出判断 → 工具执行，最大 100 轮
- **三层上下文压缩**：`applyToolResultBudget`（超长工具输出截断）→ `microCompact`（冗余消息清理）→ `autoCompact`（token 超限时 LLM 摘要压缩）
- **指数退避重试**：ModelInvoker 自动重试 3 次（1s → 2s → 4s），智能识别 Connection Reset / Timeout / 503 / 429 等可重试错误
- **GraphExecutor 多 Agent 执行器**：SEQUENTIAL 模式串行推进 + `{outputKey}` 模板传递；PARALLEL 模式 CountDownLatch 并发控制 + 事件同步转发；LOOP 模式循环迭代 + 收敛检测
- **MCP 工具工厂**：`DefaultMcpClientFactory` 按传输类型路由到 SSEToolMcpCreateService / StdioToolMcpCreateService / LocalToolMcpCreateService
- **流式对话**：RxJava 3 Flowable + SSE，支持同步和流式两种 API
- **记忆系统**：自动探测项目根目录的 `memory/MEMORY.md` 读写，支持 `{memory}` 模板注入到 Agent 指令
- **Vue 3 前端**：独立的聊天前端应用，支持 Pinia 状态管理和 Vite 构建

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.4.3 |
| 构建 | Maven | 3.x |
| AI SDK | Spring AI (BOM) | 1.1.0-M3 |
| Agent 引擎 | **自研 AgentRuntime** | — |
| 工具集成 | MCP SDK + spring-ai-agent-utils | 0.4.2 |
| 响应式 | RxJava 3 | — |
| 缓存 | Guava | 32.1.3-jre |
| JSON | Jackson (domain 层) / FastJSON 2.0.28 (config 层) | — |
| 数据库 | MySQL + MyBatis + HikariCP | 8.0.28 / 3.0.4 |
| 设计模式框架 | xfg-wrench-starter-design-framework | 3.0.0 |
| 前端 | Vue 3 + Pinia + Vue Router + Vite + Axios | — |

## 项目结构

```
aether/
├── pom.xml                              # 根 POM，管理 6 个子模块 + 全部依赖版本
│
├── aether-api/          # ★ API 层：服务接口 + DTO
│   └── src/main/java/cn/zcj/aether/api/
│       ├── IAgentService.java           # 对外服务接口定义
│       ├── dto/                         # ChatRequestDTO, ChatResponseDTO 等
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
│           ├── application-dev.yml                    # 数据库、线程池、spring.config.import agent/agents.yml
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
│       │   ├── graph/                   # AgentGraph, AgentNodeDef, AgentEdge, AgentEdgeType（IR 中间表示）
│       │   └── valobj/                  # AiAgentConfigTableVO, AiAgentRegisterVO
│       │       ├── enums/               # AgentTypeEnum
│       │       └── properties/          # AiAgentAutoConfigProperties
│       └── service/
│           ├── armory/                  # ★ 策略树装配链（YAML → Agent 运行实例）
│           │   ├── IArmoryService.java  # 装配服务接口
│           │   ├── ArmoryService.java   # 实现：遍历配置表 → 启动策略树
│           │   ├── AbstractArmorySupport.java  # 策略树节点基类（doApply + get 抽象方法）
│           │   ├── AgentRegistry.java   # Agent 注册中心（agentId → AgentGraph）
│           │   ├── factory/
│           │   │   └── DefaultArmoryFactory.java  # 策略树工厂 + DynamicContext 上下文
│           │   ├── node/                # ★ 策略树节点链
│           │   │   ├── RootNode.java           # 根节点（路由入口）
│           │   │   ├── AiApiNode.java           # 构建 OpenAiApi（baseUrl + apiKey）
│           │   │   ├── ChatModelNode.java        # ★ 核心节点：构建 ChatModel，注册 ToolCallback + ToolRegistry
│           │   │   ├── AgentNode.java            # 收集 agent 名称列表
│           │   │   ├── AgentWorkflowNode.java    # 按 type 路由到 workflow 子节点
│           │   │   ├── CompilerNode.java         # 编译 AgentGraph → 注册到 AgentRegistry
│           │   │   └── workflow/                 # 多 Agent 工作流节点
│           │   │       ├── LoopAgentNode.java
│           │   │       ├── ParallelAgentNode.java
│           │   │       └── SequentialAgentNode.java
│           │   └── matter/
│           │       ├── mcp/              # MCP 客户端工厂
│           │       │   └── client/
│           │       │       ├── TooMcpCreateService.java          # MCP 工具创建接口
│           │       │       ├── factory/DefaultMcpClientFactory.java  # 按类型路由工厂
│           │       │       └── impl/
│           │       │           ├── SSEToolMcpCreateService.java     # SSE MCP 客户端
│           │       │           ├── StdioToolMcpCreateService.java   # Stdio MCP 客户端
│           │       │           └── LocalToolMcpCreateService.java   # Local MCP 客户端
│           │       └── skills/           # Skills 技能工具
│           │           ├── ToolSkillsCreateService.java
│           │           └── impl/DefaultToolSkillsCreateService.java
│           ├── chat/ChatService.java     # ★ 对话入口：单/多 Agent 路由 + 记忆注入
│           ├── compiler/AgentGraphCompiler.java  # YAML → AgentGraph IR 编译器
│           ├── runtime/                  # ★ 运行时引擎
│           │   ├── AgentRuntime.java     # 主循环（Phase 1-4），最大 100 轮
│           │   ├── ModelInvoker.java     # LLM 调用 + 指数退避重试（3 次，1s→2s→4s）
│           │   ├── RuntimeEvent.java     # 运行时事件类型
│           │   └── TurnMessage.java      # 轮次消息封装（支持 tool_use 元数据保留）
│           ├── context/                  # 上下文管理
│           │   ├── ContextManager.java   # 三层上下文压缩
│           │   ├── TokenEstimator.java   # Token 估算
│           │   └── AutoCompactResult.java # 压缩结果封装
│           ├── memory/MemoryStore.java   # 记忆系统（MEMORY.md 读写）
│           ├── tool/                     # 工具系统
│           │   ├── Tool.java             # 工具接口定义
│           │   ├── ToolRegistry.java     # 工具注册中心
│           │   ├── ToolExecutor.java     # 工具执行器（safe 组并发 / unsafe 组串行）
│           │   ├── ToolResult.java       # 工具执行结果
│           │   ├── McpToolAdapter.java   # MCP → Tool 适配器
│           │   └── SkillsToolAdapter.java # Skills → Tool 适配器
│           └── executor/                 # 多 Agent 图执行器
│               ├── GraphExecutor.java    # ★ SEQUENTIAL / PARALLEL / LOOP 执行
│               └── ExecutionState.java   # 执行状态（模板解析 + 输出合并）
│
├── aether-infrastructure/  # 基础设施层
│   └── src/main/java/cn/zcj/aether/repository/
│       └── SessionStore.java             # 会话持久化存储
│
├── aether-trigger/         # HTTP 触发层
│   └── src/main/java/cn/zcj/aether/trigger/http/
│       └── AgentServiceController.java   # ★ REST API 入口（/api/v1/chat, /api/v1/chat_stream 等）
│
├── aether-types/           # 类型定义层
│   └── src/main/java/cn/zcj/aether/types/
│       ├── common/Constants.java         # 全局常量
│       ├── enums/ResponseCode.java       # 响应码枚举（SUCCESS/UN_ERROR/E0001/E0002 等）
│       └── exception/AppException.java   # 业务异常
│
└── docs/dev-ops/
    └── AIagent_frontend/                 # Vue 3 聊天前端
        └── src/
            ├── components/chat/          # 聊天组件
            └── stores/chat.js            # Pinia 状态管理
```

## 架构设计

### DDD 分层（禁止反向依赖）

```
trigger (HTTP 控制器)
    ↓ depends on
api (服务接口 + DTO)
    ↓ depends on
domain (核心业务逻辑) ← infrastructure (适配器实现，实现 domain 的端口)
    ↓ depends on
types (枚举 / 异常 / 常量)
```

- `domain` 层禁止从 `trigger` 或 `infrastructure` 导入
- JSON 解析：domain 层统一使用 Jackson `ObjectMapper`，禁止 `com.alibaba.fastjson`

### Agent 装配流程（ApplicationReadyEvent 触发）

```
AiAgentAutoConfig.onApplicationEvent()
  → ArmoryService.acceptArmoryAgents(tables)
    → DefaultArmoryFactory.armoryStrategyHandler() → 获取 RootNode
      → RootNode → AiApiNode (构建 OpenAiApi)
        → ChatModelNode (遍历 toolMcpList/toolSkillsList → 构建 ToolCallback[]
                         → OpenAiChatModel → registerToolsToRegistry() → registerBean())
          → AgentNode (收集 agent 名称列表)
            → AgentWorkflowNode (按 type 路由)
              ├→ LoopAgentNode      → 循环工作流
              ├→ ParallelAgentNode   → 并行工作流
              └→ SequentialAgentNode → 串行工作流
                → CompilerNode (编译 AgentGraph → 注册到 AgentRegistry)
```

### 请求时路由（ChatService）

```
POST /api/v1/chat → ChatService.handleMessage()
  │
  ├─ graph.getEdges() 非空 → GraphExecutor.execute(fullGraph)
  │     ├─ SEQUENTIAL → 串行执行 subAgents，{outputKey} 模板解析
  │     ├─ PARALLEL   → CachedThreadPool 并发，CountDownLatch，synchronized(emitter) 事件转发
  │     └─ LOOP       → 循环迭代，收敛检测（prevOutput == currentOutput）
  │
  └─ graph.getEdges() 为空 → AgentRuntime.execute(entryAgent)
        │
        while (turnCount < 100):
          Phase 1 → ContextManager.applyToolResultBudget / microCompact / autoCompact
          Phase 2 → ModelInvoker.callWithStream（重试 3 次，指数退避 1s/2s/4s）
          Phase 3 → 无 tool_use → emit done + 退出
          Phase 4 → ToolExecutor.executeBatch（safe 组并发 / unsafe 组串行）→ tool_result 回填
```

### 关键配置阈值

| 配置项 | 所在文件 | 值 |
|---|---|---|
| HTTP 连接超时 | `HttpClientConfig.java` | 30s |
| HTTP 读取超时 | `HttpClientConfig.java` | 300s (5min) |
| 并行工具超时 | `ToolExecutor.executeConcurrently()` | 60s |
| 并行 Agent 超时 | `GraphExecutor.executeParallel()` | 10min |
| 重试次数 | `ModelInvoker.java` | 3 次 |
| 初始退避 | `ModelInvoker.java` | 1s，翻倍至 15s 上限 |
| 可重试错误 | `ModelInvoker.isRetryable()` | Connection reset, Broken pipe, Timeout, 503, 502, 429 |
| 不可重试错误 | `ModelInvoker.isRetryable()` | 400, 401, 403, 404 |
| 上下文压缩阈值 | `ContextManager.java` | `(contextWindow - 20000) × 0.9` |
| 工具结果截断阈值 | `ContextManager.applyToolResultBudget()` | >50000 字符 → 截断为 500 字符 + 警告 |
| 循环最大次数 | `AgentRuntime.MAX_TURNS` | 100 |
| 循环收敛默认迭代 | `GraphExecutor.executeLoop()` | 3 次 |

## Agent YAML 配置

配置文件位于 `aether-app/src/main/resources/agent/*.yml`，通过 `application-dev.yml` 中的 `spring.config.import` 激活（默认导入 `agent/agents.yml`）。

### 配置结构示例

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
            agent-desc: 这是一个旅游规划智能体
          module:
            ai-api:                          # ★ API 连接配置
              base-url: https://api.deepseek.com
              api-key: sk-xxx
              completions-path: v1/chat/completions
            chat-model:                      # ★ 模型 + 工具配置
              model: deepseek-chat
              tool-mcp-list:                 # MCP 工具列表
                - sse:                       # SSE MCP
                    name: baidu-search
                    base-uri: http://...
                    sse-endpoint: sse?api_key=xxx
                    request-timeout: 5000
              tool-skills-list:              # Skills 技能列表
                - type: resource
                  path: agent/skills
            agents:                          # ★ Agent 定义
              - name: Agent01
                description: Agent 描述
                instruction: |
                  Agent 系统指令内容...
            runner:                          # ★ 运行器配置
              agent-name: Agent01
```

### 多 Agent 工作流配置

```yaml
# 在 module 下添加 agent-workflows:
agent-workflows:
  - type: sequential                      # 串行
    name: CodePipelineAgent
    sub-agents: [CodeWriterAgent, CodeReviewerAgent, CodeRefactorerAgent]
  - type: parallel                        # 并行
    name: parallel_translation
    sub-agents: [translator_en, translator_ja]
  - type: loop                            # 循环
    name: iterative_refinement
    max-iterations: 5
    sub-agents: [reviewer, improver]
```

配置关键点：
- `agents[].outputKey` 用于跨 Agent 传递结果，下游通过 `{outputKey}` 模板引用
- `tool-mcp-list` 支持三种传输模式：`sse` / `stdio` / `local`
- `tool-skills-list` 支持两种来源：`resource`（类路径）/ `directory`（文件系统）
- `runner.agent-name` 指向入口 Agent 或 workflow 名称

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+（可选，若有持久化需求）

### 安装与配置

```bash
# 1. 克隆项目
git clone <repo-url>
cd aether

# 2. 配置 YAML
# 编辑 aether-app/src/main/resources/application-dev.yml：
#   - 配置数据库连接（如不需要可注释 datasource 相关配置）
#   - 通过 spring.config.import 激活所需的 agent/*.yml 配置文件
#
# 编辑 aether-app/src/main/resources/agent/agents.yml：
#   - 配置 ai-api（base-url 和 api-key）
#   - 配置 chat-model（model 名称）
#   - 按需配置 tool-mcp-list 和 tool-skills-list

# 3. 构建项目
mvn clean install                                    # 全量构建
mvn clean package -pl aether-app -am                 # 仅打包 app 模块
```

### 启动

```bash
# 开发模式（直接运行 Spring Boot）
mvn spring-boot:run -pl aether-app

# 或运行打包好的 JAR
java -jar aether-app/target/aether-app.jar
```

### 验证

```bash
# 查询可用 Agent 列表
curl http://localhost:8091/api/v1/query_ai_agent_config_list

# 创建会话
curl "http://localhost:8091/api/v1/create_session?agentId=000001&userId=testUser"

# 同步对话
curl -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"agentId":"000001","userId":"testUser","message":"你好，请介绍一下你的能力"}'

# 流式对话
curl -X POST http://localhost:8091/api/v1/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"agentId":"000001","userId":"testUser","message":"请帮我写一段 Java Hello World"}'
```

### 前端启动

```bash
cd docs/dev-ops/AIagent_frontend
npm install
npm run dev          # Vite 开发服务器
```

## API 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/query_ai_agent_config_list` | 查询所有可用的 Agent 配置列表 |
| GET/POST | `/api/v1/create_session` | 创建会话（参数：agentId, userId） |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat_stream` | 流式对话（SSE） |

统一响应格式（`cn.zcj.aether.api.response.Response<T>`）：

```json
{
  "code": "0000",
  "info": "成功",
  "data": "..."
}
```

错误码：

| 编码 | 含义 |
|---|---|
| `0000` | 成功 |
| `0001` | 未知失败 |
| `0002` | 非法参数 |
| `E0001` | 智能体ID不存在 |
| `E0002` | 智能体MCP配置不在可加载范围 |

## 代码规范

- Java: 4 空格缩进，Lombok（`@Data`/`@Builder`/`@Slf4j`/`@Getter`）替代手写样板
- 日志: `@Slf4j` + `log.info()`；禁止 `System.out.println` 和手写 `LoggerFactory.getLogger`
- REST 响应: `cn.zcj.aether.api.response.Response<T>` 统一包装
- 业务错误: `AppException(ResponseCode.XXX)` 抛出
- JSON: domain 层使用 Jackson `ObjectMapper`，config 层可使用 FastJSON
- 新建 DTO/枚举: 同目录必须包含 `package-info.java`
- 提交信息: 中文简洁命令式（如 `修复中文换行问题`、`增加重试机制`）

## 关键约束

- **ChatModel 延迟注入**：ChatModel 在 `ChatModelNode` 装配阶段动态注册，所有消费者必须使用 `@Lazy` 注解（见 `ChatService.java:59`）
- **tool_result 消息格式**：必须转为 `ToolResponseMessage`（role: "tool" + tool_call_id），禁止转为 `UserMessage`，否则 API 返回 400
- **禁止硬编码密钥**：API key 等敏感信息仅允许放在 `application-dev.yml` 本地值中
- **禁止未更新根 POM 前新增子模块**：新增 Maven 模块必须同步更新根 `pom.xml` 的 `<modules>` 声明
- **禁止提交 `data/` 目录**

## License

Apache License, Version 2.0

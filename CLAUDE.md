# AI Agent Scaffold Lite

企业级 AI Agent 脚手架 — Spring Boot 3.4.3 + 自研 AgentRuntime + DDD 分层。
YAML 配置驱动的多 Agent 编排，支持 MCP/Skills 工具集成和流式对话。

## 技术栈

- **后端**: Java 17, Spring Boot 3.4.3, Maven 3.x, Spring AI 1.1.0-M3
- **Agent 引擎**: 自研 AgentRuntime（主循环 + 多层上下文压缩 + 并发安全工具编排 + 指数退避重试）
- **前端**: Vue 3 + Pinia + Vite（`docs/dev-ops/AIagent_frontend/`）
- **依赖**: Lombok（必须）, RxJava3, Jackson, Guava, spring-ai-agent-utils (SkillsTool)
- **容器**: Docker (openjdk:17-jdk-slim)

## 常用命令

```bash
mvn clean install                                    # 全量构建
mvn clean compile -pl ai-agent-scaffold-lite-domain -am     # 仅编译 domain 模块
mvn clean package -pl ai-agent-scaffold-lite-app -am        # 打包可执行 JAR
mvn test -DskipTests=false -pl ai-agent-scaffold-lite-app   # 运行测试
cd ai-agent-scaffold-lite-app && bash build.sh              # Docker 构建
```

## 模块结构（6 模块 + 1 前端）

| 模块 | 职责 | 关键路径 |
|---|---|---|
| `ai-agent-scaffold-lite-app` | 启动引导、YAML 配置、HttpClientConfig | `src/main/resources/agent/*.yml` |
| `ai-agent-scaffold-lite-api` | 服务接口、DTO | `cn.bugstack.ai.api` |
| `ai-agent-scaffold-lite-domain` | **核心**：Agent 运行时、编译器、上下文、工具、执行器、记忆 | `cn.bugstack.ai.domain` |
| `ai-agent-scaffold-lite-infrastructure` | 会话存储等基础设施适配器 | `cn.bugstack.ai.infrastructure` |
| `ai-agent-scaffold-lite-trigger` | REST 控制器 | `cn.bugstack.ai.trigger.http` |
| `ai-agent-scaffold-lite-types` | 枚举、异常、常量 | `cn.bugstack.ai.types` |
| `docs/dev-ops/AIagent_frontend` | Vue 3 聊天前端 | `src/components/chat/`, `src/stores/chat.js` |

## Domain 核心包结构

```
domain/agent/service/
├── armory/            # 策略树装配链（启动时 YAML→Bean）
│   ├── factory/       # DefaultArmoryFactory + DynamicContext
│   ├── node/          # RootNode, AiApiNode, ChatModelNode, AgentNode, AgentWorkflowNode, CompilerNode
│   │   └── workflow/  # LoopAgentNode, ParallelAgentNode, SequentialAgentNode
│   └── matter/
│       ├── mcp/       # MCP 客户端工厂: SSE / Stdio / Local
│       └── skills/    # Skills 工具创建服务
├── chat/              # ChatService — 对话入口，单/多Agent 路由
├── compiler/          # AgentGraphCompiler — YAML→AgentGraph IR
├── runtime/           # AgentRuntime（主循环）, ModelInvoker（API调用+重试）, RuntimeEvent, TurnMessage
├── context/           # ContextManager（applyToolResultBudget / microCompact / autoCompact）
├── memory/            # MemoryStore — .claude/memory/MEMORY.md 读写
├── tool/              # Tool 接口, ToolRegistry, ToolExecutor, McpToolAdapter, SkillsToolAdapter, ToolResult
└── executor/          # GraphExecutor（SEQUENTIAL/PARALLEL/LOOP）, ExecutionState

model/graph/           # AgentGraph, AgentNodeDef, AgentEdge, AgentEdgeType（IR 模型）
model/valobj/          # AiAgentConfigTableVO, AiAgentRegisterVO, AiAgentAutoConfigProperties
```

## 架构规则

### DDD 分层（禁止反向依赖）
- `trigger` → `api` → `domain`；`infrastructure` 实现 `domain` 的适配器端口
- `domain` 禁止从 `trigger` 或 `infrastructure` 导入
- JSON 解析统一使用 Jackson `ObjectMapper`，禁止 `com.alibaba.fastjson`

### Agent 装配流程（ApplicationReadyEvent 时执行）
```
AiAgentAutoConfig → ArmoryService.acceptArmoryAgents()
  → RootNode → AiApiNode（构建 OpenAiApi）
  → ChatModelNode（构建 ChatModel + ToolCallback + 注册 Bean + populate ToolRegistry）
  → AgentNode（收集 agent 名列表）
  → AgentWorkflowNode（按 type 路由）
  → [LoopAgentNode | ParallelAgentNode | SequentialAgentNode]
  → CompilerNode（编译 AgentGraph + 注册到 AgentRegistry）
```

### ChatModelNode 关键职责（`armory/node/ChatModelNode.java`）
1. 遍历 `toolMcpList` → `DefaultMcpClientFactory` → `ToolCallback[]`
2. 遍历 `toolSkillsList` → `ToolSkillsCreateService` → `ToolCallback[]`
3. 合并所有 ToolCallback → `OpenAiChatOptions.toolCallbacks()`
4. `registerToolsToRegistry()` → MCP 工具经 `McpToolAdapter.adapt()`、Skills 工具经 `SkillsToolAdapter.adapt()` 注册到 `ToolRegistry`
5. `registerBean("chatModel", ChatModel.class, chatModel)` — 动态注册 Spring Bean

### 请求时路由（`chat/ChatService.java`）
```
POST /api/v1/chat → ChatService.handleMessage()
  ├── graph.getEdges() 非空 → GraphExecutor.execute(fullGraph)
  │     ├── SEQUENTIAL → 串行执行 subAgents，{outputKey} 模板解析
  │     ├── PARALLEL   → 并发执行 subAgents，CountDownLatch + 事件同步转发
  │     └── LOOP       → 循环迭代 subAgents，收敛检测
  └── graph.getEdges() 为空 → AgentRuntime.execute(entryAgent)
```

### AgentRuntime 主循环（`runtime/AgentRuntime.java`）
```
while (turnCount < 100):
  Phase1 → ContextManager.applyToolResultBudget / microCompact / autoCompact
  Phase2 → ModelInvoker.callWithStream（重试3次，指数退避1s/2s/4s）
  Phase3 → 无 tool_use → emit done + 退出
  Phase4 → ToolExecutor.executeBatch（safe组并发/safe组串行）→ feed tool_result 回对话
```

### 工具调用消息格式（关键：避免 API 400）
- `tool_result` 必须转为 `ToolResponseMessage`（role: "tool" + tool_call_id + tool_name），禁止转为 `UserMessage`
- assistant 消息含 tool_use 时必须保留 `toolCalls` 元数据写入 `TurnMessage`

### ChatModel 延迟注入
- `ChatModel` 在 `ChatModelNode` 装配阶段动态注册，所有消费者必须使用 `@Lazy` 注解注入：
  - `ChatService.java:56` — `@Lazy private ChatModel chatModel`
  - `ContextManager` 内部方法参数注入，避免字段级依赖

## Agent YAML 配置

- 文件位置: `app/src/main/resources/agent/*.yml`
- 通过 `application-dev.yml` 的 `spring.config.import` 激活
- 必填: `ai-api`（base-url, api-key）, `chat-model`（model）, `agents[]`, `runner.agent-name`
- 可选: `agent-workflows[]`（type: sequential/parallel/loop, subAgents[]）, `tool-mcp-list[]`, `tool-skills-list[]`
- `agents[].outputKey` 跨 Agent 传结果，下游 `{outputKey}` 模板引用
- 参考 `agent/only-one-agent.yml` 作为最小配置模板

## 配置要点

| 配置项 | 文件 | 值 |
|---|---|---|
| HTTP 连接超时 | `HttpClientConfig.java` | 30s |
| HTTP 读取超时 | `HttpClientConfig.java` | 300s (5min) |
| 重试次数 | `ModelInvoker.java` | 3 次，初始退避 1s，翻倍至 15s 上限 |
| 可重试错误 | `ModelInvoker.isRetryable()` | Connection reset, Broken pipe, Timeout, 503, 502, 429 |
| 不可重试错误 | `ModelInvoker.isRetryable()` | 400, 401, 403, 404 |
| 上下文压缩阈值 | `ContextManager.java` | `(contextWindow - 20000) * 0.9` |
| 工具结果截断 | `ContextManager.applyToolResultBudget()` | >50000 字符 → 500 字符 + 警告 |
| 并行工具超时 | `ToolExecutor.executeConcurrently()` | 60s |
| 循环最大次数 | `AgentRuntime.MAX_TURNS` | 100 |

## 代码风格

- Java: 4 空格缩进，Lombok（`@Data`/`@Builder`/`@Slf4j`/`@Getter`）替代手写样板
- 日志: `@Slf4j` + `log.info()`；禁止 `System.out.println` 和 `LoggerFactory.getLogger`
- REST 响应: `cn.bugstack.ai.api.response.Response<T>` 包装
- 业务错误: `AppException(ResponseCode.XXX)` 抛出
- 新建 DTO/枚举: 同目录必须包含 `package-info.java`
- JSON: Jackson `ObjectMapper`，禁止 domain 层使用 FastJSON
- 前端 CSS: `word-break: normal`, `overflow-wrap: break-word`, `white-space: pre-line`
- 前端文本: `cleanMessageText()` 预处理合并多余空白/换行

## 禁止事项

- 禁止未更新根 `pom.xml` 的 `<modules>` 前新增 Maven 模块
- 禁止提交 `data/` 目录内容
- 禁止硬编码 API key / 密码于源码（仅允许 `application-dev.yml` 本地值）
- 禁止 `tool_result` 转为 `UserMessage`（必须 `ToolResponseMessage`）
- 禁止 `domain` 模块使用 `com.alibaba.fastjson`
- 禁止 `@Resource ChatModel` 不加 `@Lazy`（ChatModel 在装配阶段动态注册）

## Git 工作流

- 提交信息用中文简洁命令式（`修复中文换行问题`, `增加重试机制`）
- 修改/提交前阅读 `docs/git-instructions.md`

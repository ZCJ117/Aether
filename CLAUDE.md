# AI Agent Scaffold Lite

企业级 AI Agent 脚手架 — Spring Boot 3.4.3 + 自研 Agent 运行时 + DDD 分层。
YAML 配置驱动的多 Agent 编排，支持 MCP 工具集成和流式对话。

## 技术栈

- **后端**: Java 17, Spring Boot 3.4.3, Maven 3.x, Spring AI 1.1.0-M3
- **Agent 引擎**: 自研 AgentRuntime（主循环 + 上下文压缩 + 工具编排 + 重试容错）
- **前端**: Vue 3 + Pinia + Vite（`docs/dev-ops/AIagent_frontend/`）
- **依赖**: Lombok（必须）, RxJava3, Jackson, Guava
- **容器**: Docker (openjdk:17-jdk-slim)

## 常用命令

```bash
mvn clean install                                 # 全量构建
mvn clean compile -pl ai-agent-scaffold-lite-domain -am  # 仅编译 domain 模块
mvn clean package -pl ai-agent-scaffold-lite-app -am     # 打包可执行 JAR
mvn test -DskipTests=false -pl ai-agent-scaffold-lite-app  # 运行测试
cd ai-agent-scaffold-lite-app && bash build.sh           # Docker 构建
```

## 模块结构与职责（6 模块 + 1 前端）

| 模块 | 职责 | 关键路径 |
|---|---|---|
| `ai-agent-scaffold-lite-app` | 启动引导、YAML 配置、全局 Config | `src/main/resources/agent/*.yml` |
| `ai-agent-scaffold-lite-api` | 服务接口、DTO | `cn.bugstack.ai.api` |
| `ai-agent-scaffold-lite-domain` | **核心**：Agent 运行时、编译器、上下文、工具、执行器 | `cn.bugstack.ai.domain` |
| `ai-agent-scaffold-lite-infrastructure` | 会话存储等基础设施 | `cn.bugstack.ai.infrastructure` |
| `ai-agent-scaffold-lite-trigger` | REST 控制器 | `cn.bugstack.ai.trigger.http` |
| `ai-agent-scaffold-lite-types` | 枚举、异常、常量 | `cn.bugstack.ai.types` |
| `docs/dev-ops/AIagent_frontend` | Vue 3 聊天前端 | `src/components/chat/`, `src/stores/chat.js` |

## Domain 核心包结构（升级后）

```
domain/agent/service/
├── armory/          # 策略树装配链（YAML→OpenAiApi→ChatModel→CompilerNode）
│   ├── factory/     # DefaultArmoryFactory + DynamicContext
│   ├── node/        # 策略树节点: RootNode, AiApiNode, ChatModelNode, AgentNode, AgentWorkflowNode, CompilerNode
│   │   └── workflow/  # LoopAgentNode, ParallelAgentNode, SequentialAgentNode
│   └── matter/mcp/  # MCP 客户端（SSE/Stdio/Local）
├── chat/            # ChatService — 对话入口
├── compiler/        # AgentGraphCompiler — YAML→AgentGraph IR 编译
├── runtime/         # AgentRuntime（主循环）, ModelInvoker（API调用+重试）, RuntimeEvent, TurnMessage
├── context/         # ContextManager（多层压缩）, TokenEstimator, AutoCompactResult
├── memory/          # MemoryStore — .claude/memory/MEMORY.md 读写
├── tool/            # Tool 接口, ToolRegistry, ToolExecutor（并发安全分组）, McpToolAdapter
└── executor/        # GraphExecutor（SEQUENTIAL/PARALLEL/LOOP）, ExecutionState

model/graph/         # AgentGraph, AgentNodeDef, AgentEdge, AgentEdgeType（IR 模型）
model/valobj/        # AiAgentConfigTableVO, AiAgentRegisterVO
```

## 架构规则

### DDD 分层（禁止违反）
- `trigger` → `api` → `domain`；禁止反向依赖
- `domain` 禁止从 `trigger` 或 `infrastructure` 导入
- `infrastructure` 实现 `domain` 的适配器端口

### Agent 装配流程（启动时执行，不可打断）
```
ArmoryService.acceptArmoryAgents()
  → RootNode → AiApiNode（构建 OpenAiApi）
  → ChatModelNode（构建 ChatModel + MCP 工具 + 注册 Bean）
  → AgentNode（收集 agent name 列表）
  → AgentWorkflowNode（按 type 路由 workflow）
  → [LoopAgentNode|ParallelAgentNode|SequentialAgentNode]
  → CompilerNode（编译 AgentGraph + 注册到 AgentRegistry）
```

### 运行时主循环（请求时）
```
ChatService → AgentRuntime.queryLoop() [while(turnCount < 100)]
  → ContextManager.applyToolResultBudget / microCompact / autoCompact
  → ModelInvoker.callWithStream（重试 3 次，指数退避 1s/2s/4s）
  → 解析 tool_use → ToolExecutor.executeBatch（safe组并行/unsafe组串行）
  → needsFollowUp? → 继续循环 : 退出
```

### 工具调用消息格式（关键：避免 API 400）
- `tool_result` 必须转为 `ToolResponseMessage`（role: "tool" + tool_call_id），禁止转为 `UserMessage`
- assistant 消息若有 tool_use，必须保留 `toolCalls` 元数据到 `TurnMessage`

## Agent YAML 配置规范

- 文件位置: `ai-agent-scaffold-lite-app/src/main/resources/agent/*.yml`
- 在 `application-dev.yml` 的 `spring.ai.agent.imports` 中激活
- 必填字段: `ai-api`（base-url, api-key）, `chat-model`（model）, `agents[]`, `runner.agent-name`
- 可选: `agent-workflows[]`（type: sequential/parallel/loop）, `tool-mcp-list[]`（sse/stdio/local）, `tool-skills-list[]`
- `agents[].outputKey` 用于跨 Agent 传递结果，下游用 `{outputKey}` 引用
- 参考 `agent/only-one-agent.yml` 作为最小配置模板

## 代码风格

- 4 空格缩进，Lombok 注解（`@Data`/`@Builder`/`@Slf4j`/`@Getter`）替代手写代码
- 禁止 `System.out.println` 和 `LoggerFactory.getLogger`；统一使用 `@Slf4j` + `log.info()`
- REST 响应用 `cn.bugstack.ai.api.response.Response<T>` 包装
- 业务错误用 `AppException(ResponseCode.XXX)` 抛出
- 新建 DTO/枚举时必须在同目录包含 `package-info.java`
- JSON 解析使用 Jackson `ObjectMapper`，禁止在 domain 模块使用 FastJSON

## 前端约定
- 消息气泡 CSS: `word-break: normal`, `overflow-wrap: break-word`, `white-space: pre-line`
- 消息文本在 `updateMessage()` 中通过 `cleanMessageText()` 预处理
- 聊天 API: `POST /api/v1/chat`，返回 `{ content }`
- 会话 API: `POST /api/v1/create_session`，返回 `{ sessionId }`

## 路径范围规则

| 规则 | 适用路径 |
|---|---|
| DDD 分层约束、策略树节点 | `**/domain/**/*.java` |
| Controller 仅做参数转换 | `**/trigger/http/*.java` |
| 前端 CSS/文本预处理 | `docs/dev-ops/AIagent_frontend/**/*.{vue,js,css}` |
| YAML 配置格式 | `**/resources/agent/*.yml` |
| Jackson JSON，禁止 FastJSON | `**/domain/**/*.java` |
| `@Slf4j` 日志，禁止 System.out | `**/*.java` |

## 配置要点

### HTTP 超时（`HttpClientConfig.java`）
- `sun.net.client.defaultConnectTimeout = 30000`（30 秒）
- `sun.net.client.defaultReadTimeout = 300000`（5 分钟）

### 重试策略（`ModelInvoker.java`）
- 最大重试 3 次，初始退避 1s，每次翻倍，上限 15s
- 可重试: Connection reset, Broken pipe, Timeout, 503, 502, 429
- 不可重试: 400, 401, 403, 404

### 上下文压缩（`ContextManager.java`）
- 触发阈值: `(contextWindow - 20000) * 0.9`
- `microCompact`: 两遍扫描删除被后续 Edit/Write 覆盖的冗余工具调用对
- `autoCompact`: 调用 LLM 生成摘要，失败时降级为字符串拼接
- `applyToolResultBudget`: 超 50000 字符的工具结果截断为 500 字符 + 警告

### 并发安全分组（`ToolExecutor.java`）
- `isConcurrencySafe() == true` → 并行执行（60s 超时）
- `isConcurrencySafe() == false` → 串行执行

## 禁止事项

- 禁止未更新根 `pom.xml` 的 `<modules>` 前新增 Maven 模块
- 禁止提交 `data/` 目录内容
- 禁止硬编码凭证于源码中（仅允许 `application-dev.yml` 本地值）
- 禁止在 `convertToSpringMessages()` 中将 `tool_result` 转为 `UserMessage`
- 禁止在 `domain` 模块直接使用 `com.alibaba.fastjson`（已统一用 Jackson）

## Git 工作流
- 提交信息用中文简洁命令式（`修复中文换行问题`, `增加重试机制`）
- 修改/提交前阅读 `docs/git-instructions.md`

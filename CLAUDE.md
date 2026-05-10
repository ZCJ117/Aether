# 项目上下文

AI Agent Scaffold Lite — 基于 Spring Boot + Google ADK 的轻量级企业级 AI Agent 脚手架。
DDD 架构，配置驱动的 agent/workflow/MCP 编排。

## 技术栈

- Java 17, Spring Boot 3.4.3, Maven 3.x
- Google ADK 0.5.0, Spring AI 1.1.0-M3, LangChain4j 1.4.0
- MySQL 8.x, MyBatis 3.0.4（当前禁用）, HikariCP
- FastJSON 2.0.28, Guava 32.1.3, Retrofit2, RxJava3
- Lombok（必须使用 — 所有 POJO 类使用 `@Data`, `@Builder`, `@Slf4j`）
- Docker (openjdk:17-jdk-slim), Logback 日志

## 模块结构

| 模块 | 用途 | 包根路径 |
|---|---|---|
| `ai-agent-scaffold-lite-api` | 服务接口与 DTO | `cn.bugstack.ai.api` |
| `ai-agent-scaffold-lite-types` | 常量、枚举、异常 | `cn.bugstack.ai.types` |
| `ai-agent-scaffold-lite-domain` | 核心业务逻辑、agent 节点 | `cn.bugstack.ai.domain` |
| `ai-agent-scaffold-lite-infrastructure` | DAO、网关、redis 适配器 | `cn.bugstack.ai.infrastructure` |
| `ai-agent-scaffold-lite-trigger` | REST 控制器、定时任务、监听器 | `cn.bugstack.ai.trigger` |
| `ai-agent-scaffold-lite-app` | 启动引导、配置、agent YAML 定义 | `cn.bugstack.ai` |

## 架构规则

- 所有 Java 类使用 `cn.bugstack.ai.*` 包路径 — 禁止在此根路径之外创建类。
- 强制 DDD 分层：`trigger` -> `api` -> `domain` -> `infrastructure`。
  - `trigger` 依赖 `api`；禁止直接从 `domain` 或 `infrastructure` 导入。
  - `domain` 禁止从 `trigger` 或 `infrastructure` 导入。
  - `infrastructure` 实现 `domain` 的适配器端口。
- REST 端点位于 `ai-agent-scaffold-lite-trigger/src/main/java/cn/bugstack/ai/trigger/http/`。
- Agent 工作流节点（顺序/并行/循环）位于 `domain/.../armory/node/workflow/`。
- MCP 客户端/服务端代码位于 `domain/.../armory/matter/mcp/`。
- Agent 定义为 YAML 文件，位于 `ai-agent-scaffold-lite-app/src/main/resources/agent/`。
- 参考 `agent/demo.yml` 了解所有支持的 agent 配置字段。

## 常用命令

```bash
# 构建整个项目
mvn clean install

# 仅构建 app 模块（生成可执行 JAR）
mvn clean package -pl ai-agent-scaffold-lite-app -am

# 运行测试（app 模块默认跳过）
mvn test -DskipTests=false

# 运行单个测试类
mvn test -pl ai-agent-scaffold-lite-app -Dtest=cn.bugstack.ai.test.domain.agent.ChatServiceTest

# Docker 构建
cd ai-agent-scaffold-lite-app && bash build.sh
```

## 代码风格

- Java 使用 4 空格缩进（与现有代码库一致）。
- 使用 Lombok 注解替代手动编写 getter/setter/构造函数/日志器：
  - DTO 和值对象使用 `@Data` + `@Builder`
  - 日志使用 `@Slf4j`（禁止手动使用 `LoggerFactory.getLogger`）
  - 选择性访问使用 `@Getter` / `@Setter`
- 所有 DTO 和枚举必须在其目录下包含 `package-info.java`。
- 提交信息使用中文，简洁的命令式风格（例如 `增加注释`, `修改配置`）。
- REST 响应包装器：始终使用 `cn.bugstack.ai.api.response.Response`。
- 错误码：在 `cn.bugstack.ai.types.enums.ResponseCode` 中定义；业务错误抛出 `AppException`。

## Agent 配置规范

- Agent YAML 文件放置在 `src/main/resources/agent/` 目录下。
- 通过在 `application-dev.yml` 的 `spring.ai.agent.imports` 列表中添加来激活 agent 配置。
- 每个 agent 配置定义：`agent-id`、`api`（端点 + 模型）、`workflow`（sequential/parallel/loop）、`agents`（名称 + 指令）、`mcp`（工具提供者）、`skills`。
- MCP 工具提供者：远程使用 `type: sse`，本地使用 `type: stdio`。
- 提示词文件使用 `.prompt.md` 扩展名，与 agent YAML 文件放在一起。

## 测试

- 测试类位于 `ai-agent-scaffold-lite-app/src/test/java/cn/bugstack/ai/test/`。
- 测试包与模块对应：`test.api.agent`、`test.api.tool.skills`、`test.domain.agent`。
- 集成测试使用 `@SpringBootTest` + `@RunWith(SpringRunner.class)`（JUnit 4）。
- 测试资源（图片、固定数据）位于 `src/test/resources/`。
- 测试默认跳过（`<skipTests>true</skipTests>`）— 使用 `-DskipTests=false` 运行。

## 关键文件

| 文件 | 用途 |
|---|---|
| `pom.xml`（根目录） | 多模块 Maven 配置、依赖版本 |
| `application-dev.yml` | 开发环境数据库、线程池、agent 导入 |
| `agent/demo.yml` | 完整的 agent 配置参考/模板 |
| `logback-spring.xml` | 日志配置：控制台 + 异步文件，100MB 滚动 |
| `Dockerfile` | openjdk:17-jdk-slim，中国时区 |
| `.claude/settings.local.json` | Claude Code 允许的命令 |

## 禁止事项

- 未更新根 `pom.xml` 的 `<modules>` 列表前，禁止添加新的 Maven 模块。
- 禁止提交 `data/` 目录内容（已被 gitignore）。
- 禁止硬编码凭证 — 仅使用 `application-dev.yml` 中的本地值。
- 禁止使用 `System.out.println` — 使用 `@Slf4j` 和 `log.info()`。
- 禁止直接导入 `com.alibaba.fastjson.JSON` — 使用 FastJSON2 的 `JSON.toJSONString()` / `JSON.parseObject()`。

## 项目文档与规则

- 项目概述：参阅 [`README.md`](./README.md)
- 可用 npm 命令：参阅 [`package.json`](./package.json)
- Git 工作流：所有提交必须遵循 [`docs/git-instructions.md`](./docs/git-instructions.md)（修改/提交前请先阅读）

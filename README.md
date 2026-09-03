# Aether

> 生产级多 Agent AI 运行时后端 —— 基于 Spring Boot 3 + Spring AI 的自研 Agent 编排框架

![CI](https://github.com/zuo-changjian/ai-agent-scaffold-lite/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen)
![Tests](https://img.shields.io/badge/tests-688%20passing-success)
![Coverage](https://img.shields.io/badge/coverage-51.4%25%20line-brightgreen)

## 量化数字（路线图交付）

| 指标 | 数值 | 来源 |
|------|------|------|
| 测试用例 | **688**（0 失败；另有 3 个 Testcontainers IT 需 Docker） | `mvn -B verify`（无 Docker 加 `-DskipITs`） |
| 行覆盖率（全项目 / domain） | **51.4% / 55.4%**（全项目达标 ≥40%；domain 距 60% 目标持续攻坚中） | [docs/coverage-baseline.md](docs/coverage-baseline.md) |
| Eval 评测通过率（确定性模式） | **100%**（50 例：工具选择 20 / 多步编排 15 / 上下文保持 10 / 权限协议 5） | [docs/eval-report.md](docs/eval-report.md) |
| A/B 回归捕获 | 基线 100% → 变异后 98%，失败用例精确定位 | `scripts/eval-ab-demo.sh` |
| 并发容量 | **50 VU × 10 轮流式对话，0 错误**，轮 p95 稳定 ~4.6s（mock 节奏） | [docs/benchmark-report.md](docs/benchmark-report.md) |
| 容错混沌恢复 | 429×3 → 500×2 → 成功，恢复 **197s**，最终成功率 **100%** | `benchmark/run.sh` |
| LLM 响应缓存 | 冷调用 4,577ms → 命中后 p95 **14ms**（**331x**） | `aether.cache.llm.hitrate` |
| 真流式 A/B | TTFT 真流式 ≈ 缓冲（Δ<2%）——修复前基线；`stream()` 单帧聚合缺陷已于 2026-09-03 修复（逐帧透传 + 首帧前 failover） | [docs/benchmark-report.md](docs/benchmark-report.md) |
| 多实例线性度（P2-1.4） | 单实例 1,144 RPS → 双实例 2,036 RPS = **1.78x**，64 并发 0 错误，PASS | [docs/scaling-benchmark-report.md](docs/scaling-benchmark-report.md) |

## 5 条命令快速开始

```bash
git clone https://gitee.com/zuo-changjian/ai-agent-scaffold-lite.git && cd ai-agent-scaffold-lite/aether
export DB_PASSWORD=123456 JWT_SECRET=aether-dev-jwt-secret-key-min-32-chats!!
docker compose -f docker/docker-compose-secure.yml up -d --build
curl -fsS http://localhost:8091/actuator/health
# 冒烟（注册 → 登录 → chat）：见 QUICKSTART.md
```

更多路径（压测栈、Python 四服务、无 Docker 本地验证）：[QUICKSTART.md](QUICKSTART.md)

## P1 生产化能力速览（2026-08 交付）

| 能力 | 说明 | 入口 |
|---|---|---|
| 分布式限流 | 令牌桶算法，`memory`/`redis` 双模式配置切换（`aether.security.rate-limit.mode`），Redis 故障自动降级内存 + 告警指标 | [docs/capacity-planning.md](docs/capacity-planning.md) |
| 消息队列解耦 | Kafka 双链路：审计削峰批量落库 + 会话统计聚合（`dashboard_stats`）；幂等去重、手动 ack、重试→DLT（`aether.kafka.enabled`） | `types/messaging` + `trigger/listener` |
| 监控告警 | Prometheus + Grafana + Alertmanager 全栈一键起（含 7 条告警规则 + 12 面板），通知通道可实测 | [docs/monitoring-guide.md](docs/monitoring-guide.md) |
| 高并发可观测 | 模型等待线程 Gauge / 超时率 / 全部线程池队列利用率指标 + 容量规划公式 | [docs/capacity-planning.md](docs/capacity-planning.md) |
| RAG 三级检索 | 查询改写 → 混合召回（pgvector + PG 全文 bigram-GIN，RRF 单 SQL）→ Python 重排，逐级降级开关（`aether.rag.*`） | [docs/rag-pipeline.md](docs/rag-pipeline.md) |
| 记忆生命周期 | 遗忘曲线（保留分软删/复活）+ 写入重要性门槛 + 冲突合并三策略 | [docs/memory-lifecycle.md](docs/memory-lifecycle.md) |
| 集成测试 | Testcontainers（pgvector/Redis/Kafka）`mvn verify -Pintegration`，CI 独立 job | `.github/workflows/ci.yml` |

全栈（业务 + Redis/Kafka + 监控栈）一键体验：

```bash
cp docker/.env.example docker/.env   # 填 JASYPT_MASTER_PASSWORD / DB_PASSWORD / JWT_SECRET
docker compose -f docker/docker-compose-fullstack.yml --env-file docker/.env up -d
# Grafana: http://localhost:3000 | Prometheus: 9090 | Alertmanager: 9093 | 告警回显: curl localhost:8081/alerts
```

## P2 生产化能力速览（2026-09 交付）

| 能力 | 说明 | 入口 |
|---|---|---|
| 水平扩展 | LLM 缓存 Caffeine L1 + Redis L2（`aether.cache.llm.redis-enabled`，Redis 异常自动降级 L1-only）；`RedisSessionRepository` 补齐全部仓储接口（软删语义与 PG 对齐） | [docs/horizontal-scaling-design.md](docs/horizontal-scaling-design.md) |
| 多实例部署 | 双实例 + Nginx：携带会话头走一致性哈希粘性路由，新建会话 `least_conn` 分流 | `docker/docker-compose-scale.yml` + `docker/nginx/` |
| 编排重构 | `GraphExecutor` 收敛为统一生命周期（trace / MDC / 钩子 / 异常完成），编排下沉为 Sequential / Parallel / Loop / SubAgent / EventDriven 五种策略 + `GraphFlowCoordinator`（最大类 262 行） | [docs/p2-roadmap-delivery.md](docs/p2-roadmap-delivery.md) |
| 规划能力 | `PlanActAgent` 中途重规划（连续工具失败 / 检查点评估不达标触发，保留已完成步骤）+ 任务后反思沉淀 notes；指标 `aether.agent.plan.replans / completions / reflections.total` | [docs/p2-roadmap-delivery.md](docs/p2-roadmap-delivery.md) |
| 日志排障 | prod 日志收紧 INFO；三步排障手册（关联 ID → JSON 日志 / MDC / graph trace → 审计表） | [docs/log-troubleshooting.md](docs/log-troubleshooting.md) |
| 数据库迁移链 | `V1__baseline.sql` 幂等基线（用户 / 令牌 / 审计 / session / pgvector+HNSW）→ V5 完整链路 | `data/sql/` |

```bash
# 多实例演示 + 线性度压测（1.78x PASS）
docker compose -f docker/docker-compose-scale.yml up -d
python scripts/scaling-benchmark.py --mode all --manage-stack --duration 60
```

## 缺陷修复记录（2026-09-03，四缺陷端到端修复）

| 缺陷 | 修复 | 回归测试 |
|---|---|---|
| `ModelCallCache` 缓存 key 用 32 位 `Objects.hash` 且不含消息 role——哈希碰撞导致不同对话误命中 | key 改为 SHA-256 全量摘要（modelName + 逐条 `role+text`），碰撞概率降至工程可忽略 | `ModelCallCacheTest`（碰撞回归/稳定性/区分度） |
| `ResilientChatModelExecutor.stream()` 委托同步 `call()` 聚合为单帧——真流式 TTFT 失效 | stream 改逐帧透传；首帧前按恢复分支重建流（退避/压缩/凭据轮换/fallback），首帧后失败不重放 | `ResilientChatModelExecutorTest`（多帧/首帧前后失败） |
| `ReActAgent.persistState` 伪持久化 + 挂起 PAUSED 状态被收尾无条件覆写为 IDLE——权限挂起恢复协议失效 | 挂起瞬间经可选注入的 `SessionRepository` 真实落盘快照；`execute()` 收尾仅 RUNNING→IDLE，PAUSED 保持 | `ReActAgentStatePersistenceTest`（落盘 + loadState 恢复回路） |
| `MiddlewareChain.applyActing` 吞异常继续放行——权限链被击穿成 fail-open | 改 fail-closed：任一中间件异常即中断链路并拒绝本批全部工具调用（log.error 含堆栈） | `MiddlewareChainTest`（短路/透传/正常链） |

四个测试类 22 用例全绿 + domain/app 模块回归通过，详见 [docs/superpowers/plans/2026-09-02-four-defect-fixes.md](docs/superpowers/plans/2026-09-02-four-defect-fixes.md)（含根因分析与遗留项记录）。

---

Aether 是一个采用 **DDD 六边形（Ports & Adapters）分层 + 模块化单体** 架构的 Java 后端项目，提供了一套完整的 AI Agent 运行时：从 YAML 声明式配置「装配」出可运行的智能体，支持单 Agent 的 ReAct 推理-行动循环、多 Agent 工作流（sequential / parallel / loop / subagent / eventdriven 五种编排策略）编排、MCP 工具接入、记忆系统、模型容错/凭据轮换、工具权限审批（Human-in-the-loop）、会话持久化与恢复，以及基于 JWT 的认证与审计体系。

---

## 目录

- [项目简介](#项目简介)
- [主要特性](#主要特性)
- [技术栈](#技术栈)
- [目录结构说明](#目录结构说明)
- [安装步骤](#安装步骤)
- [配置说明](#配置说明)
- [使用说明](#使用说明)
- [API 接口](#api-接口)
- [示例代码](#示例代码)
- [许可证](#许可证)

---

## 项目简介

Aether 是一个六模块的 Maven 多模块工程（`groupId=cn.zcj.aether`，`artifactId=aether`，`version=1.0`），主入口为 `cn.zcj.aether.Application`，默认监听端口 **8091**。

核心设计思想是「**配置即装配**」：通过 `agent/agents.yml` 等 YAML 文件声明智能体的模型、工具（MCP / Skills）、提示词与工作流，应用在启动时（`AiAgentAutoConfig` 监听 `ApplicationReadyEvent`）将这些配置翻译为可运行的 Agent 实例并注册到 `AgentRegistry`，运行时按 `agentId` 路由到对应智能体执行对话。

整个系统借鉴了 **AgentScope / CrewAI / AutoGen / MetaGPT** 等 Agent 框架的设计（如 ReAct 主循环、状态双模式访问、检查点恢复、权限挂起等），并参考 `hermes-agent-main` 的架构进行对齐优化。

### 分层架构

```
┌────────────────────────────────────────────────────────────────┐
│  aether-app       应用装配层（启动入口、Spring 配置、Security）   │
├────────────────────────────────────────────────────────────────┤
│  aether-trigger   HTTP 触发层（Controller / Filter / Handler）   │
├────────────────────────────────────────────────────────────────┤
│  aether-api       端口接口 + DTO + 统一响应 Response<T>          │
├────────────────────────────────────────────────────────────────┤
│  aether-domain    领域层（Agent 引擎 / 模型 SPI / 记忆 / 工具等）  │
├────────────────────────────────────────────────────────────────┤
│  aether-infrastructure  适配器层（Pg / Redis / MCP / 凭据池等）   │
├────────────────────────────────────────────────────────────────┤
│  aether-types     基础类型（枚举 / 常量 / 异常）                   │
└────────────────────────────────────────────────────────────────┘
```

模块间依赖关系（依据各模块 `pom.xml`）：

| 模块 | 内部依赖 |
|------|----------|
| `aether-types` | 无（仅外部依赖） |
| `aether-api` | 无（仅外部依赖） |
| `aether-domain` | `aether-types` |
| `aether-infrastructure` | `aether-domain` |
| `aether-trigger` | `aether-api`、`aether-types`、`aether-domain`、`aether-infrastructure` |
| `aether-app` | `aether-trigger`、`aether-infrastructure` |

---

## 主要特性

### 1. 声明式智能体装配（Armory）

- 通过 `ai.agent.config.tables.*` 配置多个智能体「表」，每个表包含 `agent`（id/名称/描述）、`module`（模型 API 与工具）、`agents`（单 Agent 定义）、`agent-workflows`（工作流）与 `runner`（入口）。
- 装配采用 **责任链 + 策略路由** 的策略树：`RootNode → AiApiNode → ChatModelNode → AgentNode → AgentWorkflowNode → CompilerNode`，将 YAML 翻译为可运行的 `AgentGraph`（见 `ArmoryService`）。

### 2. Agent 执行引擎

- 统一 `Agent` 接口 + `BaseAgent` 抽象基类，内置钩子（Hook）与状态（State）管理。
- 标准 **ReAct（Reasoning + Acting）** 循环实现 `ReActAgent`，支持最大轮次 `MAX_TURNS = 100`，以及 `PlanActAgent`。
- `PlanActAgent` 支持中途重规划（连续工具失败或步骤评估不达标时保留已完成步骤、生成替代后续计划）与任务后反思（LLM 自评沉淀 `ExternalNotes`，`BackgroundReviewer` 二次复盘）。
- 基于 **RxJava 3 `Flowable<RuntimeEvent>`** 的事件流式输出（`textDelta`、`toolCall`、`toolResult`、`compactBoundary`、`permissionAsking`、`agentPaused`、`checkpoint`、`turnComplete`、`done`、`error` 等）。
- **多 Agent 工作流**：`sequential` / `parallel` / `loop` / `subagent` / `eventdriven` 编排，由 `GraphExecutor` 统一生命周期执行（trace / MDC / 生命周期钩子 / 异常完成），具体编排下沉为五种 `GraphOrchestrationStrategy` 策略实现，GRAPHFLOW DAG 由 `GraphFlowCoordinator` 协调。

### 3. 模型 Provider SPI 与容错

- 可插拔的 `ModelProvider` SPI，内置 `OpenAIProvider`、`AnthropicProvider`、`DashScopeProvider`，由 `ModelProviderRegistry` 自动发现并按 `modelId` 路由。
- **容错与凭据轮换**：`ResilientChatModelExecutor`（错误分类 + 退避重试 + fallback 链；`call()` 与 `stream()` 共用恢复分支，流式为逐帧透传 + 首帧前 failover、首帧后失败不重放）、`RotatingCredentialPool`、`DefaultModelErrorClassifier`、`RetryBackoff`、`RecoveryDirective` 等。
- **LLM 响应缓存**：`ModelCallCache`（Caffeine L1，TTL 60s / LRU 1000 条 + 可选 Redis L2），key 为 modelName + SHA-256(逐条 role+text) 摘要（2026-09-03 修复 32 位哈希碰撞）。
- **成本追踪与熔断**：`ModelPricingRegistry` + `TokenBudget`，支持 `maxCostUsd` 成本上限熔断。

### 4. 上下文与记忆

- `ContextManager` 提供上下文裁剪、微压缩、自动压缩（AutoCompact）与多步压缩管道（`CompactionPipeline`）。
- 记忆系统（对齐 hermes 记忆段）：`MemoryFacade` / `MemoryStore` / `VectorStore`，支持用户画像、语义召回（权重可配）、`pgvector` 向量存储（`PgvectorVectorStore`）、存量向量回填（`MemoryEmbeddingBackfillRunner`）等；配置项统一挂在 `aether.memory.*` 下。
- 记忆注入通过指令中的 `{memory}` 占位符完成（`ChatService.injectMemory`）。

### 5. 工具体系（Tool / MCP / Skills / Python）

- 统一 `Tool` 抽象与 `ToolExecutor`、`ToolRegistry`。
- MCP 客户端支持 **SSE / Stdio / Local** 三种接入方式（`SSEToolMcpCreateService`、`StdioToolMcpCreateService`、`LocalToolMcpCreateService`）。
- Skills 技能书（`resource/agent/skills` 下）通过 `SkillsToolAdapter` / `ToolSkillsCreateService` 接入。
- 内置 Python 服务调用（`PythonTools` / `PythonServicePort` / `PythonToolCallbackAdapter`）。

### 6. 权限审批（Human-in-the-loop）

- 可插拔的 `PermissionEngine` 与规则集：`DangerousToolRule`、`InjectionGuardRule`、`SensitiveArgMaskRule`、`ToolAllowlistRule`、`SubAgentDenyApprovalRule`。
- 危险工具调用会挂起（`AgentState.AgentStatus.PAUSED`）并向前端发出 `permissionAsking` 事件，等待用户通过 `/api/v1/confirm` 提交批准/拒绝回执后恢复执行。

### 7. 子 Agent 委派与编排

- `SubAgentOrchestrator` 提供子 Agent 委派、异步委派（`AsyncDelegationService` + `PgAsyncDelegationStore`）、租约管理（`LeaseManager`）、结果聚合（`ChildResultAggregator`）、过时委派扫描（`StaleDelegationScanner`）等。
- 提供编排实时控制端点（`OrchestrationController`）：查询活跃子 Agent、中断、暂停/恢复 spawn、委派查询。

### 8. 会话持久化与检查点

- 会话持久化支持 PostgreSQL（`PgSessionRepository`）与 Redis（`RedisSessionRepository`），软删除策略（状态改为 `ARCHIVED`）。
- Agent 状态序列化 / 恢复（`saveState` / `loadState`），缺失必需字段时抛出 `StateRestoreException`「响亮失败」。
- 权限挂起（PAUSED）瞬间即经可选注入的 `SessionRepository` 落盘状态快照（未注入时降级为仅内存），收尾不会把 PAUSED 覆写为 IDLE，支撑确认回执后的恢复执行。
- 检查点（Checkpoint）：每 N 轮自动保存（`checkpointInterval`，默认 5），支持文件快照（`FileCheckpointCollector`）、WAL 结构化日志、Git 影子仓（`GitShadowCheckpointStore`）。

### 9. 安全与审计

- Spring Security 6 + JWT（jjwt 0.12.5，HMAC-SHA256），无状态会话，角色体系 `VIEWER / OPERATOR / ADMIN`（`UserRole`）。
- 密码采用 `BCryptPasswordEncoder(12)`，敏感配置支持 Jasypt 加密（`ENC(...)`）。
- 审计日志（`@Auditable` + `AuditAspect`，异步写入 `t_audit_log`），操作类型见 `AuditAction`。
- SSRF 防护（`SsrfSafeInterceptor`）、安全响应头（`SecurityHeadersFilter`）、限流（`RateLimitFilter`）、MDC trace（`MdcFilter`）。

### 10. 可观测性

- Spring Boot Actuator + Micrometer + Prometheus（`management.endpoints` 暴露 `health,info,metrics,prometheus`）。
- OpenTelemetry（OTLP）集成（`opentelemetry-*`）。
- 结构化 JSON 日志（`logstash-logback-encoder`），输出到 `logs/aether-agent.json`。

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot **3.4.3**（parent） |
| 构建 | Maven（多模块，阿里云镜像源） |
| AI | Spring AI（`spring-ai-openai`、`spring-ai-mcp`、`spring-ai-starter-mcp-client-webflux`）、LangChain4j、Google ADK（依赖管理，部分注释） |
| 响应式 / 流式 | RxJava 3（`3.1.9`）、Spring WebFlux / Reactor Netty |
| 数据库 | PostgreSQL（含 `pgvector`，`pgvector/pgvector:pg16`） |
| 缓存 | Caffeine（LLM 响应 L1）+ Redis（L2 / 限流 / 会话存储可选，`spring-boot-starter-data-redis`） |
| 消息队列 | spring-kafka（审计削峰 + 会话统计聚合，`aether.kafka.enabled` 开关） |
| 集成测试 | Testcontainers 2.0.2（pgvector / Redis / Kafka，`-Pintegration` 独立 profile） |
| 安全 | Spring Security 6、JJWT `0.12.5`、Jasypt `3.0.5`、BCrypt |
| 可观测 | OpenTelemetry `1.41.0`、Micrometer、Prometheus、logstash-logback-encoder |
| 序列化 / 工具 | fastjson `2.0.28`、commons-lang3、Guava、Lombok |
| 检查点 | JGit `6.10.0`（Git 影子仓） |
| 设计模式框架 | xfg-wrench-starter-design-framework `3.0.0`（策略树） |

> 前端项目位于 `docs/dev-ops/aether-frontend-v2/`（Vue 3.5 + Vite 5 + TypeScript 5.6 + Pinia + vue-flow + ECharts + Mermaid + TailwindCSS），为独立工程，本文档聚焦后端。

---

## 目录结构说明

```
aether/
├── pom.xml                         # 父 POM：模块聚合、依赖版本管理、Maven profiles
├── aether-types/                   # 基础类型层
│   └── src/main/java/cn/zcj/aether/types/
│       ├── common/Constants.java
│       ├── enums/                  # ResponseCode / UserRole / AuditAction
│       └── exception/              # AppException / StateRestoreException
├── aether-api/                     # 端口接口 + DTO 层
│   └── src/main/java/cn/zcj/aether/api/
│       ├── IAgentService.java      # 智能体服务端口接口
│       ├── dto/                    # Auth/Chat/Session/Model/Dashboard 等 DTO
│       └── response/Response.java  # 统一响应封装 code/info/data
├── aether-domain/                  # 领域层（核心）
│   └── src/main/java/cn/zcj/aether/domain/
│       ├── agent/model/            # 聚合/实体/图(AgentGraph)/值对象
│       └── agent/service/
│           ├── agent/              # core/impl(ReAct,PlanAct)/hook/middleware/
│           │                       #   permission/intervention/observability
│           ├── armory/             # 装配：ArmoryService + 策略树 node + MCP + skills
│           ├── chat/               # ChatService
│           ├── compiler/           # AgentGraphCompiler / InstructionResolver
│           ├── context/            # ContextManager / TokenBudget / compaction
│           ├── curation/           # 结果策展
│           ├── event/              # AgentEventPublisher
│           ├── executor/           # GraphExecutor + orchestration/（五种编排策略）
│           ├── memory/             # MemoryFacade / VectorStore / core
│           ├── model/              # ModelProvider SPI + failover
│           ├── notes/              # ExternalNotes（EXPERIMENTAL，见 docs/notes-experimental.md）
│           ├── retrieval/          # CodeExplorer / IdentifierRegistry / rag（三级检索管道）
│           ├── runtime/            # AgentRuntime / ModelInvoker / RuntimeEvent
│           ├── security/           # JwtService
│           ├── session/            # SessionEntity / SessionRepository
│           ├── subagent/           # 子 Agent 委派编排
│           └── tool/               # Tool / MCP 适配 / python / validation
├── aether-infrastructure/          # 基础设施适配层
│   └── src/main/java/cn/zcj/aether/
│       ├── infrastructure/
│       │   ├── checkpoint/         # GitShadowCheckpointStore
│       │   ├── classifier/         # DefaultModelErrorClassifier
│       │   ├── credential/         # RotatingCredentialPool
│       │   ├── deleg/              # PgAsyncDelegationStore / FileDelegationLiveLog
│       │   ├── persistence/        # UserRepository / RefreshTokenRepository / AuditLogRepository
│       │   ├── python/             # PythonServiceClient
│       │   └── security/           # SsrfSafeInterceptor
│       └── repository/             # PgSessionRepository / RedisSessionRepository / PgvectorVectorStore
├── aether-trigger/                 # HTTP 触发层
│   └── src/main/java/cn/zcj/aether/trigger/http/
│       ├── AuthController.java
│       ├── AgentServiceController.java
│       ├── OrchestrationController.java
│       ├── McpRefreshController.java
│       ├── filter/                 # JwtAuthFilter / MdcFilter / RateLimitFilter / SecurityHeadersFilter
│       └── handler/                # JwtAccessDeniedHandler / JwtAuthEntryPoint / ValidationExceptionHandler
├── aether-app/                     # 应用装配层（可执行）
│   └── src/main/
│       ├── java/cn/zcj/aether/
│       │   ├── Application.java    # 启动入口
│       │   ├── aspect/AuditAspect.java
│       │   ├── config/             # Security/Cors/ThreadPool/Async/AiAgentAutoConfig/DataInitializer/Jasypt/...
│       │   └── security/UserDetailsServiceImpl.java
│       └── resources/
│           ├── application.yml     # 主配置（默认激活 dev）
│           ├── application-dev.yml / application-prod.yml / application-test.yml / application-bench.yml
│           ├── schema.sql          # 数据库初始化（spring.sql.init.mode=always）
│           ├── logback-spring.xml
│           └── agent/              # 智能体 YAML 配置 + skills + prompts
├── docker/                         # Dockerfile（多阶段构建）+ compose 栈与 prometheus/grafana/
│                                   #   alertmanager/nginx/alert-echo 配置（secure / fullstack / bench / scale）
├── benchmark/                      # 压测设施（k6 脚本 / mock-llm / TTFT 探针 / run.sh / generate_report.py）
├── scripts/                        # coverage-summary.py / eval-ab-demo.sh / scaling-benchmark.py
├── data/sql/                       # 迁移脚本（V1 基线 → V5 消息/记忆）
├── .github/workflows/ci.yml        # CI（build-test + Qodana + integration job）
├── QUICKSTART.md                   # 5 条命令快速开始
└── docs/                           # 文档（含架构方案与前端工程）
```

---

## 安装步骤

### 环境要求

- **JDK 17**
- **Maven 3.6+**
- **PostgreSQL 16 + pgvector**（可选但推荐，用于会话持久化与向量记忆）

### 1. 克隆与构建

```bash
git clone <repository-url> aether
cd aether

# 编译并跳过测试（父 POM 未默认跳过测试，需显式 -DskipTests）
mvn clean package -DskipTests
```

打包产物为 `aether-app/target/aether-app.jar`。

> 注意：`mvn -B verify` 会连带执行 `aether-infrastructure` 的 Testcontainers 集成测试（`*IT.java`，需 Docker 运行环境）；无 Docker 本地验证请加 `-DskipITs`。JDK 17 可直接运行；本机为 JDK 21 时需追加 `-DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`（Mockito inline 自附加）。

### 2. 启动数据库（可选）

项目内置 Docker Compose 安全部署配置：

```bash
cd docker
JASYPT_MASTER_PASSWORD=your-master-password \
DB_PASSWORD=your-db-password \
JWT_SECRET=your-jwt-secret \
docker compose -f docker-compose-secure.yml up -d
```

> 该配置同时启动 `aether:latest` 应用与 `pgvector/pgvector:pg16` 数据库；若不使用 Docker，需本地准备 PostgreSQL 数据库 `aether`（开发环境默认连接 `jdbc:postgresql://127.0.0.1:5432/aether`，用户名 `postgres`，密码通过 `${DB_PASSWORD:123456}` 指定）。

### 3. 运行

```bash
java -jar aether-app/target/aether-app.jar \
  --spring.profiles.active=dev \
  --jasypt.encryptor.password=<master-password>
```

或在 IDE 中直接运行 `cn.zcj.aether.Application`。启动成功后：

- 服务地址：`http://localhost:8091`
- 健康检查：`http://localhost:8091/actuator/health`
- Prometheus 指标：`http://localhost:8091/actuator/prometheus`

首次启动时，`DataInitializer` 会自动创建默认管理员账户 **`admin / admin`**（生产环境务必立即修改密码）。

---

## 配置说明

配置采用 Spring Boot 多 profile 机制：`application.yml` 声明主配置并默认激活 `dev`，随后按需加载 `application-dev.yml` / `application-prod.yml` / `application-test.yml`，其中 dev 配置会 `spring.config.import` 导入 `classpath:agent/agents.yml`。

### 核心配置项（`aether.*`）

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `ai.agent.config.enabled` | `true` | Agent 自动装配总开关（O17；false 时启动不装配任何 Agent） |
| `aether.ssrf.allow-private-urls` | `false` | SSRF 防护：是否允许访问内网 URL（生产必须为 false，dev 覆盖为 true） |
| `aether.security.jwt.secret` | `${JWT_SECRET:...}` | JWT 签名密钥（至少 32 字符） |
| `aether.security.jwt.access-token-expiration` | `900000` | Access Token 有效期（ms，15 分钟） |
| `aether.security.jwt.refresh-token-expiration` | `604800000` | Refresh Token 有效期（ms，7 天） |
| `aether.memory.enabled` | `true` | 记忆系统总开关 |
| `aether.memory.user-profile-enabled` | `true` | 用户画像记忆开关 |
| `aether.memory.memory-char-limit` | `2200` | 注入记忆上下文预算 |
| `aether.memory.recall.max-results` | `10` | 语义召回 Top-K |
| `aether.memory.embedding.base-url` | `https://open.bigmodel.cn` | 向量嵌入 API 地址 |
| `aether.memory.pgvector.enabled` | dev 为 `true` | 是否启用 pgvector 向量存储 |
| `aether.session.persistence` | dev 为 `true` | 会话持久化开关 |
| `aether.delegation.persistence` | dev 为 `true` | 异步委派持久化开关 |
| `aether.delegation.stale-timeout` | `PT10M` | 挂起子 Agent 判定窗口 |
| `aether.delegation.stale-scan-interval-ms` | `60000` | 过时委派扫描间隔 |
| `aether.subagent.terminal-retention` | `100` | 子 Agent 终态保留上限 |
| `aether.graph.trace.persistence` | dev 为 `true` | 图级 trace 是否异步落盘 |
| `aether.cors.allowed-origins` | 未配置=拒绝所有跨域 | CORS 白名单（逗号分隔；dev 显式放行前端 `http://localhost:5173`） |

### P1/P2 新增配置速查

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `aether.security.rate-limit.mode` | `memory` | 限流实现：`memory` / `redis`（Redis 令牌桶，不可用自动降级并打点） |
| `aether.kafka.enabled` | `false` | Kafka 双链路总开关（审计削峰 + 会话统计聚合，超时降级直写） |
| `aether.dashboard.stats-source` | `live` | 仪表盘统计来源：`live` 实时查询 / `kafka` 读聚合表 |
| `aether.rag.enabled` | `false` | RAG 三级检索总开关（`aether.rag.rewrite` / `hybrid` / `rerank` 逐级开关降级） |
| `aether.cache.llm.redis-enabled` | `false` | LLM 响应缓存 Redis L2（缺省仅 Caffeine L1，Redis 异常自动降级） |
| `aether.session.store` | `postgres` | 会话存储：`postgres` / `redis` / `none` |
| `aether.model.invoker.true-streaming` | `true` | 真流式模型调用开关（压测 A/B 维度） |
| `aether.memory.decay.*` / `write-gate.*` / `conflict.*` | 见 yml | 记忆生命周期：遗忘曲线 / 写入重要性门槛 / 冲突合并策略 |
| `aether.graph.background-review.*` | 关闭 | BackgroundReviewer 后台复盘开关与超时参数 |

### 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `DB_PASSWORD` | `123456`（dev/bench） | PostgreSQL 密码（prod 无默认，必须显式提供） |
| `JWT_SECRET` | dev/bench 提供开发默认 | JWT 签名密钥（≥32 字符；主配置无默认，prod 未设即启动失败） |
| `ZHIPU_API_KEY` | dev 提供开发回退 | 记忆向量嵌入（智谱）API Key（主配置无默认，未设置即启动失败；bench 关闭记忆不受影响） |
| `JASYPT_MASTER_PASSWORD` | 无 | Jasypt 配置加密主密钥（`docker/.env` 与 docker-compose-secure.yml 使用） |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 连接（限流 redis 模式 / 会话 redis 存储时使用） |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker 地址（`aether.kafka.enabled=true` 时生效） |
| `AETHER_LLM_CACHE_REDIS_ENABLED` | `false` | LLM 缓存 Redis L2 开关（等价 `aether.cache.llm.redis-enabled`） |
| `AETHER_MODEL_INVOKER_TRUE_STREAMING` | `true` | 真流式开关（bench 压测 A/B 重启切换用） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP 上报端点（OTel Java Agent 读取） |
| `MANAGEMENT_HEALTH_REDIS_ENABLED` | `false` | 未部署 Redis 时保持 health UP；redis 模式部署设 `true` |

### 数据源与线程池（`application-dev.yml`）

```yaml
spring:
  datasource:
    username: postgres
    password: ${DB_PASSWORD:123456}
    url: jdbc:postgresql://127.0.0.1:5432/aether
    driver-class-name: org.postgresql.Driver
    hikari:
      pool-name: Aether_HikariCP
      maximum-pool-size: 25
  sql:
    init:
      mode: always

thread:
  pool:
    executor:
      config:
        core-pool-size: 20
        max-pool-size: 50
        keep-alive-time: 5000
        block-queue-size: 5000
        policy: CallerRunsPolicy
```

线程池拒绝策略支持 `AbortPolicy` / `DiscardPolicy` / `DiscardOldestPolicy` / `CallerRunsPolicy`（见 `ThreadPoolConfig`）。审计日志使用独立异步线程池 `auditExecutor`（core=2 / max=5 / queue=100，`AsyncConfig`）。

### 数据库初始化

`schema.sql` 通过 `spring.sql.init.mode=always` 自动执行，创建以下表：

- `t_user` / `t_refresh_token` —— 用户与刷新令牌
- `t_audit_log` —— 审计日志
- `t_async_delegation` —— 异步委派
- `aether_session` —— Agent 会话状态
- `dashboard_stats` —— 会话统计聚合（Kafka 消费写入）
- `aether_processed_event` —— 事件幂等去重

> `data/sql/` 提供 Flyway 风格完整迁移链：`V1__baseline.sql`（幂等基线，含 pgvector / HNSW）→ `V5__p1_messaging_and_memory.sql`。

### 智能体 YAML 配置结构

智能体配置位于 `aether-app/src/main/resources/agent/*.yml`，顶层结构为 `ai.agent.config.tables.<表ID>`：

```yaml
ai:
  agent:
    config:
      tables:
        Agent01:                       # 表 ID（agentId）
          app-name: Agent01
          agent:
            agent-id: "1"
            agent-name: 旅游规划智能体
            agent-desc: 智能体描述
          module:
            ai-api:
              base-url: https://api.deepseek.com
              api-key: sk-xxx
              completions-path: v1/chat/completions
              embeddings-path: v1/embeddings
            chat-model:
              model: deepseek-v4-flash
              tool-mcp-list:           # MCP 工具（sse / local / stdio）
                - sse:
                    name: baidu-search
                    base-uri: http://appbuilder.baidu.com/v2/ai_search/mcp/
                    sse-endpoint: sse?api_key=xxx
                    request-timeout: 500000
              tool-skills-list:        # 技能书
                - type: resource
                  path: agent/skills
          agents:                      # 单 Agent 定义
            - name: Agent01
              description: 描述
              instruction: |           # 支持内联 / classpath: / file: 引用
                你的系统提示词
          agent-workflows:             # 工作流（可选）
            - type: parallel           # loop / parallel / sequential
              name: Parallel01
              sub-agents: [A, B, C]
          runner:
            agent-name: Agent01        # 入口（单 Agent 或工作流名）
```

`instruction` 支持三种写法（见 `demo.yml` 注释）：内联多行文本、`classpath:agent/prompts/xx.md`、`file:/absolute/path.md`，并支持 `{outputKey}`（跨 Agent 输出引用）与 `{memory}`（记忆注入）占位符。

---

## 使用说明

### 认证流程

1. 使用默认管理员或注册新用户登录，获取 JWT：

   ```bash
   curl -X POST http://localhost:8091/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"admin"}'
   ```

2. 后续请求携带 `Authorization: Bearer <accessToken>`。Access Token 过期后使用 `/api/v1/auth/refresh` 轮转刷新。

### 对话流程

1. 查询可用智能体：`GET /api/v1/query_ai_agent_config_list`
2. 创建会话：`POST /api/v1/create_session`（获取 `sessionId`）
3. 发起同步对话：`POST /api/v1/chat`；或流式对话：`POST /api/v1/chat_stream`（SSE）

---

## API 接口

统一响应结构 `Response<T>`：`{ "code": "0000", "info": "成功", "data": ... }`（见 `Response.java` 与 `ResponseCode`）。除认证端点外均需 JWT 认证（`SecurityConfig` 中仅 `/api/v1/auth/**`、`/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/health` 放行）。

### 认证（`/api/v1/auth`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 用户注册（用户名 3-64 字符，密码 8-128 字符） |
| POST | `/api/v1/auth/login` | 用户登录 |
| POST | `/api/v1/auth/refresh` | 刷新 Access Token（轮转 Refresh Token） |

### 智能体服务（`/api/v1/`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/query_ai_agent_config_list` | 查询智能体配置列表 |
| POST / GET | `/api/v1/create_session` | 创建会话 |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat_stream` | 流式对话（SSE） |
| POST | `/api/v1/confirm` | 提交工具调用确认回执（SSE） |
| GET | `/api/v1/list_sessions` | 查询会话列表（参数 `agentId`、`userId`） |
| DELETE | `/api/v1/delete_session` | 删除会话（软删除，参数 `sessionId`） |
| GET | `/api/v1/session_messages` | 查询会话消息（参数 `sessionId`） |
| GET | `/api/v1/models` | 查询已配置模型列表 |
| GET | `/api/v1/dashboard_stats` | 查询仪表盘统计 |

### 编排控制（`/api/orchestration/`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/orchestration/active-subagents` | 查询活跃子 Agent 列表 |
| POST | `/api/orchestration/subagents/{id}/interrupt` | 中断指定子 Agent |
| POST | `/api/orchestration/spawn/pause` | 暂停/恢复新 spawn（body `{"paused": bool}`） |
| GET | `/api/orchestration/delegations` | 委派查询（可选 `sessionId`） |

### MCP 工具（`/api/mcp`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mcp/refresh` | 手动刷新 MCP 工具（body 可选 `{"serverId":"..."}`，缺省刷新全部） |

---

## 示例代码

### 流式对话（SSE）请求示例

```bash
curl -N -X POST http://localhost:8091/api/v1/chat_stream \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{
        "agentId": "1",
        "userId": "1",
        "sessionId": "",
        "message": "帮我规划一个杭州出发的5天避暑行程"
      }'
```

SSE 事件示例（`data:` 前缀，见 `AgentServiceController.serializeEvent`）：

```
data: {"type":"turnStarted","turnCount":1}
data: {"type":"textDelta","text":"好的，..."}
data: {"type":"toolCall","toolCallId":"call_001","toolName":"baidu-search","toolInput":{...}}
data: {"type":"toolResult","toolCallId":"call_001","toolName":"baidu-search","toolOutput":"...","toolError":false}
data: {"type":"done"}
```

### 工具调用确认回执示例

```json
{
  "agentId": "1",
  "userId": "1",
  "sessionId": "abc123",
  "confirmResults": [
    { "toolCallId": "call_001", "approved": true },
    { "toolCallId": "call_002", "approved": false }
  ]
}
```

### 多 Agent 工作流配置示例（代码审查流水线）

取自 `test-agent.yml`（`sequential` 串行组合 `CodeWriterAgent → CodeReviewerAgent → CodeRefactorerAgent`），通过 `{generated_code}` / `{review_comments}` 占位符在 Agent 间传递输出：

```yaml
agent-workflows:
  - type: sequential
    name: CodePipelineAgent
    description: Executes a sequence of code writing, reviewing, and refactoring.
    sub-agents:
      - CodeWriterAgent
      - CodeReviewerAgent
      - CodeRefactorerAgent
runner:
  agent-name: CodePipelineAgent
```

---

## 许可证

本项目在父 `pom.xml` 中声明采用 **Apache License, Version 2.0**（`<licenses>` 段，URL：https://www.apache.org/licenses/LICENSE-2.0）。仓库当前未包含独立的 `LICENSE` 文件。

<a id="engineering-cicd-benchmark-eval"></a>
## 工程化闭环（P0 路线图交付）

```bash
mvn -B verify                                        # 构建 + 688 测试 + JaCoCo（aether-app/target/site/jacoco-aggregate/；IT 需 Docker，无 Docker 加 -DskipITs）
python scripts/coverage-summary.py --write docs/coverage-baseline.md   # 覆盖率汇总/盲区
docker compose -f docker/docker-compose-bench.yml up -d --build        # 压测栈（PG + mock-llm + aether bench）
cd benchmark && ./run.sh                             # 一键压测 → docs/benchmark-report.md
mvn -B -pl aether-app -am test -Dtest=EvalRunnerTest # Eval 50 例 → aether-app/target/eval-report.json
bash scripts/eval-ab-demo.sh                         # A/B 回归演示 → docs/eval-report.md
python scripts/scaling-benchmark.py --mode all --manage-stack --duration 60   # 双实例线性度压测 → docs/scaling-benchmark-report.md
```

- **CI**：`.github/workflows/ci.yml` —— push/PR 触发 build-test（含 JaCoCo 报告 artifact）与 Qodana 双 job
- **压测**：`benchmark/run.sh` —— 冒烟 → 并发容量（10/30/50 VU × 10 轮）→ 混沌容错（429×3→500×2→成功）→ 真流式 A/B（true/false 重启切换）→ 缓存收益 → 自动生成 `docs/benchmark-report.md`（第 7 节含 mermaid 并发曲线图）；TTFT 由独立 SSE 探针测量
- **Eval**：`aether-app/src/test/resources/eval/cases.jsonl` 四类 50 例；确定性模式（mock 决策核，CI 可跑）+ 真实模式（`AETHER_EVAL_MODE=real` + `DEEPSEEK_API_KEY`）；A/B 演示注入"工具描述回归"并捕获通过率下降
- **指标**：`aether.cache.llm.hitrate` / `aether.compaction.tokens.pre|post|count` / `aether.model.recovery.branch{branch=}` / `aether.model.fallback.switches` / `aether.agent.plan.replans|completions|reflections.total`（`/actuator/prometheus`）
- **P2**：水平扩展（[设计文档](docs/horizontal-scaling-design.md) + 双实例压测 1.78x PASS）、编排策略化重构、PlanActAgent 重规划/反思、日志排障手册——详见 [docs/p2-roadmap-delivery.md](docs/p2-roadmap-delivery.md)

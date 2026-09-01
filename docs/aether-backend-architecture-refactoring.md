# Aether 后端架构重构方案 —— 面向「扩展性」与「稳定性」的可靠架构设计

> 文档类型：架构设计（只读分析，不含代码改动）
> 适用基线：`D:\code\Agents-framework\aether`（Java 17 + Spring Boot 3.4.3 + Spring AI，六模块 DDD 六边形单体）
> 制定角色：Backend Architect
> 制定日期：2026-08-13

---

## 0. 设计基线约定

### 0.1 单一参考框架来源（避免模糊）

本方案**唯一溯源**至 Chris Richardson《Microservices Patterns》（Manning, 2018），并落地其四类核心模式：

| 参考模式 | 书中出处 | 本方案用途 |
|---|---|---|
| **Modular Monolith First（模块化单体优先）** | Ch.2 / "Pattern: Modular Monolith" | 当前阶段**不拆微服务**，先把单体内部边界理清，避免过早分布化（YAGNI） |
| **Scale Cube（扩展立方体）** | Ch.2 / "Scaling Up with the Scale Cube" | 明确「先纵向扩容 + 进程内并发治理」，水平扩容（X 轴多副本）的前提是先外置状态 |
| **Resilience Patterns：Bulkhead / Circuit Breaker / Rate Limiter** | Ch.3 / "Patterns for reliability" | 解决线程爆炸、下游雪崩、上游打爆三类稳定性问题 |
| **Saga / Transactional Outbox（持久化作业）** | Ch.4 / "Transactional outbox" | 执行任务与状态落库解耦，崩溃可恢复 |

> 模块边界规范采用 **六边形架构（Ports & Adapters，Alistair Cockburn）**——它是 Richardson 书中推荐的模块化手段；**实现库为 Resilience4j**（对应 Bulkhead/CircuitBreaker/RateLimiter 三模式）。所有结论均可回溯到上述框架，不引入第二套互相矛盾的方法论。

### 0.2 范围与约束

- **只给方案、不修改代码**（用户约束 + CLAUDE.md 简单优先）。
- 现状证据全部来自当前工作区源码，标注 `文件:行号`，可逐条复核。
- 优先级统一使用 **P0（致命）/ P1（重要）/ P2（增强）** 三级。

---

## 1. 执行摘要（Executive Summary）

**核心判断**：Aether 已具备干净的 DDD 六边形**领域层**（`aether-domain` 仅依赖 `aether-types`，无反向依赖），Agent 引擎、容错、记忆、权限体系设计成熟。但其**稳定性与扩展性短板集中在「进程内资源治理」「下游韧性」「状态持久化」「模块边界」**四个横切面，且均为"不出事则已、一出事整进程崩"的系统性风险，非单点 bug。

**总体策略**（遵循 Modular Monolith First，尊重 YAGNI）：

1. **P0 — 止血**：消除"整进程崩溃"类风险（无界线程池、重试复合放大、会话默认无持久化）。
2. **P1 — 加固**：让单 Agent 失败不拖垮整体、给所有下游边界加韧性、为水平扩展铺路（状态外置）。
3. **P2 — 增强**：理清模块边界、让装配链可扩展、定义"执行服务可独立提取"的演进边界（**只定义接口，不实现**，避免过早分布化）。

**交付分级**：P0 ×3、P1 ×4、P2 ×4，合计 11 项。其中 P0 全部为"小改动、大收益"，建议优先排期。

---

## 2. 现状实证诊断（含 file:line 证据）

| # | 类别 | 现象（实证） | 证据位置 | 影响 |
|---|---|---|---|---|
| F1 | 并发治理 | `GraphExecutor` 使用**无界缓存线程池** `Executors.newCachedThreadPool()`，并发图节点会无限创建线程 | `aether-domain/.../executor/GraphExecutor.java:85` | 高（OOM / 线程耗尽 → 整进程崩） |
| F2 | 并发治理 | GRAPHFLOW 路径 `new Thread(() -> {...})` 裸起线程，不受任何池管控 | `aether-domain/.../executor/GraphExecutor.java:727` | 高（同 F1） |
| F3 | 重试复合 | `ModelInvoker` 自带重试循环 `MAX_RETRIES=3` + `Thread.sleep` 退避，而 `ChatModelNode` 又把原始模型包成 `ResilientChatModelExecutor`（自带 `maxAttempts=3`）——**两套重试嵌套**，单次挂起退避被放大到 3×3 | `aether-domain/.../runtime/ModelInvoker.java:40,227,231`；`aether-domain/.../armory/node/ChatModelNode.java:514` | 高（超时放大、下游压力倍增） |
| F4 | 状态持久化 | `PgSessionRepository` 与 `RedisSessionRepository` 均为 `@ConditionalOnProperty(persistence=true, matchIfMissing=false)`，**默认两个 Bean 都不存在**；`ChatService` 用 `@Autowired(required=false)` 静默降级 → **默认会话不落库** | `aether-infrastructure/.../PgSessionRepository.java:28`；`RedisSessionRepository.java:25` | 高（重启即丢会话，违反"状态完整恢复"设计目标） |
| F5 | 启动韧性 | `AiAgentAutoConfig.onApplicationEvent` 捕获异常后 `throw new RuntimeException(e)`，**任一 Agent YAML 错误即阻断整个应用启动** | `aether-app/.../config/AiAgentAutoConfig.java:29-38` | 高（单点配置错误 → 全站不可用） |
| F6 | 韧性中间件 | 全仓库**无 resilience4j / 无 @Retryable / 无 circuitbreaker**；对 LLM Provider、MCP Server、Python fs 服务均无熔断/舱壁/限流 | 全仓库 grep 无结果 | 高（下游抖动直接雪崩传导） |
| F7 | 进程内单例状态 | `AgentRegistry`（`ConcurrentHashMap`，`:19`）、`DefaultMcpToolRegistry`（3 个 `ConcurrentHashMap`，`:22-26`）、`SubAgentOrchestrator` `Semaphore(5)`（`:34`）、`RotatingCredentialPool`、各类 `ToolRegistry`/`ModelCallCache` 均为 `@Component` 单例且**实例本地** | `aether-domain/.../armory/AgentRegistry.java:17-19`；`.../matter/mcp/registry/DefaultMcpToolRegistry.java:19-26`；`.../subagent/SubAgentOrchestrator.java:34` | 高（多副本下状态不一致，无法水平扩展） |
| F8 | 执行器单一化 | 仅一个 `@Async` 池（core20/max200/queue5000/**AbortPolicy**），Agent 单 turn 还 `.block(2min)` | `aether-app/.../config/ThreadPoolConfig.java:41-47`；`ReActAgent.java:257` | 中（AbortPolicy 静默丢任务；长阻塞占满池） |
| F9 | 检查点/委派本地化 | `GitShadowCheckpointStore` 基于**本地文件系统**；`AsyncDelegationService` 仅 `@PostConstruct` 一次性恢复，`StaleDelegationScanner` 默认 `interval≤0` 禁用 → 挂起子 Agent 长期占租约 | `aether-infrastructure/.../GitShadowCheckpointStore.java`；`AsyncDelegationService.java:27-76` | 中（多实例/重启下残留、不一致） |
| F10 | 模块边界 | `aether-trigger` **同时依赖 `aether-domain` 与 `aether-infrastructure`**，入口适配层直接耦合基础设施适配器，违反六边形 | `aether-trigger/pom.xml:45,49` | 中（耦合固化、难以独立测试/替换基础设施） |
| F11 | 装配链硬编码 | `RootNode→AiApiNode→ChatModelNode→AgentNode→AgentWorkflowNode→CompilerNode` 全部 `@Service` 固定链，新增装配维度需改链 | `aether-domain/.../armory/node/*.java` | 低（扩展性摩擦） |

---

## 3. 目标架构蓝图

### 3.1 逻辑视图（模块化单体 + 明确限界上下文 + 端口适配器）

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Aether 应用进程（单体）                          │
│                                                                        │
│  ┌─────────────┐    ┌──────────────────────────────────────────────┐  │
│  │  trigger    │    │  api（端口接口 + DTO + Response<T>）           │  │
│  │ (HTTP 适配) │───▶│      ↓ 依赖（仅端口，不依赖实现）              │  │
│  │ Controller  │    │  domain（限界上下文集）                         │  │
│  │ MdcFilter   │    │   ├─ agent(引擎) │ model(Provider SPI)         │  │
│  └─────────────┘    │   ├─ memory │ session │ tool │ executor │ ...  │  │
│        │            │      ↓ 定义端口（Repository/SPI）               │  │
│        │            └──────────────────────────────────────────────┘  │
│        │                          ↑ 实现                                │
│  ┌─────┴───────────────────────────────────────────────────────────┐ │
│  │  infrastructure（适配器：Pg/Mcp/Credential/Checkpoint 等实现）    │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  横切韧性层（Resilience4j，包裹所有「出站边界」）：                    │
│   LLM Provider 出站 ── CB + RL + Bulkhead                            │
│   MCP Server 出站  ── CB + RL + Bulkhead                            │
│   下游 HTTP(fs服务)  ── CB + Timeout + RL                            │
└──────────────────────────────────────────────────────────────────────┘
            │                              │
    ┌───────┴────────┐            ┌────────┴─────────┐
    │ 共享基础设施    │            │ 共享状态（外置）  │
    │ PostgreSQL+pg  │            │ Redis（租约/注册  │
    │ vector / Redis │            │ /缓存/信号）      │
    └────────────────┘            └───────────────────┘
```

### 3.2 部署视图（演进）

- **阶段 A（本方案目标，单体）**：单进程 + PostgreSQL(+pgvector) + Redis；进程内并发受控；下游全韧性包裹。
- **阶段 B（按需，非本次实现）**：当单实例 CPU/吞吐触顶，按 Scale Cube X 轴**多副本**——前提是 F7/F9 状态已外置到 Redis/共享存储。
- **阶段 C（远期，可选）**：将 `AgentExecutionPort` 实现抽为独立「执行服务」，trigger 经消息队列提交作业——**仅定义边界，不实现**。

### 3.3 五项核心原则

1. **出站边界必包韧性**（Bulkhead/CB/RL）—— Richardson 可靠性模式。
2. **重试单一所有权**——只允许一处（ResilientChatModelExecutor）决定重试/熔断/回退。
3. **可变状态外置**——进程内只保留"编译期/只读"定义（Agent 定义），"运行期可变"状态（会话、租约、凭据池、MCP 连接注册）外置或明确标注单实例约束。
4. **失败隔离**——单 Agent / 单请求失败不得传导为进程级失败。
5. **默认安全**——缺配置时选择"有持久化、有降级"而非"静默无持久化"。

---

## 4. 重构映射表（现状 → 目标 → 动作）

| 关注点 | 现状（file:line） | 目标状态 | 参考模式 | 优先级 |
|---|---|---|---|---|
| 图执行线程池 | `GraphExecutor.java:85` 无界 cached 池；`:727` 裸线程 | 受 Spring 托管的**有界执行器**（命名工厂 + 有界队列 + CallerRuns/降级拒绝） | Bulkhead | P0-1 |
| 重试所有权 | `ModelInvoker.java:227` + `ChatModelNode.java:514` 双重试 | `ResilientChatModelExecutor` 为**唯一**重试/熔断/回退所有者；`ModelInvoker` 退化为单次调用+超时 | 重试收口（Richardson 可靠性） | P0-2 |
| 会话持久化 | `PgSessionRepository.java:28`/`RedisSessionRepository.java:25` 默认无 Bean | 默认启用 PostgreSQL 持久化（`matchIfMissing=true` 或显式默认），缺失配置给 WARN 而非静默 | Transactional Outbox 思想 | P0-3 |
| 启动装配 | `AiAgentAutoConfig.java:29-38` 全崩 | 单 Agent 装配失败隔离（跳过+记录+健康标记 degraded），其余 Agent 仍可服务 | 失败隔离 | P1-1 |
| 下游韧性 | 全仓库无 resilience4j | LLM/MCP/下游 HTTP 出站加 CB+RL+Bulkhead | Circuit Breaker/Rate Limiter/Bulkhead | P1-2 |
| 进程内状态 | `AgentRegistry.java:19` 等 7+ 处实例本地 | 标注单实例约束；中期外置（Redis）计划 | Scale Cube / 状态外置 | P1-3 |
| 检查点/委派 | `GitShadowCheckpointStore.java` 本地；`AsyncDelegationService:27-76` 一次性恢复 | 检查点改共享后端；stale 扫描默认开启合理间隔 | Saga/持久化作业 | P1-4 |
| 模块依赖 | `aether-trigger/pom.xml:49` 依赖 infra | trigger 仅依赖 api+domain 端口，infra 经自动装配注入 | 六边形（Ports & Adapters） | P2-1 |
| 装配链 | `armory/node/*.java` 硬编码 `@Service` 链 | 装配步骤改为**有序可注册 Bean（@Order）**，新增维度只加 Bean | 开放封闭 / 策略可插拔 | P2-2 |
| 执行服务边界 | 执行逻辑内嵌 trigger→domain | 定义 `AgentExecutionPort`（入：执行请求 / 出：事件流），当前单体内实现 | 演进边界（非实现） | P2-3 |
| 边缘网关 | 无 | （可选）trigger 前置网关统一鉴权/限流/CORS/trace | API Gateway 模式 | P2-4 |

---

## 5. 分级实施路线

### P0 — 致命稳定性（立即做，小改动大收益）

#### P0-1 消除无界线程池爆炸
- **问题**：`GraphExecutor.java:85` 的 `newCachedThreadPool()` 与 `:727` 的 `new Thread()` 在并发图流/GRAPHFLOW 下会无限创建线程，OOM 或耗尽后**整进程崩溃**。
- **目标设计**：
  - 引入一个 Spring 托管的 `GraphExecutorPool`（`ThreadPoolTaskExecutor`：core≤CPU、max 有界、队列有界、命名线程工厂、`CallerRunsPolicy` 或显式降级拒绝）。
  - `:727` 的裸线程并入同一池；禁止在请求路径上 `new Thread`/`newCachedThreadPool`。
  - 复用 `ThreadPoolConfig` 的已有策略开关，新增 `graph` 专用池配置段。
- **参考模式**：Bulkhead（舱壁隔离）。
- **验收**：并发 50 路 GRAPHFLOW 压测下，进程线程数收敛在配置上限内，无 OOM；超限请求被优雅拒绝（含指标）。
- **影响面**：仅 `GraphExecutor` 构造与调用处；需补并发测试。

#### P0-2 重试所有权单一收口
- **问题**：`ModelInvoker.java:227` 的 `for(attempt<=MAX_RETRIES)` + `Thread.sleep` 与 `ChatModelNode.java:514` 包装的 `ResilientChatModelExecutor`（自带 `maxAttempts=3`、分类容错、fallback 链）**两套重试嵌套**。README 自身已写明"重试所有权收归 ResilientChatModelExecutor"但 `ModelInvoker` 仍保留旧循环，单次挂起退避被放大 3×3 倍，且对下游造成 9 倍重试压力。
- **目标设计**：
  - **唯一所有者** = `ResilientChatModelExecutor`（已实现分类容错/fallback/退避/jitter）。
  - `ModelInvoker` 退化为「单次调用 + 双通道超时（RestClient 30s/120s、WebClient 同）」，**移除其重试循环**；其职责仅剩调用与超时。
  - 退避上限由 `ResilientChatModelExecutor`（base 2s / max 30s / jitter 0.5 / maxAttempts 3）统一定义，避免复合。
- **参考模式**：重试收口（Richardson 可靠性章节）。
- **验收**：注入持续 5xx 的 Provider，观测到退避序列严格符合 `ResilientChatModelExecutor` 配置（≤3 次、2s→30s、带 jitter），且**不再出现 9 倍重试**；`ModelInvokerTest` 调整断言。
- **影响面**：`ModelInvoker`、`ReActAgent` 调用点、`ChatModelNode`；属高危改动，需保留回滚开关。

#### P0-3 会话/状态持久化默认开启
- **问题**：`PgSessionRepository.java:28` 与 `RedisSessionRepository.java:25` 均为 `matchIfMissing=false` 且需 `persistence=true`，默认**两个 Bean 都不存在**，`ChatService` 用 `@Autowired(required=false)` 静默降级 → 默认会话不落库，重启即丢，与设计目标"状态完整恢复"矛盾。
- **目标设计**：
  - 将 PostgreSQL 实现改为**默认启用**（`matchIfMissing=true`，或显式设默认值），Redis 作为可选覆盖。
  - 配置缺失且无任何持久化 Bean 时，启动时打印 **WARN + 暴露 health degraded**，而非静默无持久化。
  - 委派持久化 `aether.delegation.persistence`（默认 false）同样给出明确策略与告警。
- **参考模式**：Transactional Outbox 思想 / 默认安全。
- **验收**：**零额外配置**启动后，`/api/v1/create_session` + 对话 + 重启进程，会话可从 PostgreSQL 恢复；health 端点反映持久化状态。
- **影响面**：`application-dev.yml` 默认值、两个 Repository 的 `@ConditionalOnProperty`、健康检查。

### P1 — 重要稳定性（应做）

#### P1-1 启动装配失败隔离
- **问题**：`AiAgentAutoConfig.java:29-38` 任一 Agent YAML 错误即 `throw RuntimeException` 阻断整体启动。
- **目标设计**：单 Agent 装配失败 → 记录日志 + 标记该 Agent `degraded` + 继续装配其余；应用正常启动并服务健康 Agent；`/health` 暴露"N/M Agent 就绪"。
- **参考模式**：失败隔离（舱壁思想）。
- **验收**：故意制造 1 个损坏 YAML，其余 Agent 仍可正常对话；health 显示部分降级。
- **影响面**：`AiAgentAutoConfig`、`AgentRegistry` 装配态、`Application.java` 健康检查。

#### P1-2 下游边界韧性中间件（Resilience4j）
- **问题**：全仓库无 resilience4j/CB/RL；LLM Provider、MCP Server、Python fs 服务调用无熔断/限流/舱壁。
- **目标设计**：在**出站适配器**层统一包裹：
  - LLM Provider 出站：`CircuitBreaker`（按 provider 维度）+ `RateLimiter`（防打爆上游）+ `Bulkhead`（限制并发占用）。
  - MCP Server 出站：同上 + 连接级超时。
  - 下游 HTTP（fs 服务）：`CircuitBreaker` + `Timeout` + `RateLimiter`。
  - 与既有 `ResilientChatModelExecutor` 的"分类容错/fallback"**互补不冲突**：Resilience4j 管"调用级熔断/限流/舱壁"，ResilientChatModelExecutor 管"语义级重试/回退"，职责分层。
- **参考模式**：Circuit Breaker / Rate Limiter / Bulkhead（Richardson Ch.3）。
- **验收**：某 Provider 持续 5xx 时自动熔断并走 fallback 链，QPS 不再压垮上游；熔断/半开/恢复有指标与日志；压测验证限流生效。
- **影响面**：`ModelProvider` 出站、`McpClientFactory`、`HttpClientConfig`；新增 resilience4j 依赖（pom 统一管理版本）。

#### P1-3 进程内状态外置 / 单实例约束标注
- **问题**：`AgentRegistry.java:19`、`DefaultMcpToolRegistry.java:22-26`、`SubAgentOrchestrator.java:34 Semaphore(5)`、`RotatingCredentialPool` 等均为实例本地单例，多副本下不一致。
- **目标设计（两步走）**：
  - **短期（阶段 A）**：在部署文档与代码注释中**显式标注"单实例约束"**，禁止无外置即多副本；`SubAgentOrchestrator` 的 `Semaphore(5)` 改为"单实例内并发上限"语义清晰化。
  - **中期（阶段 B）**：将"运行期可变状态"外置到 Redis——凭据池租约、MCP 连接注册表、子 Agent 租约计数（`LeaseManager` 的 `ConcurrentHashMap` 可迁 Redis）。**Agent 定义（编译期）保留本地内存**。
- **参考模式**：Scale Cube / 状态外置。
- **验收**：单实例约束写入运维手册；Redis 外置方案有迁移设计（接口不变，仅 Repository/Registry 实现切换）。
- **影响面**：`AgentRegistry`、`DefaultMcpToolRegistry`、`RotatingCredentialPool`、`LeaseManager` 等。

#### P1-4 检查点/委派状态跨实例一致性
- **问题**：`GitShadowCheckpointStore` 基于**本地文件系统**；`AsyncDelegationService.java:27-76` 仅 `@PostConstruct` 一次性恢复，`StaleDelegationScanner` 默认 `interval≤0` 禁用。
- **目标设计**：
  - 检查点存储支持**共享后端**（对象存储 S3 兼容 或 DB BLOB），保留 JGit 内容寻址去重逻辑，仅把 bare 仓位置迁到共享卷/对象存储。
  - `StaleDelegationScanner` 默认开启合理间隔（如 60s），保证挂起子 Agent 租约被回收，释放池线程。
- **参考模式**：Saga / 持久化作业 / 共享状态。
- **验收**：多实例部署下检查点/委派可从共享后端恢复；stale 扫描在默认配置下自动运行并回收孤儿租约。
- **影响面**：`GitShadowCheckpointStore`、`StaleDelegationScanner`、`AsyncDelegationService`。

### P2 — 扩展性增强（择机做，尊重 YAGNI）

#### P2-1 模块依赖修正（trigger 不依赖 infrastructure）
- **问题**：`aether-trigger/pom.xml:49` 直接依赖 `aether-infrastructure`，违反六边形（入口适配不应耦合基础设施适配器）。
- **目标设计**：trigger 仅依赖 `api` + `domain` 的**端口接口**；infrastructure 的 Bean 经 Spring 自动装配注入，trigger 编译期 classpath 不含 infrastructure 类。若某些类型必须共享，上移到 `api`/`types`。
- **参考模式**：六边形（Ports & Adapters）。
- **验收**：`aether-trigger` 编译 classpath 无 infrastructure 类；既有功能零回归。
- **影响面**：`aether-trigger/pom.xml`、可能的类型上移。

#### P2-2 装配链可注册化
- **问题**：`armory/node/*.java` 硬编码 `@Service` 固定链，新增装配维度需改链（扩展摩擦）。
- **目标设计**：将各 Node 抽象为 `ArmoryStep` 接口 + `@Order` 有序 Bean，由 `ArmoryService` 按序执行；新增维度只需加一个 `@Component @Order` 的 Step，**不改既有 Node**。
- **参考模式**：开放封闭原则 / 策略可插拔。
- **验收**：新增一个装配步骤（如新的预处理 Step）不修改任何既有 Node 类；装配顺序可测。
- **影响面**：`armory/node/*`、`ArmoryService`。

#### P2-3 执行服务可独立提取（演进边界，仅定义）
- **问题**：执行逻辑内嵌于 trigger→domain 调用链，未来若需独立"执行服务"会因循环依赖难以拆分。
- **目标设计**：定义 `AgentExecutionPort`（入参：执行请求 DTO；出参：SSE/事件流），当前由单体内 `ReActAgent`/`GraphExecutor` 实现。**本次仅定义接口与依赖方向，不实现独立部署**。
- **参考模式**：演进边界（Richardson "extracting a service" 前置）。
- **验收**：接口已定义、依赖方向清晰，未来提取时无循环依赖；有 ADR 记录。
- **影响面**：新增 `api` 端口接口；零运行期变更。

#### P2-4 边缘网关 / BFF（可选）
- **问题**：无统一边缘层，鉴权/CORS/限流/trace 分散在各 Controller。
- **目标设计**：在 trigger 前置一层网关（Spring Cloud Gateway 或 Nginx），统一 JWT 校验、CORS 白名单、`X-Correlation-Id` 注入、边缘限流；trigger 收敛为"应用路由层"。
- **参考模式**：API Gateway 模式。
- **验收**：边缘层统一拦截未授权请求；trace id 跨跳贯穿。
- **影响面**：新增网关组件 / Nginx 配置；trigger 安全中间件可下移。

---

## 6. 关键设计决策详解

### 6.1 为何"模块化单体优先"而非直接拆微服务
CLAUDE.md 强调"简单优先、不做推测性抽象"。当前瓶颈是**进程内资源治理与韧性**，不是"团队/部署边界"。拆微服务会引入分布式事务、服务发现、网络韧性等更大复杂度，与 YAGNI 冲突。先把单体内部边界与韧性做对，水平扩展（X 轴多副本）在状态外置（P1-3/P1-4）后即水到渠成。

### 6.2 重试单一所有权的边界划分
- **Resilience4j（P1-2）**：管"调用级"——熔断（连续失败→开路）、限流（QPS 上限）、舱壁（并发上限）、超时。
- **ResilientChatModelExecutor（P0-2 保留为唯一）**：管"语义级"——错误分类（AUTH/RATE_LIMIT/CONTEXT_OVERFLOW…）、退避重试、fallback 模型链、凭据池轮换。
- **ModelInvoker（P0-2 退化）**：仅单次调用 + 双通道超时。
- 三者职责不重叠，消除 F3 的复合放大。

### 6.3 默认安全的持久化策略
优先级：PostgreSQL 持久化（已依赖 pgvector，零新依赖）> 显式 Redis 覆盖 > 缺失配置时 WARN+degraded（不再静默无持久化）。这样既满足"状态完整恢复"设计目标，又不强制引入 Redis。

### 6.4 状态分类外置
- **保留本地内存**：Agent 定义（编译期、只读）、工具定义、模型注册表元数据——这些本就应在启动时固化。
- **外置（阶段 B）**：会话态、子 Agent 租约、凭据池租约、MCP 连接注册、检查点——运行期可变且需跨实例一致。
- 此分类直接对应 Scale Cube 的"先把有状态部分识别出来"。

---

## 7. 验收与可观测性

### 7.1 必建指标（Micrometer/Prometheus 已有基础，补韧性维度）
- `resilience4j.circuitbreaker.calls`（open/closed/half-open 计数）
- `resilience4j.bulkhead.available` / `resilience4j.ratelimiter.count`
- `aether.threadpool.graph.active` / `aether.threadpool.graph.rejected`
- `aether.session.persistence.enabled` / `aether.agent.assembly.degraded`

### 7.2 SLO 建议
- 单 Agent 装配失败不影响整体可用性（P1-1 验收）。
- 单实例内并发图执行线程数 ≤ 配置上限（P0-1 验收）。
- 下游 Provider 持续故障时，95% 请求在 fallback 链内完成，不雪崩（P1-2 验收）。
- 默认配置下会话跨重启可恢复率 100%（P0-3 验收）。

### 7.3 回滚
P0-2（重试收口）属高危改动，建议通过配置开关保留 `ModelInvoker` 旧重试路径，灰度验证后再移除。

---

## 8. 风险与未决问题

| 项 | 说明 | 责任 |
|---|---|---|
| 记忆系统检索未实现真实向量检索 | `MemoryStore.java`/`PgvectorVectorStore.java` 的 `search` 为伪随机/硬编码相似度（见《Aether 记忆系统代码级分析》§3.3），属"有向量之名无向量之实"。**不在本架构重构范围**，建议单列数据层优化项 | 数据/算法 |
| 双重试历史包袱 | `ModelInvoker` 旧重试循环是否与某调用路径仍必须保留，需回归 `ReActAgent`/`GraphExecutor` 全链路确认 | 实施时 |
| Redis 是否引入 | P1-3/P1-4 外置状态可选 Redis；若暂不愿引入，先以"单实例约束 + 共享文件系统/对象存储"过渡 | 架构决策 |
| 工作区分支 | 记忆对齐特性位于未合并分支 `feat/memory-hermes-alignment`，本方案以工作区现状为基线，落地前需确认合并基线 | 实施前 |

---

## 9. 附录：本次明确**不**做（YAGNI 边界）

- 不拆微服务、不引入服务网格 / 服务发现（除非 P1-3 外置完成且确有水平扩展需求）。
- 不重写 Agent 引擎 / 记忆 / 权限（已成熟，仅顺带标注约束）。
- 不实现独立"执行服务"部署（P2-3 仅定义边界）。
- 不替换 PostgreSQL 为分库分表（单实例吞吐未触顶前不必要）。
- 不处理记忆向量检索缺陷（单列，非本方案范围）。

---

*本方案所有结论均可回溯至 Chris Richardson《Microservices Patterns》的模块化单体、Scale Cube、韧性三模式、Outbox/Saga 四类模式；模块边界采用六边形架构。证据行号来自 `D:\code\Agents-framework\aether` 工作区现状，可逐条复核。*

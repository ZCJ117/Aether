# Aether P2 路线图交付说明（2026-09-01）

本文档对 `docs/项目优化路线图.md` 中四个 P2 项做完成态自检。

## 1. 水平扩展与有状态治理（1.4）

**目标：** 多实例演进路径明确，LLM 缓存与会话存储不再单点。

### 已实现

- LLM 缓存升级为 Caffeine L1 + Redis L2：
  - `ModelCallCache` 先读本地，未命中读 L2，L2 命中回填 L1；
  - `ModelCacheStore` / `ModelCacheSnapshot` 抽象序列化边界；
  - `RedisModelCacheStore` 只在 `aether.cache.llm.redis-enabled=true` 时装配；
  - Redis 读取/写入异常只降级为 L1-only，不阻断模型调用。
- `RedisSessionRepository` 补齐全部 `SessionRepository` 接口：
  - 单会话 JSON 值 + 7 天 TTL；
  - `user` / `agent` Hash 二级索引；
  - active Set 统计索引；
  - `listByUserId`、`listByUserIdAndAgentId`、`countActiveSessions`、`countSessionsByAgent` 全部可用；
  - 删除语义与 PostgreSQL 对齐：软删为 ARCHIVED，列表/统计隐藏，按 sessionId 仍可恢复。
- 新增设计文档：`docs/horizontal-scaling-design.md`；
- 新增多实例演示：`docker/docker-compose-scale.yml` + `docker/nginx/aether-scale.conf`。

### 自检结论

通过。Redis 缺失或关闭时回退既有 Caffeine/PostgreSQL 行为；Redis 模式下列表/统计不再抛 `UnsupportedOperationException`。

## 2. 残留重构（2.3）

**目标：** GraphExecutor 按编排策略拆分，notes 包显式标注，补 V1 基线。

### 已实现

- 新增 `GraphOrchestrationStrategy` 接口和五种编排实现：
  - Sequential / Parallel / Loop / SubAgent / EventDriven；
  - 共享单节点执行和拦截语义下沉到 `OrchestrationServices`；
  - GRAPHFLOW DAG 独立为 `GraphFlowCoordinator`；
- `GraphExecutor` 保留统一生命周期：trace、MDC、生命周期钩子、异常完成、有界线程池；
- `data/sql/V1__baseline.sql` 建立 V1 → V5 迁移链起点；
- notes 包新增实验性说明：
  - `ExternalNotes` 类注释标注 `EXPERIMENTAL`；
  - `docs/notes-experimental.md` 说明能力边界。

### 自检结论

通过。executor 包最大生产类 `GraphFlowCoordinator` 为 262 行，原 `GraphExecutor` 现为 207 行，远低于路线图 600 行要求；相关测试 `GraphExecutorOrchestrationTest` 会持续守住该包上限。

## 3. 日志体系收尾（3.4）

**目标：** 按 env 分离级别，补 trace → MDC → 审计排障手册。

### 已实现

- `logback-spring.xml`：
  - dev 保持 `cn.zcj.aether.domain.agent=DEBUG`；
  - prod 收紧为 INFO，Spring Security 为 WARN；
  - JSON encoder 补充 `graphExecutionId` MDC 字段；
- `application-prod.yml` 显式声明生产 Agent 日志为 INFO；
- 新增 `docs/log-troubleshooting.md`，给出从 `X-Correlation-Id` / traceId 到 JSON 日志、MDC、graph trace、PostgreSQL 审计表的完整三步定位流程和命令。

### 自检结论

通过。日志排障从“配置完善”升级为可执行方法论；JSON 日志现在能稳定携带 graph 和 session 关联键。

## 4. 多轮对话与规划能力（4.4）

**目标：** PlanActAgent 支持中途重规划和任务后反思，复盘沉淀到 notes。

### 已实现

- 中途重规划触发条件：
  - 连续工具失败；
  - 步骤输出未通过 LLM 检查点评估；
- 重规划行为：
  - 保留已完成步骤；
  - 调 LLM 生成替代后续步骤；
  - 重新编号并从第一个 pending 步骤继续；
  - LLM 重规划失败时保留当前计划重试，不让主流程直接崩溃；
- 任务后反思：
  - LLM 自评计划合理性、主要问题和改进点；
  - 自评先写入 `ExternalNotes`；
  - `BackgroundReviewer` 后台评审通过 `ReviewSink` 二次沉淀到 notes；
- 指标：
  - `aether.agent.plan.replans.total`；
  - `aether.agent.plan.completions.total`；
  - `aether.agent.plan.reflections.total`。

### 自检结论

通过。规划 Agent 具备失败反馈闭环；测试覆盖重规划合并、重规划失败兜底、检查点不达标、反思持久化和后台评审回调。

## 5. 验证记录

- `mvn -B -q test`：全部模块单元测试通过，退出码 0；
- 目标测试：
  - `ModelCallCacheTest` / `RedisSessionRepositoryTest`；
  - `GraphExecutorOrchestrationTest`；
  - `PlanActAgentReplanTest` / `BackgroundReviewerTest`；
- Logback XML 已通过 XML parser 校验；
- executor 包行数自检：`GraphFlowCoordinator=262`、`GraphExecutor=207`，均小于 600。

## 6. 主要交付文件

- `aether-domain/.../runtime/ModelCallCache.java`
- `aether-domain/.../runtime/ModelCacheStore.java`
- `aether-domain/.../runtime/ModelCacheSnapshot.java`
- `aether-infrastructure/.../RedisModelCacheStore.java`
- `aether-infrastructure/.../RedisSessionRepository.java`
- `aether-domain/.../executor/orchestration/*`
- `aether-domain/.../executor/GraphExecutor.java`
- `aether-domain/.../agent/impl/PlanActAgent.java`
- `aether-domain/.../agent/observability/BackgroundReviewer.java`
- `aether-domain/.../agent/observability/AgentMetrics.java`
- `aether-app/src/main/resources/logback-spring.xml`
- `aether-app/src/main/resources/application-prod.yml`
- `data/sql/V1__baseline.sql`
- `docker/docker-compose-scale.yml`
- `docker/nginx/aether-scale.conf`
- `docs/horizontal-scaling-design.md`
- `docs/log-troubleshooting.md`
- `docs/notes-experimental.md`

## 7. Code Review P1/P2 修复记录（2026-09-01）

### 7.1 Review 原始问题修复

**P1**

- 广播拦截语义恢复为发布侧拦截：`OrchestrationServices.applyBroadcastInterception` 使用 `onPublish`，新增 `OrchestrationServicesBroadcastTest` 守护。
- 完成率指标按结果拆分：`AgentMetrics` 将 `aether.agent.plan.completions.total` 细分为 `result=success` / `result=failure`，新增 `AgentMetricsTest`。

**P2**

- `PlanActAgent` 不再把未完成计划误记为成功；abort 也记录失败，新增未完成步骤守护测试。
- `RedisSessionRepository.writeSession` 改为 Redis pipeline 批量写 session/user/agent/active；`save` / `deleteBySessionId` 失败不再吞异常，记录 `SessionPersistenceMetrics` 后向上传播。
- `data/sql/V1__baseline.sql` 替换为完整幂等 PostgreSQL 基线，覆盖用户、refresh token、审计、session、pgvector memory 和 HNSW。
- 补齐水平扩展压测：新增压测脚本、说明与报告、单实例 Nginx/compose；修正双实例 compose 数据源与后端地址。真实业务端点压测结果 single=1143.81 RPS，dual=2036.37 RPS，线性度 1.78x，PASS。
- 修正 Nginx 空会话头的 hash 热点：携带 `X-Session-Id` 的请求继续走一致性 hash 粘性路由；新建会话等无 session 头请求走 `least_conn` 分流。单实例配置同步使用变量 upstream，Nginx `nginx -t` 通过。

### 7.2 集成验证过程中的补充修复

- 升级 Testcontainers BOM 到 2.0.2 以适配 Docker 29 的最低 API 约束；同步迁移 `postgresql` / `junit-jupiter` / `kafka` 到 Testcontainers 2.x 坐标。
- infrastructure 测试链路恢复执行：
  - `pgvector` 列启用后，upsert / embedding 更新参数显式转换为 `?::vector`；
  - recency 回退搜索只累加 `access_count`，不刷新 `last_accessed_at`，避免归档前搜索复活所有记忆并破坏遗忘曲线判定；
  - failsafe 排除 `xfg-wrench` fat-jar，避免其旧 Jackson annotations 遮蔽 Testcontainers 2.x 依赖。
- trigger Redis 令牌桶并发 IT 由 `refillPerMinute=60` 改为 `1`，消除测试运行超过 1 秒后合法补充令牌造成的 101/100 假阳性；Lua 侧仍是原子扣减。

### 7.3 最终验证证据

```text
mvn -B test
BUILD SUCCESS
Total time: 01:29 min

mvn -B -DskipTests package
BUILD SUCCESS
Total time: 42.125 s

DOCKER_HOST=npipe:////./pipe/docker_engine_linux
mvn -B -pl aether-infrastructure -am test-compile \
  failsafe:integration-test failsafe:verify -Pintegration
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -B -pl aether-trigger -am test-compile \
  '-Dit.test=RedisTokenBucketRateLimiterIT' \
  '-Dfailsafe.failIfNoSpecifiedTests=false' \
  failsafe:integration-test failsafe:verify -Pintegration
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

此前已完成：Docker 镜像构建成功；真实双实例压测线性度 1.78x，PASS。

### 7.4 当前限制

- `aether-app` 的 `KafkaAuditPipelineIT` 未纳入本轮最终集成记录：Docker Hub 拉取 `apache/kafka:3.8.0` 长时间阻塞。Kafka 相关单元测试已在 `mvn -B test` 中全部通过。
- 压测路由修复后的一次 64 并发 / 20s 复测为 1.61x（single=1563.82 RPS，dual=2511.66 RPS，0 错误），低于脚本 1.70x 阈值；继续复测时本机 Docker Desktop 进入 `unable to start` 状态，因此不能在当前工作区刷新 1.78x PASS 报告。该结果已保留在 `target/scaling-benchmark-report-final.md`，不应作为正式 PASS 证据。

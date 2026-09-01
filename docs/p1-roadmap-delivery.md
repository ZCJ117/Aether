# P1 路线图端到端交付说明

> 对应《项目优化路线图》P1 七项：高并发线程模型 / 分布式限流 / 消息队列解耦 / 监控告警 /
> Testcontainers 集成测试 / RAG 三级检索管道 / 记忆机制深化。
> 实施计划：`docs/superpowers/plans/2026-08-31-p1-roadmap-delivery.md`（含逐任务步骤与验收命令）。

## 0. 一键启动（全栈含 P1 组件）

```bash
cd aether
cp docker/.env.example docker/.env   # 填 JASYPT_MASTER_PASSWORD / DB_PASSWORD / JWT_SECRET（ZHIPU_API_KEY 可选）
docker compose -f docker/docker-compose-fullstack.yml --env-file docker/.env up -d

# 服务清单：aether:8091 | postgres(pgvector) | redis:6379 | kafka:9092
#           prometheus:9090 | grafana:3000 | alertmanager:9093 | node-exporter:9100 | alert-echo:8081
curl http://localhost:8091/actuator/health          # 应用健康
curl http://localhost:8081/alerts | jq              # 告警通知通道回显（可实测触达）
```

不带监控/中间件的原最小部署（`docker-compose-secure.yml`）不受影响；全部新能力默认关闭或降级安全。

## 1. 七项交付对照表

| # | 路线图条目 | 交付物 | 关键参数/开关 | 验证 |
|---|---|---|---|---|
| ① | 高并发线程模型（1.1） | `ModelCallObservability`（`aether.model.waiting.threads` Gauge + `aether.model.call.timeouts.total`）接入 `ReActAgent.invokeModelStreaming`；`AetherExecutorRegistry` 全部有界池绑定 `ExecutorServiceMetrics` + `aether.executor.queue.utilization{pool}`；`server.tomcat.threads.max=200`；容量公式文档 | `aether.thread-pools.pools.graph.*`（Map 绑定键必须含 `pools` 层级，有 yml 绑定回归测试守护）、`server.tomcat.threads.*`、`hikari.maximum-pool-size` | `ModelCallObservabilityTest`（3）、`AetherExecutorRegistryTest`（5，含 application.yml 绑定回归）；容量公式 `docs/capacity-planning.md` |
| ② | 分布式限流（1.2） | `RateLimiter` 策略接口 + **令牌桶**（内存/Redis-Lua 双实现）+ 降级保护；限流键 **userId 优先**（Bearer Token 校验通过取 subject）、未认证/Token 无效退回 **客户端 IP**（登录端点无 Token，天然按 IP 防爆破）；`X-RateLimit-*`/`Retry-After` 头；429 契约不变 | `aether.security.rate-limit.{enabled,mode=memory\|redis,default-capacity=100,default-refill-per-minute=100,login-capacity=5,login-refill-per-minute=5}`；Redis 不可用自动降级内存（WARN 60s 节流 + `aether.ratelimit.fallback.total`） | 单测 13（含 userId 跨 IP 共享桶 / 无效 Token 回退 IP 键控）；`RedisTokenBucketRateLimiterIT`（原子性 32 线程恰放行 100、共享计数、Retry-After 边界，`-Pintegration`） |
| ③ | 消息队列解耦（1.3） | Kafka 双链路：①审计事件 `aether.audit.events` → 批量消费落 `t_audit_log`（削峰+批量写）②`AgentEventPublisher` → `aether.agent.events` → 聚合 `dashboard_stats`；**不丢**（同步确认+降级直写/手动 ack）、**不重**（`event_id` ON CONFLICT 去重 + `aether_processed_event`）、**有序**（key=userId/sessionId 分区）、重试×3 指数退避 → `*.DLT` | `aether.kafka.enabled=false`（默认关）；`aether.kafka.produce-timeout-ms=2000`；`aether.dashboard.stats-source=live\|kafka` | 单测 11；`KafkaAuditPipelineIT`（Testcontainers KafkaContainer + 真实 PG：重复 eventId 幂等、聚合正确、毒消息不阻断） |
| ④ | 监控告警（3.3） | Prometheus + Grafana + Alertmanager + node-exporter + alert-echo 全栈 compose；**7 条告警规则**；12 面板仪表盘；webhook（alert-echo 回显，可实测）+ 邮件/钉钉注释模板 | `docker/prometheus/rules.yml`（模型超时/错误率>5%/持久化失败/graphPool 饱和>80%/限流突增/Kafka lag>1000/实例宕机）；抑制规则（宕机抑制业务告警） | `docker compose config` 通过；指标名与代码逐一 grep 对齐；演练步骤 `docs/monitoring-guide.md` §6 |
| ⑤ | 集成测试（2.2） | Testcontainers（pgvector/pgvector:pg16 与生产一致）+ failsafe `**/*IT.java` + `-Pintegration` profile + CI 独立 job；`AbstractPgIT` 按**演进链**执行 DDL（canonical 基线 → `V3__audit_log` → `V5__p1_messaging_and_memory`），迁移文件与生产同源 | **5 个 IT**：`PgSessionRepositoryIT`（save/restore/list/软删 + state_json 逐字节往返）、`PgMemoryLifecycleIT`（归档→不可见→复活→touch 回填）、`PgHybridSearchRepositoryIT`（语义+词法 RRF/GIN 索引/归档过滤）、`RedisTokenBucketRateLimiterIT`（原子性/边界）、`KafkaAuditPipelineIT`（幂等/聚合/毒消息） | `mvn verify -Pintegration`（需 Docker；CI `integration` job 自动执行） |
| ⑥ | RAG 三级管道（4.2） | 一级 LLM 查询改写（fail-open）→ 二级混合召回（pgvector 语义 + PG 全文 bigram-GIN 词法，**RRF 单 SQL**，中文零扩展适配）→ 三级 Python 重排（`/rerank`，启发式 F1 / 可选 CrossEncoder）；`RecallFlow` 可选接管；StageTrace + 每级 Timer/降级指标 | `aether.rag.{enabled=false,rewrite.*,hybrid.*,rerank.*}`（`rrf-k`/`vector-top-k`/`lexical-top-k` 直通 SQL 生成，无重复绑定）；逐级降级矩阵见 `docs/rag-pipeline.md` | 确定性评测已接线 **30 query 标注集**（`aether-domain/src/test/resources/rag/queries.jsonl`，`RetrievalEvalTest` CI 常驻）：Recall@5/MRR = vec 0.800/0.688 → hybrid **0.867**/0.681 → +rerank 0.867/**0.757** |
| ⑦ | 记忆深化（4.3） | ①遗忘曲线：保留分 = 0.5×新近度(半衰30d)+0.3×频次+0.2×importance，`archived=true` 软删（可复活），共享 scheduledPool 周期任务（`decay.enabled` 真实生效）②写入门槛：LLM 打分 <0.6 拒绝入库（无 LLM 不拦）③冲突合并：concat/new-wins/llm 三策略 | `aether.memory.decay.{enabled=true,interval-minutes=60,half-life-days=30,min-retention-score=0.2}`、`write-gate.{enabled=true,min-importance=0.6}`、`conflict.strategy=concat` | 单测 16（含 20 轮跨会话依赖评测：写入门槛拦 16 条闲聊 / 跨会话偏好更新不打架 / 衰减只归档弱备注）；`PgMemoryLifecycleIT`（归档→不可见→复活→access_count 回填）；瘦身率 `aether.memory.decay.slim.ratio` |

## 2. 兼容性与默认行为（零破坏承诺）

- 既有 399 个单测全绿（本次新增 **165** 个用例，全仓 **564** 个，`mvn -B test` 汇总 0 失败 0 错误，1:26 min；其中 6 个为代码评审修复轮新增：yml 绑定回归、decay.enabled、限流键控×2、RRF 参数化、20 轮跨会话评测）；
- 全部新 Bean 条件装配：Kafka/Redis 限流/RAG 重排/统计读模型默认**关闭**；限流默认内存模式；RAG 管道默认旁路；记忆门槛仅在 LLM 成功打分时生效；
- `docker-compose-secure.yml`/`-bench.yml` 未改动；新栈在 `docker-compose-fullstack.yml`；
- schema 变更全部幂等：认证/审计域进 `schema.sql`（启动自动执行）；记忆/向量域走 Java `@PostConstruct ensure*()` 自愈（无 pgvector 扩展的库不受影响）；手工迁移存档 `data/sql/V5__p1_messaging_and_memory.sql`（并随 `AbstractPgIT` 以 baseline+V3+V5 演进链做真实库回归）。

## 3. 已知限制与后续（P2 候选）

- 限流 Redis 往返延迟开销数据、并发拐点数字 → 依赖 P0 压测体系跑批（`docs/capacity-planning.md` §6 有方法）；
- RAG 真实模型在线评测（DeepSeek + 真实 embedding 的在线 Runner）为后续工作，CI 常驻的是 30 条标注集确定性评测（`RetrievalEvalTest`）；
- 重排模型（bge-reranker）默认未安装，`/rerank` 用确定性启发式打分；设 `RERANK_MODEL` 环境变量即可升级；
- Grafana 面板截图需运行态导出（CI 无法截取），步骤见 `docs/monitoring-guide.md` §7。

## 4. 变更文件地图

```
aether/
├─ pom.xml                                   # testcontainers-bom + integration profile(failsafe)
├─ .github/workflows/ci.yml                  # + integration job
├─ aether-types/    types/messaging/*        # Kafka 消息契约（Audit/AgentEventMessage + Codec + Topics）
├─ aether-domain/
│  ├─ agent/observability/ModelCallObservability
│  ├─ agent/service/agent/impl/ReActAgent     # latch 等待埋点 + 超时计数
│  ├─ agent/service/memory/lifecycle/*        # DecayStore/DecayJob/WriteGate/ConflictResolver/Metrics
│  ├─ agent/service/memory/{MemoryRecord,DefaultMemoryFacade,RecallFlow,core/MemoryProperties}
│  ├─ agent/service/support/{DaemonThreads,LlmJson}   # 守护线程工厂 / LLM JSON 解析共用辅助
│  └─ agent/service/retrieval/rag/*           # Pipeline/QueryRewriter/CjkBigram/端口/RagProperties
├─ aether-infrastructure/
│  ├─ repository/PgvectorVectorStore          # archived 过滤/复活/touch/content_bigram/自愈列
│  ├─ repository/{PgMemoryDecayStore,PgHybridSearchRepository,PythonReranker}
│  ├─ persistence/{AuditLogRepository(batch),DashboardStatsRepository,ProcessedEventRepository,PgDashboardStatsStore}
│  └─ python/PythonServiceClient              # + rerank
├─ aether-trigger/
│  ├─ http/filter/{RateLimitFilter,ratelimit/*}   # 策略化令牌桶 + Redis Lua + 降级
│  └─ listener/{KafkaConsumerConfig,AuditEventConsumer,DashboardStatsConsumer}
├─ aether-app/
│  ├─ aspect/AuditAspect                      # Kafka 优先 → 降级直写
│  ├─ messaging/*                             # Producer/Bridge/TopicConfig
│  ├─ config/AetherExecutorRegistry           # 池指标绑定
│  ├─ resources/{application.yml(+全部新配置),schema.sql(+幂等 DDL)}
│  └─ src/test/…（单测 + KafkaAuditPipelineIT）
├─ docker/                                    # fullstack compose + prometheus/grafana/alertmanager/alert-echo
├─ data/sql/V5__p1_messaging_and_memory.sql   # 手工迁移存档
└─ docs/{capacity-planning,monitoring-guide,rag-pipeline,memory-lifecycle,p1-roadmap-delivery}.md
```

# P1 路线图端到端交付 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付《项目优化路线图》P1 全部七项：高并发线程模型、分布式限流、消息队列解耦、监控告警、Testcontainers 集成测试、RAG 三级检索管道、记忆机制深化——全部可编译、可运行、带测试与配置。

**Architecture:** 在既有六模块 Maven（aether-api/app/domain/infrastructure/trigger/types，Java 17 + Spring Boot 3.4.3）上做增量扩展：①线程模型为既有 `AetherExecutorRegistry`/`ReActAgent` 增加可观测与配置面；②限流改为 `RateLimiter` 策略接口（内存令牌桶/Redis Lua 令牌桶）+ 降级；③Kafka 双链路（审计批量消费 + Agent 事件统计聚合），生产端降级直写、消费端手动 ack + event_id 幂等 + DLT；④Prometheus/Grafana/Alertmanager 全栈 compose + 7 条告警规则；⑤Testcontainers PG(pgvector)IT + failsafe `-Pintegration`；⑥RAG 三级管道（LLM 改写 → pgvector+tsvector-bigram RRF 混合召回 → Python 重排）以可选 Bean 接入 `RecallFlow`；⑦记忆衰减（archived 软删 + 保留分）、写入重要性门槛、LLM 冲突合并。

**Tech Stack:** Spring Boot 3.4.3 / spring-kafka / spring-boot-starter-data-redis(Lettuce) / Micrometer+Prometheus / Testcontainers(postgresql, GenericContainer redis) / spring-kafka-test(EmbeddedKafka) / PostgreSQL16+pgvector(HNSW+GIN) / FastAPI。

**Spec:** `D:\code\Agents-framework\docs\项目优化路线图.md` §1.1/1.2/1.3/2.2/3.3/4.2/4.3

## Global Constraints

- Java 17，Spring Boot 3.4.3，父 pom 统一 dependencyManagement，版本优先走 Boot BOM。
- 新增依赖只允许：`spring-kafka`、`spring-kafka-test`、`spring-boot-starter-data-redis`、`org.testcontainers:*`、`micrometer-registry-prometheus`（已有）。
- 主配置命名空间：限流 `aether.security.rate-limit.*`；Kafka `aether.kafka.*`；RAG `aether.rag.*`；记忆深化并入 `aether.memory.*`；线程池 `aether.thread-pools.*`。
- 所有新功能必须有 `enabled` 开关且**默认关闭/降级安全**：`aether.kafka.enabled=false`、`aether.rag.enabled=false`、`aether.security.rate-limit.mode=memory`（默认内存）、记忆衰减/写入门默认开启但仅作用于 pgvector 后端。既有 399 个单测必须保持全绿。
- 统一线程资源管理：新调度任务一律注入共享 `scheduledPool`（`@Qualifier`），禁止 `@Scheduled`/自建池（参照 `StaleDelegationScanner`）。
- 基础包 `cn.zcj.aether` 全模块共享，新包被组件扫描；`@ConfigurationProperties` 需在某个 `@Configuration` 上 `@EnableConfigurationProperties` 注册。
- SQL 变更：认证/审计域进 `aether-app/src/main/resources/schema.sql`（幂等）；记忆/向量域自愈式 DDL 放 Java `@PostConstruct ensure*()`（避免无 pgvector 扩展的库启动失败）+ 迁移文件 `data/sql/V5__p1_messaging_and_memory.sql` 存档。
- 文档一律中文，放 `aether/docs/`。
- 提交约定：每 Task 一次 commit，message 格式 `p1(<域>): <内容>`。

---

### Task 0: 构建基建 — Testcontainers 依赖 + failsafe integration profile + CI 集成 job

**Files:**
- Modify: `aether/pom.xml`（dependencyManagement 加 testcontainers-bom；`<profiles>` 加 `integration`）
- Modify: `aether/aether-infrastructure/pom.xml`、`aether/aether-trigger/pom.xml`、`aether/aether-app/pom.xml`（test 依赖）
- Modify: `aether/.github/workflows/ci.yml`（加 `integration` job）
- Create: `aether/aether-infrastructure/src/test/java/cn/zcj/aether/repository/AbstractPgIT.java`

**Interfaces:**
- Produces: `integration` Maven profile（`mvn verify -Pintegration` 跑 `**/*IT.java`）；`AbstractPgIT` 基类（单例 `pgvector/pgvector:pg16` 容器 + DataSource + schema 执行）。
- Consumes: 无。

- [ ] **Step 1: 父 pom 加 BOM 与 profile**

`aether/pom.xml` dependencyManagement 中（opentelemetry-bom import 之后）加入：

```xml
<!-- P1-5: Testcontainers BOM（集成测试） -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

父 pom 现有 profiles（dev/test/prod）后追加：

```xml
<!-- P1-5: 集成测试 profile —— mvn verify -Pintegration 跑 **/*IT.java（需 Docker） -->
<profile>
    <id>integration</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

- [ ] **Step 2: 三个子模块加 test 依赖**

`aether-infrastructure/pom.xml` 与 `aether-app/pom.xml`（test scope）：

```xml
<!-- P1-5: Testcontainers 集成测试 -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

`aether-trigger/pom.xml`（test scope）：`org.testcontainers:junit-jupiter`（Redis IT 用 GenericContainer 即可，无需额外模块）。

- [ ] **Step 3: AbstractPgIT 基类（infrastructure test）**

```java
package cn.zcj.aether.repository;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/** P1-5: pgvector 集成测试基类 —— 单例容器（JVM 级复用），与生产镜像 pgvector/pgvector:pg16 一致。 */
public abstract class AbstractPgIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aether").withUsername("aether").withPassword("aether");

    static { PG.start(); }

    protected static DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    protected static JdbcTemplate jdbc() { return new JdbcTemplate(dataSource()); }

    /** 执行 docs/postgresql_schema.sql（canonical DDL：aether_session + aether_memories + pgvector 迁移） */
    @BeforeAll
    static void initSchema() throws Exception {
        Path schema = Path.of("../docs/postgresql_schema.sql");
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            for (String stmt : Files.readString(schema).split(";")) {
                String s = stmt.strip();
                if (!s.isEmpty() && !s.startsWith("--")) st.execute(s);
            }
        }
    }
}
```

- [ ] **Step 4: CI 加 integration job**（`.github/workflows/ci.yml` 追加）

```yaml
  integration:
    name: Integration Tests (Testcontainers)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - name: Run Testcontainers ITs
        run: mvn -B verify -Pintegration -DskipSurefire=false
      - name: Upload failsafe reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: failsafe-reports
          path: '**/target/failsafe-reports/*.txt'
```

- [ ] **Step 5: 验证**

Run: `mvn -B compile -q && mvn -B test-compile -q`
Expected: BUILD SUCCESS（无 IT 时 `-Pintegration` 下 failsafe 空跑也 SUCCESS）。

- [ ] **Step 6: Commit** `git add -A && git commit -m "p1(build): testcontainers 依赖与 integration profile"`

---

### Task 1: ①高并发线程模型 — 等待可观测 + 池指标 + 容量文档

**Files:**
- Create: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/observability/ModelCallObservability.java`
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/impl/ReActAgent.java:536-580`（invokeModelStreaming 埋点）
- Modify: `aether/aether-app/src/main/java/cn/zcj/aether/config/AetherExecutorRegistry.java`（绑定 ExecutorServiceMetrics + 队列利用率 Gauge）
- Modify: `aether/aether-app/src/main/resources/application.yml`、`application-dev.yml`（tomcat/hikari/池参数 + 注释）
- Create: `aether/docs/capacity-planning.md`
- Test: `aether/aether-domain/src/test/java/cn/zcj/aether/domain/agent/observability/ModelCallObservabilityTest.java`

**Interfaces:**
- Produces: `ModelCallObservability`（`beginWait()`→AutoCloseable、`recordTimeout()`）；Micrometer 指标 `aether.model.waiting.threads`(Gauge)、`aether.model.call.timeouts.total`(Counter)、`aether.executor.queue.utilization`(Gauge, tag pool)；`AetherExecutorRegistry.bindExecutorMetrics(MeterRegistry, ...)`。
- Consumes: `MeterRegistry`（actuator 自动装配）。

**指标定义**（监控 Task 4 的告警直接引用）：
| 指标 | 类型 | 含义 |
|---|---|---|
| `aether.model.waiting.threads` | Gauge | 当前阻塞在 latch.await 的调用线程数 |
| `aether.model.call.timeouts.total` | Counter(tag outcome=timeout) | 模型调用超时率分子 |
| `aether.executor.queue.utilization` | Gauge(tag pool=graph/tool/...) | queued/queueCapacity，>0.8 触发容量告警 |

- [ ] **Step 1: 先写失败测试**（SimpleMeterRegistry 断言 gauge/counter 行为；beginWait 未 close 时 waiting=1，close 后=0；recordTimeout 后 counter+1）
- [ ] **Step 2: 运行确认失败** `mvn -pl aether-domain test -Dtest=ModelCallObservabilityTest`
- [ ] **Step 3: 实现 ModelCallObservability**（AtomicInteger + Gauge.builder；try-with-resources 用法）
- [ ] **Step 4: ReActAgent 接线**：`invokeModelStreaming` 中 `latch.await` 外层包 `try (ModelCallObservability.WaitHandle h = observability.beginWait())`；`!finished` 分支调 `observability.recordTimeout()`。字段注入：`@Autowired(required=false) private ModelCallObservability observability;`（null 安全跳过，保持测试直 new 可用）
- [ ] **Step 5: AetherExecutorRegistry 绑定指标**：每个池 bean 创建后 `micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics.monitor(registry, pool, name, Tags.of("pool", name))`（可选注入 MeterRegistry，null 跳过）；再加 `Gauge.builder("aether.executor.queue.utilization", pool, p -> queued/(double)capacity).tag("pool", name)`——注意 LinkedBlockingQueue 需持有引用（重构 build() 返回记录(pool, queue)）；仅对有界池注册。
- [ ] **Step 6: application.yml 参数面**：

```yaml
server:
  tomcat:
    threads:
      max: 200        # P1-1: SSE 连接承载上限（见 docs/capacity-planning.md 容量公式）
      min-spare: 20
```

dev datasource hikari `maximum-pool-size: 25` 保留并加注释（会话×连接公式）；`aether.thread-pools.graph` 注释补充容量语义。
- [ ] **Step 7: 测试通过 + 全模块编译** `mvn -pl aether-domain test -Dtest=ModelCallObservabilityTest && mvn -B compile -q`
- [ ] **Step 8: docs/capacity-planning.md**：容量公式 `最大并发会话 ≈ min(graphPool.max + queue, tomcat.max/每会话1连接, hikari.max / 每会话DB连接占用)`、轮间阻塞取舍说明（为何不做全异步重构——轮内已流式、轮间阻塞是收益/成本平衡点，附评审路径）、压测联动说明（依赖 P0 压测体系）。
- [ ] **Step 9: Commit** `p1(thread-model): 模型等待可观测+池利用率指标+容量文档`

---

### Task 2: ②分布式限流 — 策略接口 + Redis 令牌桶(Lua) + 降级

**Files:**
- Create: `aether/aether-trigger/src/main/java/cn/zcj/aether/trigger/http/filter/ratelimit/RateLimitProperties.java`
- Create: `.../ratelimit/RateLimiter.java`（接口）、`.../ratelimit/RateLimitDecision.java`（record）
- Create: `.../ratelimit/InMemoryTokenBucketRateLimiter.java`
- Create: `.../ratelimit/RedisTokenBucketRateLimiter.java`
- Create: `.../ratelimit/RateLimitMetrics.java`
- Create: `aether/aether-trigger/src/main/resources/ratelimit/ratelimit-token-bucket.lua`
- Modify: `aether/aether-trigger/src/main/java/cn/zcj/aether/trigger/http/filter/RateLimitFilter.java`（策略路由重构，保留 429 响应格式与 shouldNotFilter）
- Modify: `aether/aether-infrastructure/pom.xml`（+spring-boot-starter-data-redis）
- Modify: `aether/aether-app/src/main/resources/application.yml`、`application-dev.yml`（`aether.security.rate-limit.*` + `spring.data.redis` + `management.health.redis.enabled: false`）
- Create: `aether/data/sql/V5__p1_messaging_and_memory.sql`（首个使用方，后续 Task 追加）
- Test: `.../ratelimit/InMemoryTokenBucketRateLimiterTest.java`、`.../ratelimit/RateLimitFilterTest.java`（MockHttpServletRequestFilterChain）、IT: `.../ratelimit/RedisTokenBucketRateLimiterIT.java`（GenericContainer redis:7-alpine）

**Interfaces:**
- Produces:
```java
public interface RateLimiter {
    RateLimitDecision tryAcquire(String scope, String key, int capacity, int refillPerMinute);
}
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds) {}
```
- Bean 路由：`@ConditionalOnProperty(name="aether.security.rate-limit.mode", havingValue="memory", matchIfMissing=true)` → InMemory；`havingValue="redis"` → Redis。Redis 调用抛异常时过滤器侧降级内存并 `RateLimitMetrics.recordFallback()` + WARN（每次降级 60s 内只 WARN 一次，用时间戳节流）。
- **算法**：令牌桶，key=`aether:ratelimit:{scope}:{key}`；Lua 原子执行"计算补充→扣令牌→写回+TTL→返回(allowed, remaining, retryAfter)"；scope：`login`（`/auth/login` 结尾路径）/`default`。**阈值参数**（可配）：`capacity=100`、`refill-per-minute=100`；login `capacity=5`、`refill-per-minute=5`。
- 指标：`aether.ratelimit.triggered.total`、`aether.ratelimit.fallback.total`、`aether.ratelimit.redis.errors.total`。
- 响应头：`X-RateLimit-Limit`、`X-RateLimit-Remaining`、超限 `Retry-After`。

**Lua 脚本**（KEYS[1]=桶key, ARGV: capacity, refillPerMinute, now(µs), cost=1）：

```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_min = tonumber(ARGV[2])
local now_us = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])
local refill_interval_us = 60000000 / refill_per_min
local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])
if tokens == nil then tokens = capacity; ts = now_us end
if now_us > ts then
  local refill = math.floor((now_us - ts) / refill_interval_us)
  if refill > 0 then
    tokens = math.min(capacity, tokens + refill)
    ts = ts + refill * refill_interval_us
  end
end
local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry_after_ms = math.ceil((cost - tokens) * refill_interval_us / 1000)
end
redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, math.ceil(capacity / refill_per_min * 60000) + 60000)
local remaining = math.floor(tokens)
return {allowed, remaining, math.ceil(retry_after_ms / 1000)}
```

- [ ] **Step 1: 失败测试先行**：`InMemoryTokenBucketRateLimiterTest`——①满容量突发扣尽→拒绝；②拒绝后过 refil 间隔→恢复；③login 与 default scope 隔离；④remaining 语义正确。`RateLimitFilterTest`——超限 429 + 三头 + 指标触发；`mode=memory` 默认。
- [ ] **Step 2: 运行失败** `mvn -pl aether-trigger test -Dtest='InMemoryTokenBucketRateLimiterTest,RateLimitFilterTest'`
- [ ] **Step 3: 实现**接口/决策/内存桶（`ConcurrentHashMap<String, BucketState>`，`synchronized(state)` 桶级锁；桶级 TTL 清理沿用 scheduledPool 1 分钟扫描，复用既有 cleanup 结构）；Redis 实现（`DefaultRedisScript<List>` + `StringRedisTemplate` 可选注入，未装配→抛 `RateLimiterUnavailableException`）；`RateLimitFilter` 重构为策略路由 + 降级（保序 `@Order(HIGHEST_PRECEDENCE + 8)` 不变）。
- [ ] **Step 4: 测试通过**。
- [ ] **Step 5: Redis IT**（`@Tag("integration")`，继承独立 GenericContainer，不依赖 PG）：并发 32 线程 × 50 次扣减 capacity=100 桶 → 成功数恰 100（原子性）；临界突刺用例：容量耗尽后跨 refill 窗口两次请求不超额。CI 中随 `-Pintegration` 跑。
- [ ] **Step 6: 配置落盘**（application.yml）：

```yaml
aether:
  security:
    rate-limit:
      enabled: true
      mode: memory            # P1-2: memory | redis（集群部署切 redis，见 docker-compose-fullstack.yml）
      default-capacity: 100
      default-refill-per-minute: 100
      login-capacity: 5
      login-refill-per-minute: 5
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
management:
  health:
    redis:
      enabled: false   # 默认关闭：未部署 Redis 时 health 不因 redis 项 DOWN；redis 模式部署时用 env 置 true
```
- [ ] **Step 7: V5 迁移文件创建**（本任务先写头部注释 + 限流无 SQL 的说明，Kafka/记忆部分由 Task 3/5 追加）。
- [ ] **Step 8: 全量单测** `mvn -pl aether-trigger,aether-app test -q`（399 基线不回归）
- [ ] **Step 9: Commit** `p1(ratelimit): 令牌桶策略化+Redis Lua 分布式实现+降级`

---

### Task 3: ③消息队列解耦 — Kafka 双链路（审计批量落库 + 会话统计聚合）

**Files:**
- Modify: `aether/aether-trigger/pom.xml`、`aether/aether-app/pom.xml`（+spring-kafka；app 另加 test `spring-kafka-test`）
- Create: `aether/aether-app/src/main/java/cn/zcj/aether/messaging/AuditEventMessage.java`、`.../messaging/AgentEventMessage.java`、`.../messaging/KafkaMessageCodec.java`、`.../messaging/AuditEventProducer.java`、`.../messaging/AgentEventKafkaBridge.java`、`.../messaging/KafkaTopicConfig.java`、`.../messaging/KafkaMessagingProperties.java`
- Modify: `aether/aether-app/src/main/java/cn/zcj/aether/aspect/AuditAspect.java`（Kafka 优先→降级直写）
- Create: `aether/aether-trigger/src/main/java/cn/zcj/aether/trigger/listener/KafkaConsumerConfig.java`、`.../listener/AuditEventConsumer.java`、`.../listener/DashboardStatsConsumer.java`、`.../listener/AgentStatsAggregator.java`
- Create: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/persistence/AuditLogBatchRepository.java`（批量插入 `ON CONFLICT (event_id) DO NOTHING`）、`.../infrastructure/persistence/DashboardStatsRepository.java`（upsert）、`.../infrastructure/persistence/ProcessedEventRepository.java`（`aether_processed_event` 幂等表）
- Modify: `aether/aether-app/src/main/resources/schema.sql`（t_audit_log + `event_id VARCHAR(64)` 列/唯一索引；新表 `dashboard_stats`、`aether_processed_event`）
- Modify: `aether/data/sql/V5__p1_messaging_and_memory.sql`（追加迁移段）
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/DashboardService.java`（可选 stats-source=kafka 读取聚合表）
- Modify: `aether/aether-app/src/main/resources/application.yml`（`aether.kafka.*` + `spring.kafka.*`）
- Test: `.../messaging/AuditEventProducerTest.java`（mock KafkaTemplate：发送成功不降级/发送异常降级 auditExecutor）、`.../messaging/KafkaMessageCodecTest.java`；IT: `aether/aether-app/src/test/java/cn/zcj/aether/messaging/KafkaAuditPipelineIT.java`（EmbeddedKafka + PG 容器：批量落库、重复 event_id 幂等跳过、毒消息→DLT、dashboard_stats 聚合）

**Interfaces:**
- **消息结构**（JSON over StringSerializer，key 保序）：
```java
public record AuditEventMessage(
    String eventId,        // UUID，幂等键 → t_audit_log.event_id
    String action, String resource, String detail,
    Long userId, String username, String ip,
    boolean success, String errorMessage,
    String correlationId, Instant occurredAt) {}

public record AgentEventMessage(
    String eventId,        // 来自 AgentEvent.eventId
    String eventType,      // turn.completed / tool.call.completed / agent.completed
    String agentId, String sessionId, String correlationId,
    int turnNumber, boolean hasToolCalls, int toolCallCount,
    long durationMs, int inputTokens, int outputTokens, double costUsd,
    String toolName, boolean toolSuccess, String status, Instant occurredAt) {}
```
- Topic：`aether.audit.events`（key=userId/anonymous）、`aether.agent.events`（key=sessionId，会话内有序）；DLT：`aether.audit.events.DLT`、`aether.agent.events.DLT`。
- **生产端**：`AuditEventProducer.send(AuditEventMessage)`——`kafkaTemplate.send(topic, key, json).get(2, SECONDS)`；成功→`aether.kafka.produce.success`；任何异常（超时/未装配/未启用）→ 返回 false，调用方降级走既有 `auditExecutor` 直写路径（不丢审计）。`AgentEventKafkaBridge` 在 `@PostConstruct` 订阅 `AgentEventPublisher`（内存总线既有扩展点），将 `TurnCompleted/ToolCallCompleted/AgentCompleted` 转 `AgentEventMessage` 异步发送（独立单线程 executor，队列有界 1000，满了丢弃+计数）。
- **消费端**：`@KafkaListener(containerFactory="batchFactory", ackMode=MANUAL_IMMEDIATE)`；`DefaultErrorHandler`（指数退避 1s×3）+ `DeadLetterPublishingRecoverer`→DLT。**幂等**：审计批写走 `INSERT ... ON CONFLICT (event_id) DO NOTHING`（t_audit_log.event_id 唯一）；统计消费先 `ProcessedEventRepository.markProcessed(eventId)`（`INSERT ... ON CONFLICT DO NOTHING`，返回行数>0 才更新 `dashboard_stats`）。
- `dashboard_stats` 表：`agent_id VARCHAR PK, sessions_total BIGINT, turns_total BIGINT, tool_calls_total BIGINT, tool_errors_total BIGINT, tokens_total BIGINT, cost_usd DOUBLE PRECISION, updated_at TIMESTAMPTZ`。
- 开关：`aether.kafka.enabled=false`（默认；true 时生产/消费 Bean 全装配）；`aether.dashboard.stats-source=live|kafka`（默认 live，行为不变）。

- [ ] **Step 1: 失败测试**：Producer 降级逻辑（mock KafkaTemplate + 计时器）；Codec 往返。
- [ ] **Step 2: 实现 schema（幂等 DDL）**：

```sql
-- P1-3: t_audit_log 增加幂等键
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_event_id ON t_audit_log(event_id);

CREATE TABLE IF NOT EXISTS dashboard_stats (
    agent_id         VARCHAR(128) PRIMARY KEY,
    sessions_total   BIGINT       NOT NULL DEFAULT 0,
    turns_total      BIGINT       NOT NULL DEFAULT 0,
    tool_calls_total BIGINT       NOT NULL DEFAULT 0,
    tool_errors_total BIGINT      NOT NULL DEFAULT 0,
    tokens_total     BIGINT       NOT NULL DEFAULT 0,
    cost_usd         DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS aether_processed_event (
    event_id     VARCHAR(64) PRIMARY KEY,
    event_type   VARCHAR(64),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
- [ ] **Step 3: 实现生产端**（Codec 单例 ObjectMapper + JavaTimeModule；Producer 可选 Bean `@ConditionalOnProperty(aether.kafka.enabled=true)`；AuditAspect 注入 `@Autowired(required=false) AuditEventProducer`——非 null 且 `send` 返回 true 则短路，否则既有路径）。
- [ ] **Step 4: 实现消费端**（batchFactory：`ConsumerBatchAcknowledgingMessageListener` 签名 `void onMessage(List<ConsumerRecord<String,String>> data, Acknowledgment ack)`；审计：解析→`saveBatch`→`ack.acknowledge()`；统计：逐条 dedup→`DashboardStatsRepository.applyDelta`；解析失败/处理异常→抛出交 DefaultErrorHandler 重试→DLT）。
- [ ] **Step 5: 生产端/Codec 测试通过**；`mvn -pl aether-app test -Dtest='AuditEventProducerTest,KafkaMessageCodecTest'`。
- [ ] **Step 6: KafkaAuditPipelineIT**：`@EmbeddedKafka(partitions=1, topics={...})` + PG 容器 + 手工装配 `AuditLogBatchRepository/DashboardStatsRepository/ProcessedEventRepository`（真实 JdbcTemplate 指向容器）；`KafkaTemplate` 发 3 条审计（1 条重复 eventId）→ poll → handler → 断言 t_audit_log 恰 2 行、dashboard_stats turns_total=2、再发坏 JSON → 断言 DLT 收到。
- [ ] **Step 7: 配置落盘**：

```yaml
aether:
  kafka:
    enabled: false            # P1-3: 消息队列解耦总开关（true 需 Kafka broker，见 docker-compose-fullstack.yml）
  dashboard:
    stats-source: live        # live=实时查询(默认) | kafka=读 dashboard_stats 聚合表
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer: { key-serializer: ..., value-serializer: StringSerializer, acks: all }
    consumer: { group-id: aether-consumer, auto-offset-reset: earliest, key/value-serializer: String... }
    listener: { ack-mode: manual_immediate }
```
- [ ] **Step 8: DashboardService 接入**（可选注入 domain 新 port `DashboardStatsStore`，infrastructure 实现 `PgDashboardStatsStore`；stats-source=kafka 时读表，缺行回退 live）。
- [ ] **Step 9: 全量单测回归 + IT 标注**；Commit `p1(mq): Kafka审计/事件双链路+幂等重试DLT+统计聚合`

---

### Task 4: ④监控告警 — Prometheus + Grafana + Alertmanager 全栈

**Files:**
- Create: `aether/docker/docker-compose-fullstack.yml`（aether + postgres + redis + kafka(KRaft) + prometheus + grafana + alertmanager + node-exporter + alert-echo）
- Create: `aether/docker/prometheus/prometheus.yml`、`aether/docker/prometheus/rules.yml`
- Create: `aether/docker/alertmanager/alertmanager.yml`
- Create: `aether/docker/grafana/provisioning/datasources/prometheus.yml`、`.../provisioning/dashboards/dashboards.yml`、`.../dashboards/aether-overview.json`
- Create: `aether/docker/alert-echo/main.py` + `aether/docker/alert-echo/Dockerfile`（FastAPI: 收 webhook 告警→内存环形缓冲→`GET /alerts` 可查——通知通道可实测）
- Create: `aether/docs/monitoring-guide.md`
- 无新 Java（消费 Task 1/2/3 已注册的全部指标）

**指标→告警映射**（rules.yml，7 条真实规则）：
| 规则 | 表达式 | for |
|---|---|---|
| AetherModelCallTimeouts | `increase(aether_model_call_timeouts_total[5m]) > 0` | 5m |
| AetherModelErrorRateHigh | `rate(aether_agent_errors_total[5m]) / clamp_min(rate(aether_agent_turns_total[5m]),0.01) > 0.05` | 5m |
| AetherSessionPersistFailures | `increase(aether_session_persist_failures_total[5m]) > 0` | 5m |
| AetherGraphPoolSaturation | `max by (pool) (aether_executor_queue_utilization{pool="graph"}) > 0.8` | 10m |
| AetherRateLimitSpike | `increase(aether_ratelimit_triggered_total[5m]) > 50` | 5m |
| AetherKafkaConsumerLag | `max by (topic) (kafka_consumer_fetch_manager_records_lag) > 1000` | 10m |
| AetherInstanceDown | `up{job="aether"} == 0` | 1m |

- [ ] **Step 1: prometheus.yml**（scrape: aether:8091/actuator/prometheus 15s、node-exporter 9100、prometheus 自身；rule_files: rules.yml；alerting: alertmanager:9093）
- [ ] **Step 2: rules.yml** 上述 7 条（severity: critical/warning 分级，annotation 带中文 runbook 链接 `docs/monitoring-guide.md#<rule>`）
- [ ] **Step 3: alertmanager.yml**：route 默认 receiver=`ops-webhook`（`http://alert-echo:8080/alerts`）；第二 receiver=`ops-email`（SMTP env 占位注释）；group_by [alertname, severity]，group_wait 30s / repeat_interval 4h
- [ ] **Step 4: Grafana 面板** `aether-overview.json`：两行布局——Agent 行（turn P95 `aether_agent_turn_latency_seconds{quantile="0.95"}`、工具失败率 `rate(aether_agent_tool_errors_total[5m])/rate(aether_agent_tool_calls_total[5m])`、token 成本速率、并发会话=active 线程 gauge）；系统行（JVM heap used/max、Hikari wait `hikaricp_connection_acquire_time_seconds`、`aether_executor_queue_utilization`、限流触发速率、Kafka lag）
- [ ] **Step 5: docker-compose-fullstack.yml**：redis:7-alpine、bitnami/kafka:3.7 KRaft 单节点（KAFKA_CFG_* 自监听 `kafka:9092` + controller）、prom/prometheus、grafana/grafana(匿名只读)、prom/alertmanager、prom/node-exporter、alert-echo(自建镜像)；aether 服务 env：`AETHER_SECURITY_RATE_LIMIT_MODE=redis`、`MANAGEMENT_HEALTH_REDIS_ENABLED=true`、`AETHER_KAFKA_ENABLED=true`、`KAFKA_BOOTSTRAP_SERVERS=kafka:9092`；健康检查。
- [ ] **Step 6: alert-echo**：`POST /alerts`（JSON 存 deque(maxlen=200)）+ `GET /alerts`（Dockerfile: python:3.12-alpine + pip fastapi uvicorn）
- [ ] **Step 7: docs/monitoring-guide.md**：启动命令（`docker compose -f docker/docker-compose-fullstack.yml up -d`）、面板/告警说明表、**实测触发方法**（如 `curl -X POST http://localhost:8091/api/auth/login` 灌满 login 限流→AetherRateLimitSpike / 用 `aether.model.call.timeouts` 人为注入→验证 alert-echo `GET :8081/alerts` 收到）、通知通道扩展说明（邮件/钉钉 webhook 配置示例）。
- [ ] **Step 8: 校验**：`docker compose -f ... config` 语法校验；grep 校验 rules 引用的指标名与 Task1/2/3 代码中注册名逐一对应。
- [ ] **Step 9: Commit** `p1(monitoring): prometheus+grafana+alertmanager 全栈与 7 条告警规则`

---

### Task 5: ⑦记忆机制深化 — 衰减遗忘 + 写入门槛 + 冲突合并

**Files:**
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/MemoryRecord.java`（+`@Builder.Default boolean archived = false`）
- Modify: `aether/aether-domain/.../memory/core/MemoryProperties.java`（嵌套 `Decay`/`WriteGate`/`Conflict` 子段）
- Create: `aether/aether-domain/.../memory/lifecycle/MemoryDecayStore.java`（port）、`.../lifecycle/MemoryDecayJob.java`（scheduledPool 模式）、`.../lifecycle/MemoryDecayMetrics.java`、`.../memory/lifecycle/MemoryWriteGate.java`、`.../memory/lifecycle/MemoryConflictResolver.java`
- Modify: `aether/aether-domain/.../memory/DefaultMemoryFacade.java`（remember 中接入 Gate + Resolver）
- Modify: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgvectorVectorStore.java`（search 过滤 `archived=false`、upsert 复活 `archived=false`、search 后 touch access_count/last_accessed_at、`@PostConstruct ensureLifecycleColumns()`）
- Create: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgMemoryDecayStore.java`（SQL 保留分批归档）
- Modify: `aether/aether-app/src/main/resources/application.yml`（`aether.memory.decay.* / write-gate.* / conflict.*`）
- Modify: `aether/data/sql/V5__p1_messaging_and_memory.sql`（追加 archived 列/索引段）
- Test: `.../memory/lifecycle/MemoryWriteGateTest.java`、`MemoryConflictResolverTest.java`、`MemoryDecayJobTest.java`；IT: `aether/aether-infrastructure/.../PgMemoryLifecycleIT.java`（真实 PG：归档→检索不现→再次 upsert 复活）；`aether/aether-domain/.../MemoryLifecycleEvalTest.java`（确定性 20 轮跨会话场景：记得住/忘得掉/不打架）
- Create: `aether/docs/memory-lifecycle.md`

**Interfaces:**
- **保留分公式**（SQL 内计算，批处理）：`retention = 0.5*recency + 0.3*frequency + 0.2*importance`；`recency = GREATEST(0, 1 - EXTRACT(EPOCH FROM (NOW()-last_accessed_at)) / (half_life_days*86400.0))`（半衰线性）；`frequency = LEAST(1.0, access_count / 10.0)`。`retention < min_retention_score(0.2)` → `UPDATE aether_memories SET archived=true WHERE id IN (...)`（每批 200，`interval-minutes=60`，`decay.enabled=true`）。
- Port：
```java
public interface MemoryDecayStore {
    int archiveStale(double halfLifeDays, double minRetentionScore, int batchSize);
    long countActive(); long countArchived();
}
```
- **写入门槛**：`MemoryWriteGate.shouldAccept(EncodingFlow.EncodeResult encoded, boolean llmAvailable)`——`enabled && llmAvailable && llmSuccess && importance < minImportance(0.6)` → 拒绝（默认重要性 0.5 仅在无 LLM 时出现，不拦）。拒返 synth record（不落库，metadata `gate=rejected`），计数 `aether.memory.write.rejected.total` / `aether.memory.write.accepted.total`。
- **冲突合并**：`MemoryConflictResolver.resolve(oldRec, newContent, newImportance)` → `MergeOutcome(strategy, content, importance)`；策略 `aether.memory.conflict.strategy=concat|new-wins|llm`（默认 concat 保旧行为；llm：prompt 输出 `{action: merge|replace, content}`，失败/无 ChatModel 回退 concat）；指标 `aether.memory.conflict.resolved.total{strategy}`。
- 指标：`aether.memory.decay.archived.total`、`aether.memory.decay.active.count`、`aether.memory.decay.archived.count`、`aether.memory.decay.slim.ratio`(=archived/total)。
- 自愈 DDL（PgvectorVectorStore @PostConstruct 与 PgMemoryDecayStore 共用工具方法）：`ALTER TABLE aether_memories ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT false; CREATE INDEX IF NOT EXISTS idx_memories_active ON aether_memories(archived, last_accessed_at DESC);`

- [ ] **Step 1: 失败测试**（Gate/Resolver/Job 三个纯单测，fake store + mock ChatModel）。
- [ ] **Step 2: 运行失败** `mvn -pl aether-domain test -Dtest='Memory*Test'`
- [ ] **Step 3: 实现 domain 三件套 + MemoryRecord.archived + Properties 子段；DefaultMemoryFacade.remember 接线**（encode 后过 Gate；merge 分支改调 Resolver）。
- [ ] **Step 4: PgvectorVectorStore/PG 实现**（archived 过滤进 `buildCosineSearchSql` 的 WHERE——既有 `PgvectorVectorStoreSqlTest` 需同步更新断言；touch SQL `UPDATE aether_memories SET access_count=access_count+1, last_accessed_at=NOW() WHERE id = ANY(?::text[])` best-effort）。
- [ ] **Step 5: 测试通过 + infra 编译**。
- [ ] **Step 6: PgMemoryLifecycleIT**（继承 AbstractPgIT）：写 2 条（旧低分/新高分）→ 手动 `archiveStale(0, 0.99, 10)` → search 不含旧条 → upsert 同 id → archived=false 复活。
- [ ] **Step 7: MemoryLifecycleEvalTest**（不依赖网络：FakeVectorStore + 固定时钟注入；断言：重要记忆跨"会话"可召回、低分记忆被衰减归档、偏好更新后新胜旧）。注意 Job/Resolver 的 `Instant.now()` 需可注入 `Clock`。
- [ ] **Step 8: 配置落盘 + V5 追加**；`mvn -pl aether-domain,aether-infrastructure test -q` 全绿。
- [ ] **Step 9: docs/memory-lifecycle.md**（公式、配置、评测口径、瘦身率指标）。Commit `p1(memory): 衰减遗忘+写入门槛+冲突合并+生命周期评测`

---

### Task 6: ⑥RAG 三级检索管道 — 改写 → 混合召回(RRF) → 重排

**Files:**
- Create: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/retrieval/rag/RagProperties.java`、`RetrievalDocument.java`、`HybridSearchPort.java`、`RerankPort.java`、`QueryRewriter.java`、`RetrievalPipeline.java`、`CjkBigram.java`
- Modify: `aether/aether-domain/.../memory/RecallFlow.java`（可选注入 RetrievalPipeline，`aether.rag.enabled=true` 时 shallow/deep 走管道，异常回退原路径）
- Create: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgHybridSearchRepository.java`（RRF SQL + `ensureFtsColumns()` 建 content_bigram 列/GIN 表达式索引）
- Modify: `aether/aether-infrastructure/.../repository/PgvectorVectorStore.java`（upsert 时写 `content_bigram = CjkBigram.bigram(content)`）
- Modify: `aether/aether-domain/.../tool/python/PythonServicePort.java`（+`JsonNode rerank(String query, java.util.List<String> documents)`）
- Modify: `aether/aether-infrastructure/.../python/PythonServiceClient.java`（实现 rerank → POST `doc-url`/rerank）
- Modify: `aether/aether-python-services/document-service/main.py`（+`/rerank`：RERANK_MODEL 环境变量可选 CrossEncoder，缺省启发式 bigram 重叠打分——零依赖可运行）
- Create: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/repository/PythonReranker.java`（implements RerankPort，超时/异常回退原序）
- Modify: `aether/aether-app/src/main/resources/application.yml`（`aether.rag.*`）
- Test: `CjkBigramTest`、`QueryRewriterTest`、`RetrievalPipelineTest`（fake ports：三级全开/降级链/超时切换）、`aether/aether-infrastructure/.../PgHybridSearchRepositoryIT.java`（中文语义+词法命中、RRF 融合排序）、`RetrievalEvalTest`（确定性：hash 向量语料，Recall@5/MRR 三模式对比断言 hybrid≥vector）
- Create: `aether/docs/rag-pipeline.md`

**Interfaces:**
```java
public record RetrievalDocument(String id, String content, double score, String source) {}
public interface HybridSearchPort {
    List<RetrievalDocument> search(float[] queryVector, String lexicalQuery, int topK,
                                   List<MemoryScope> scopes);
}
public interface RerankPort {
    List<RetrievalDocument> rerank(String query, List<RetrievalDocument> candidates, int topN);
}
```
- `RetrievalPipeline.retrieve(String query, List<MemoryScope> scopes, int topK)` → `RetrievalResult(List<RetrievalDocument> docs, StageTrace trace)`；**切换条件**（逐级降级）：rewrite 未启用/超时(800ms)/异常 → 原 query；hybrid 未启用/异常 → 纯向量（VectorStore.search 顶替）；rerank 未启用/超时(800ms)/Python 不可达 → RRF 序直接截断。trace 记录每级是否生效+耗时（`aether.rag.stage.duration` Timer tag stage）。
- **RRF SQL**（全部单 SQL，k=60）：
```sql
WITH semantic AS (
  SELECT id, content, scope_path, scope_private, importance, source,
         created_at, last_accessed_at, access_count,
         ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rank
  FROM aether_memories
  WHERE embedding IS NOT NULL AND archived = false {scopeFilter}
  ORDER BY embedding <=> ?::vector LIMIT ?),
lexical AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(to_tsvector('simple', content_bigram),
         plainto_tsquery('simple', ?)) DESC) AS rank
  FROM aether_memories
  WHERE content_bigram IS NOT NULL AND archived = false {scopeFilter}
    AND to_tsvector('simple', content_bigram) @@ plainto_tsquery('simple', ?)
  ORDER BY ts_rank(to_tsvector('simple', content_bigram), plainto_tsquery('simple', ?)) DESC LIMIT ?)
SELECT COALESCE(s.id, l.id) AS id, s.content, s.scope_path, s.scope_private, s.importance, s.source,
       s.created_at, s.last_accessed_at, s.access_count,
       COALESCE(1.0/(60 + s.rank), 0) + COALESCE(1.0/(60 + l.rank), 0) AS rrf_score
FROM semantic s FULL OUTER JOIN lexical l ON s.id = l.id
ORDER BY rrf_score DESC LIMIT ?
```
  占位符序：`queryVec, [scope×2 语义], queryVec, vecLimit, lexQuery, [scope×2 词法], lexQuery, lexQuery, lexLimit, finalLimit`。中文词法：Java 侧 `CjkBigram.bigram()` 生成 2-gram 空格分隔文本，写入列 `content_bigram TEXT` + `CREATE INDEX ... USING gin (to_tsvector('simple', content_bigram))`（查询侧对 query 同样 bigram——零扩展组件支持中文）。
- **配置**（默认关，行为零变化）：
```yaml
aether:
  rag:
    enabled: false
    rewrite: { enabled: false, timeout-ms: 800 }   # 一级：LLM 改写+2 变体
    hybrid:  { enabled: true, vector-top-k: 30, lexical-top-k: 30, candidate-limit: 50 }  # 二级
    rerank:  { enabled: false, timeout-ms: 800, top-n: 10 }  # 三级：Python /rerank
```
- [ ] **Step 1: 失败测试**：CjkBigram（中/英/混合）；QueryRewriter（mock ChatModel 正常/异常回退）；Pipeline 三级 fake（trace + 降级）。
- [ ] **Step 2: domain 实现**（Pipeline 无 ChatModel/端口时逐级回退——纯 domain 可测）。
- [ ] **Step 3: 测试通过** `mvn -pl aether-domain test -Dtest='*Rag*,CjkBigramTest,RetrievalPipelineTest,QueryRewriterTest'`
- [ ] **Step 4: infra RRF 仓储 + PgvectorVectorStore 写 bigram 列 + PythonServicePort.rerank + PythonReranker（MockRestServiceServer 单测超时回退）**。
- [ ] **Step 5: PgHybridSearchRepositoryIT**（AbstractPgIT）：插入 5 条中文记忆（含向量）→ 语义 top1 命中近义句、词法命中独有词、RRF 双命中排序最高；`ensureFtsColumns` 索引存在断言（pg_indexes 查 GIN）。
- [ ] **Step 6: document-service /rerank**（启发式 bigram-F1 打分 + 可选模型注释），`RetrievalEvalTest` 确定性评测（Recall@5 / MRR 表格输出到 stdout，断言 hybrid ≥ pure-vector-0.0 降幅不超 & rerank 序稳定）。
- [ ] **Step 7: RecallFlow 接线 + 配置落盘 + V5 追加 bigram 段**；`mvn -B test -q` 全量回归。
- [ ] **Step 8: docs/rag-pipeline.md**（每级策略/数据流/切换条件/延迟预算/评测表 + 30-query 标注集路径 `aether-app/src/test/resources/rag/queries.jsonl` 供真实模式评测）。Commit `p1(rag): 三级检索管道(改写/RRF混合/重排)`

---

### Task 7: 交付收尾 — 汇总文档 + README + 全量验证

**Files:**
- Create: `aether/docs/p1-roadmap-delivery.md`（七项端到端设计+启动说明+验收对照表）
- Modify: `aether/README.md`（P1 能力速览段：限流双模式/Kafka/监控一键起/IT 跑法）
- Modify: `aether/data/sql/V5__p1_messaging_and_memory.sql`（终稿核对）

- [ ] **Step 1: 全量单测**：`mvn -B test -q` → 0 failures（对比基线 399，只增不减）。
- [ ] **Step 2: 本机有 Docker 则** `mvn -B verify -Pintegration -q` 验证 IT；无 Docker 记录 CI 执行路径。
- [ ] **Step 3: compose 校验** `docker compose -f aether/docker/docker-compose-fullstack.yml config -q`。
- [ ] **Step 4: 交付文档**（含每项：设计要点/关键参数表/启动说明/验证命令）。
- [ ] **Step 5: Commit** `p1(docs): P1 交付文档与 README`。

---

## Self-Review 记录

- 规格覆盖：1.1→Task1；1.2→Task2；1.3→Task3；2.2→Task0+各 IT；3.3→Task4；4.2→Task6；4.3→Task5。✔
- 类型一致性：`RateLimitDecision`/`RetrievalDocument`/`MemoryDecayStore` 签名在各 Task 间已对齐；指标名清单 Task1/2/3 与 Task4 规则一一对应。✔
- 回归安全：所有新 Bean 默认关闭或可选注入；`buildCosineSearchSql` 断言更新列入 Task5 Step4。✔

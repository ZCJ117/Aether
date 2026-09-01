# Aether 监控告警指南（P1-3.3）

> 闭环：**埋点（Micrometer）→ 抓取（Prometheus）→ 面板（Grafana）→ 告警（Alertmanager）→ 通知（webhook/邮件）**。
> 一键起栈：`docker compose -f docker/docker-compose-fullstack.yml up -d`。

## 1. 快速开始

```bash
cd aether
# .env 需要：JASYPT_MASTER_PASSWORD / DB_PASSWORD / JWT_SECRET / ZHIPU_API_KEY（可选）
docker compose -f docker/docker-compose-fullstack.yml up -d

# 服务就绪后
open http://localhost:3000        # Grafana（Aether/Aether Agent 总览 面板，匿名 Viewer 可看）
open http://localhost:9090        # Prometheus（查看抓取状态/规则）
open http://localhost:9093        # Alertmanager
curl http://localhost:8081/health # alert-echo（通知通道回显服务）
```

## 2. 指标清单（埋点来源）

| Prometheus 指标 | 类型 | 代码来源 |
|---|---|---|
| `aether_agent_turn_latency_seconds{quantile}` | Timer | `AgentMetrics`（P0 既有） |
| `aether_agent_errors_total` / `aether_agent_turns_total` | Counter | `AgentMetrics` |
| `aether_agent_tool_calls_total` / `aether_agent_tool_errors_total` | Counter | `AgentMetrics` |
| `aether_agent_tokens_input_total` / `aether_agent_tokens_output_total` | Counter | `AgentMetrics` |
| `aether_session_persist_failures_total` | Counter | `SessionPersistenceMetrics` |
| `aether_model_waiting_threads` | Gauge | P1-1.1 `ModelCallObservability` |
| `aether_model_call_timeouts_total` | Counter | P1-1.1 `ModelCallObservability` |
| `aether_executor_queue_utilization{pool}` | Gauge | P1-1.1 `AetherExecutorRegistry` |
| `aether_ratelimit_triggered_total{scope}` | Counter | P1-1.2 `RateLimitMetrics` |
| `aether_ratelimit_fallback_total` / `aether_ratelimit_redis_errors_total` | Counter | P1-1.2 降级打点 |
| `aether_kafka_produce_total{topic,outcome}` | Counter | P1-1.3 `AuditEventProducer` |
| `kafka_consumer_fetch_manager_records_lag` | Gauge | P1-1.3 `MicrometerConsumerListener` |
| `hikaricp_*` / `tomcat_*` / `jvm_*` | — | Micrometer 自动绑定 |

## 3. 告警规则（docker/prometheus/rules.yml）

| 规则 | 触发条件 | 级别 | 处置入口 |
|---|---|---|---|
| `AetherModelCallTimeouts` | 5min 内超时 >0，持续 5m | warning | §4.1 |
| `AetherModelErrorRateHigh` | 错误率 >5%，持续 5m | critical | §4.2 |
| `AetherSessionPersistFailures` | 5min 内失败 >0，持续 5m | critical | §4.3 |
| `AetherGraphPoolSaturation` | graph 队列利用率 >80%，持续 10m | warning | §4.4 |
| `AetherRateLimitSpike` | 5min 内 429 >50 | warning | §4.5 |
| `AetherKafkaConsumerLag` | lag >1000，持续 10m | warning | §4.6 |
| `AetherInstanceDown` | up==0，持续 1m | critical | §4.7 |

抑制规则：`AetherInstanceDown` 激活时抑制该实例全部业务告警（根因优先）。

## 4. 告警响应手册

### 4.1 AetherModelCallTimeouts
1. Grafana 确认 `aether_agent_model_call_latency_seconds` P99 是否同步抬升；
2. 是 → LLM Provider 故障/网络劣化，检查 fallback 分支 `aether_model_recovery_branch_total`；
3. 否（超时但延迟正常）→ `aether.model.invoker.call-timeout-ms` 配置过小。

### 4.2 AetherModelErrorRateHigh
1. 看 `aether_agent_errors_total` 突增时间点对应日志（`logs/aether-agent.json`，按 correlationId join 审计）；
2. 确认 Provider 侧 4xx/5xx；fallback 链耗尽时错误直出。
3. 复盘 fallback 链长度与冷却参数（`ResilientChatModelExecutor`）。

### 4.3 AetherSessionPersistFailures
1. `PG: SELECT 1` 连通性；`hikaricp_connections_pending` 是否 >0（连接池耗尽）；
2. 连接池耗尽 → 调 `spring.datasource.hikari.maximum-pool-size`（容量公式 docs/capacity-planning.md）；
3. PG 磁盘/锁等待 → `pg_stat_activity` 排查。

### 4.4 AetherGraphPoolSaturation
1. 容量公式：`maxConcurrent ≈ min(graphPool.max+queue, tomcat.max, hikari.max)`；
2. 短期：调大 `aether.thread-pools.pools.graph.queue-capacity`；中期：横向扩实例（限流/会话已具备多实例条件）；
3. 若 `aether_model_waiting_threads` ≈ active 线程 → 瓶颈在模型延迟，扩容编排线程无效。

### 4.5 AetherRateLimitSpike
1. 按 `scope` 维度区分：login 突增 = 疑似暴力破解；default 突增 = 爬虫/攻击；
2. 结合网关日志按 X-Forwarded-For 聚合 Top IP，必要时前置 WAF；
3. 若为业务正常峰值 → 调 `aether.security.rate-limit.*-capacity`。

### 4.6 AetherKafkaConsumerLag
1. `curl :8091/actuator/health` 确认消费端实例存活；
2. lag 持续增长 = 消费吞吐不足：检查 DB 写入延迟（审计批量插入）、并发度（batchFactory concurrency）；
3. 消费者宕机 → 重启后从上次提交位点恢复（手动 ack 语义，不丢消息）。

### 4.7 AetherInstanceDown
1. `docker ps` / 容器日志；healthcheck 40s start_period 内属正常；
2. 长时间 DOWN：检查启动日志与依赖（PG/Redis/Kafka）健康。

## 5. 通知通道

- **默认（演示/联调）**：webhook → alert-echo（本仓 `docker/alert-echo/`）。
  验证触达：`curl http://localhost:8081/alerts | jq`（返回最近 200 条告警记录）。
- **生产**：`docker/alertmanager/alertmanager.yml` 内含邮件 SMTP 与钉钉机器人（prometheus-webhook-dingtalk）注释模板，放开并注入凭据即可。

## 6. 实测演练（验证告警链路真实可用）

```bash
# ① 制造限流告警：灌满 login 桶（capacity=5）
for i in $(seq 1 200); do curl -s -o /dev/null -X POST http://localhost:8091/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'; done
# ② 等 Prometheus 评估（15s 抓取 + 5m for）后，验证通知通道：
curl -s http://localhost:8081/alerts | jq '.alerts[].alertname'
# ③ 手动单元验证（不等规则评估）：向 Alertmanager 直接注入测试告警
amtool --alertmanager.url=http://localhost:9093 alert add AetherInstanceDown job=aether severity=critical
```

## 7. 面板截图

面板 JSON：`docker/grafana/dashboards/aether-overview.json`（12 面板：Agent 行 + 系统行）。
截图占位：部署后按 §1 打开 Grafana → 导出 PNG 入 README（CI 环境无法截取运行态画面）。

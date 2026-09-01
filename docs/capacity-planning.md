# Aether 容量规划 —— 线程模型与并发上限（P1-1.1）

> 目标：把"单实例并发长会话上限"从拍脑袋变成**有公式、有配置、有指标、有调优记录**的工程问题。
> 压测数字（并发拐点、P95）由 P0 压测体系（`docs/benchmark-report.md`）补充，本文给出模型与操作方法。

## 1. 线程模型现状

| 环节 | 承载者 | 单会话占用 | 说明 |
|---|---|---|---|
| HTTP/SSE 连接 | Tomcat（`server.tomcat.threads.max`，默认改 200） | 1 线程/连接（推送期阻塞） | 连接数上限 `max-connections: 8192` |
| Agent 编排主循环 | `graphPool`（core 4 / max 8 / queue 200，`CallerRunsPolicy`） | **1 线程/会话，整会话生命周期持有** | ReAct 主循环"轮间阻塞"：`ReActAgent.invokeModelStreaming` 内 `CountDownLatch.await(callTimeoutMs)`（默认 120s/轮） |
| 工具执行 | `toolPool`（4/16/200） | 峰值 1 线程/轮 | 与 graphPool 并行面分离 |
| 会话落库 | `sessionPersistPool`（2/4/1000） | 短任务 | 失败计入 `aether.session.persist.failures` |
| 记忆 IO | `memoryIoPool`（2/4/500） | 短任务 | 向量检索/回填 |
| 模型流等待 | graphPool 线程内部阻塞 | 计入上行 | Gauge `aether.model.waiting.threads` 实时显示被模型等待占用的线程数 |

**关键结论**：单实例并发会话上限 ≈ `graphPool.max + graphPool.queue`（默认 8+200），但*可用*上限受
`CallerRunsPolicy` 影响——队列满后任务回落到 Tomcat 线程执行，表现为**整体变慢而非立刻报错**，
这就是必须配置容量告警（`aether.executor.queue.utilization{pool="graph"} > 0.8`）而非依赖拒绝异常的原因。

## 2. 容量公式

```
MaxConcurrentSessions ≈ min(
    graphPool.max + graphPool.queue,                 # 编排容量（默认 208，告警线 0.8×）
    server.tomcat.threads.max,                       # HTTP/SSE 承载（默认 200）
    hikari.maximum-pool-size / perSessionDbConns     # DB 连接（默认 25；perSessionDbConns≈1，持久化池另计）
)
```

- **每会话线程占用**：1 个 graphPool 线程整会话持有（数十轮 × 每轮 30-60s 模型耗时，单会话可占线程数十分钟）。
- **每会话 DB 连接占用**：稳态 ≈0（异步落库瞬时借还）；压缩/记忆回填时 +1（memoryIoPool 有界，不放大）。
- 推荐配比：`tomcat.threads.max ≥ 4 × graphPool.max`（SSE 推送与编排解耦余量）。

## 3. 调优路径

| 目标 | 配置键 | 默认 | 建议 |
|---|---|---|---|
| 提高编排容量 | `aether.thread-pools.pools.graph.max-pool-size` / `queue-capacity` | 8 / 200 | 会话数 >150 时先升 queue，再升 max（线程多 = 内存高 + 上下文切换）；键必须含 `pools` 层级（Map 绑定） |
| 提高承载 | `server.tomcat.threads.max` | 200 | 除非 SSE 推送线程饥饿，否则不动 |
| 提高落库吞吐 | `spring.datasource.hikari.maximum-pool-size` | 25 | 与 PG `max_connections`（默认 100）联动，多实例部署时除以实例数 |
| 缩短线程占用 | `aether.model.invoker.call-timeout-ms` | 120000 | 模型故障时该预算决定线程被"白占"多久；超时率指标 `aether.model.call.timeouts.total` |

### 指标 → 判断

- `aether_model_waiting_threads`：模型等待占用的线程数。长期 ≈ `graphPool` 活跃数 ⇒ 瓶颈在模型延迟而非编排；
- `aether_executor_queue_utilization{pool="graph"}`：>0.8 持续 10min ⇒ 容量告警（Alertmanager 规则 `AetherGraphPoolSaturation`）；
- `executor_active_threads{pool="graph"}` / `executor_queued_tasks{pool="graph"}`：Micrometer `ExecutorServiceMetrics` 自动暴露。

## 4. 架构取舍：为何不做"轮间全异步化"

现状"轮间阻塞"（每轮 `latch.await` 占一个调用线程）是**有意的收益/成本平衡**：

1. **轮内已真流式**：`aether.model.invoker.true-streaming=true` 下 token 边收边推，用户可感知延迟已是流式水平；
2. 全异步化（`Mono` 链 + 递归代替 `while`）要把 `queryLoop` 的状态机、中断、压缩、检查点全部重写为响应式——
   1054 行 `GraphExecutor` + 928 行 `ReActAgent` 的重构风险远高于"加线程/加实例"的横向成本；
3. 容量不足时正确动作是**先调 `graphPool` 参数，再横向扩实例**（限流/会话恢复已具备多实例条件，见 P1-1.2 与 checkpoint 机制）。

重估触发条件：压测显示 `graphPool` 扩到 ≥32 线程仍饱和，或单实例内存成为主导成本时，再立项全异步改造。

## 5. 虚拟线程（Java 21）评估

项目编译目标 Java 17。升级 21 后 `spring.threads.virtual.enabled=true` 可让 Tomcat 承载层受益（SSE 阻塞不再占平台线程），
但 `graphPool` 的瓶颈是"每会话一个长生命周期任务"，虚拟线程不减少任务数、只降低线程内存成本——收益中等，
且需回归验证所有 `synchronized` 密集段（pinning 风险）。列为 P2 候选，非本期范围。

## 6. 验证方法（联动 P0 压测）

1. `docker compose -f docker/docker-compose-fullstack.yml up -d`（含 mock-llm 的 bench 栈见 `docker/docker-compose-bench.yml`）；
2. k6 阶梯并发 10/30/50/100 会话 × 10 轮（压测脚本随 P0 压测体系交付）；
3. Grafana 面板观察：`graphPool` 利用率爬升 →拐点、`waiting.threads` 占比、`hikaricp_connection_pending`；
4. 把拐点数字回填本文与 `docs/benchmark-report.md`。

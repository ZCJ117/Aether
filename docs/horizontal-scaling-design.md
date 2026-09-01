# 水平扩展与有状态治理设计（P2-1.4）

## 1. 目标

Aether 需要从 docker-compose 单实例演进为可多实例部署。本文档回答两个问题：

1. 会话和 LLM 缓存状态能否共享？
2. 流量如何路由，故障实例如何恢复？

## 2. 方案对比与选型

| 方案 | 说明 | 优点 | 局限 | 结论 |
| --- | --- | --- | --- | --- |
| 会话粘性 | Nginx/网关按 `X-Session-Id` 一致性哈希，同一会话固定到一个实例 | 改造成本最低，JVM 内热数据天然有效 | 单实例故障后历史仍依赖持久化恢复；不解决单点状态 | **短期方案**，作为多实例入口 |
| 状态外置 | 会话状态继续用 PostgreSQL（当前生产默认），LLM 响应使用 Caffeine L1 + Redis L2 | 任意实例都能恢复；Redis 缺失时自动退回 L1 | 引入 Redis 可用性与序列化边界 | **长期方案**，本次已实现 |
| 全量 Agent 状态进 Redis | 把 graph/mailbox/中间状态都搬到 Redis | 单实例可任意中断 | 现有 checkpoint/会话快照已覆盖大部分恢复能力，改造成本高 | 暂不做，保持演进路径 |

### 选型结论

当前生产继续以 PostgreSQL 为 `aether.session.store=postgres`，Redis 只承载两类共享只读/可重建数据：

- LLM 响应缓存：Caffeine L1 + Redis L2，`aether.cache.llm.redis-enabled=true`；
- 分布式限流：沿用 P1 Redis 令牌桶。

`aether.session.store=redis` 现在补齐完整接口，可作为较小部署或快速会话列表/统计的备选存储，但生产主路径仍建议 PostgreSQL。

## 3. 已实现的数据结构

### 3.1 LLM 缓存

- L1：`ModelCallCache` 内 Caffeine，`maximumSize=1000`，TTL 60 秒，LRU 淘汰；
- L2：`RedisModelCacheStore`，键 `aether:llm-cache:<modelName>:<hash>`；
- L2 值是 `ModelCacheSnapshot` JSON，包含 `fullText`、token、成本、tool calls 和 runtime events；
- L1 未命中读 L2，L2 命中回填 L1；
- L2 读取/写入异常只打 WARN，不影响模型调用；坏值删除后当作未命中；
- 错误结果不进入两级缓存。

### 3.2 Redis 会话仓储

- 会话值：`aether:session:data:<sessionId>`，JSON，TTL 7 天；
- 用户索引：`aether:session:user:<userId>` Hash，field=sessionId，value=agentId；
- Agent 索引：`aether:session:agent:<agentId>` Hash，field=sessionId，value=userId；
- 活跃集合：`aether:session:active` Set；
- 归档/非 ACTIVE 会话从 active Set 移除；删除时同步移除三条索引；
- 读取时跳过过期索引项，避免 Redis TTL 与 Hash 索引生命周期不一致导致“幽灵会话”。

## 4. 部署与流量入口

`docker/docker-compose-scale.yml` 提供双实例演示：

- `aether-1`、`aether-2` 共享 PostgreSQL 和 Redis；
- Nginx 使用一致性哈希 `hash $http_x_session_id consistent`；
- SSE 关闭代理缓冲，读/写超时提升到 3600 秒；
- 两实例均开启 Redis L2 和 Redis 限流。

启动：

```bash
cd aether
docker compose -f docker/docker-compose-scale.yml up -d
curl -s -H "X-Session-Id: demo-session" http://localhost:8090/actuator/health
```

请求必须携带稳定的 `X-Session-Id`；没有该头的一致性哈希退化为空 key，仍可负载均衡，但粘性效果不可用。

## 5. 演进路径

1. 现状：PostgreSQL 会话快照 + 可选 Redis 缓存/限流 + 一致性哈希入口；
2. 下一阶段：把 graph 级 mailbox/trace 摘要下沉 Redis，减少节点故障后的重建窗口；
3. 最终状态：Agent 中间状态外置，网关粘性只作性能优化，不再作为正确性依赖。

## 6. 验收对照

- LLM 缓存不再单机单点：见 `ModelCallCache`、`RedisModelCacheStore`；
- 会话列表/统计在 Redis 模式不再 `UnsupportedOperationException`：见 `RedisSessionRepository`；
- 任意实例可恢复：生产仍由 PostgreSQL checkpoint/session 快照保障，Redis 仅加速；
- 多实例演示链路：见 `docker/docker-compose-scale.yml`。
- Redis 会话写入将 session/user/agent/active 操作放入同一 pipeline，失败通过
  `CompletableFuture` 传播并计入持久化失败指标；Redis Cluster 跨槽场景下不能把
  多个业务 key 组成 Lua 事务，PostgreSQL 仍是生产会话的事实源；
- 单实例/双实例线性度压测：见 `docs/scaling-benchmark.md` 与
  `scripts/scaling-benchmark.py`。

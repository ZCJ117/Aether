# 日志排障手册（P2-3.4）

本手册回答一个固定问题：**一次 Agent / 工具调用失败，如何从原始 trace 定位到具体会话、节点、工具和审计记录？**

## 0. 日志文件与字段

| 位置 | 内容 | 关键字段 |
| --- | --- | --- |
| `logs/aether-agent.json` | 结构化 Agent 日志（首选入口） | `@timestamp`, `level`, `logger_name`, `message`, `sessionId`, `graphExecutionId`, `correlationId`, `userId`, `agentId` |
| `./data/log/log_info.log` | 滚动普通日志 | `correlationId`（若在日志文本中出现）、`sessionId` |
| `./data/log/log_error.log` | WARN/ERROR | 同上 |
| PostgreSQL `t_audit_log` | `@Auditable` 方法审计 | `username`, `action`, `resource`, `detail`, `success`, `error_message`, `created_at` |
| OpenTelemetry / Grafana Tempo | 调用链 trace | `trace_id`, `span_id`, service `aether-agent` |

> 生产环境已将 `cn.zcj.aether.domain.agent` 收紧到 INFO；开发环境保留 DEBUG。JSON 日志始终包含 `graphExecutionId` 和 `sessionId`。

## 1. 第一步：从响应/告警拿到关联 ID

优先级如下：

1. HTTP 响应头 `X-Correlation-Id` —— `MdcFilter` 生成并写回；
2. 前端 SSE 错误事件 / API 返回中的 `correlationId`；
3. Prometheus 告警中的 `sessionId`、`agentId`、模型或工具标签；
4. OTel trace 中的 `trace_id`。

如果只有 `sessionId`，也可作为起点，但排障前建议统一携带：

```bash
curl -H "X-Correlation-Id: corr-20260901-001" \
     -H "X-Session-Id: sess-20260901-001" \
     http://localhost:8091/api/agent/chat
```

## 2. 第二步：用 MDC 缩小时间线和失败节点

PowerShell 查询 JSON 中该 correlationId 的 ERROR/WARN：

```powershell
Select-String -Path .\logs\aether-agent.json -Pattern 'corr-20260901-001' |
  ForEach-Object { $_.Line | ConvertFrom-Json } |
  Where-Object { $_.level -in @('WARN','ERROR') } |
  Select-Object @timestamp, level, logger_name, message,
    sessionId, graphExecutionId, agentId
```

Linux/macOS：

```bash
grep 'corr-20260901-001' logs/aether-agent.json \
  | jq -r 'select(.level=="ERROR" or .level=="WARN") |
      [.["@timestamp"],.level,.logger_name,.message,.sessionId,.graphExecutionId,.agentId] | @tsv'
```

判断规则：

- 有 `graphExecutionId`：失败发生在多 Agent 图路径，继续查 `GraphExecutionRecorder` / graph trace；
- 只有 `agentId + sessionId`：失败在单 Agent ReAct/PlanAct 执行；
- `logger_name` 包含 `tool`：进入工具失败路径；
- `message` 中包含 HTTP 状态、模型名或工具名，先记录下来用于第三步过滤。

## 3. 第三步：从审计日志确认“谁、什么动作、是否成功”

审计 `detail` 会拼接 `correlationId=...`，因此可用数据库查询关联：

```sql
SELECT created_at, username, action, resource, success,
       error_message, detail
FROM t_audit_log
WHERE detail LIKE '%corr-20260901-001%'
   OR created_at BETWEEN '2026-09-01 10:00:00+08' AND '2026-09-01 10:05:00+08'
ORDER BY created_at DESC;
```

Kafka 模式下可同时确认消费链路是否滞后：

```bash
docker exec -it aether-kafka /opt/bitnami/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic aether.audit.events \
  --from-beginning --timeout-ms 5000 \
  | grep 'corr-20260901-001'
```

若日志有 `@Auditable` 方法失败但 `t_audit_log` 没有记录：

1. 查 `logs/aether-agent.json` 中 `AuditEventConsumer` / audit pool WARN；
2. 查 Kafka DLT `aether.audit.events.DLT`；
3. 查 PostgreSQL 连接池 / `aether_session_persist_failures` 指标。

## 4. 常见工具失败定位模板

### 4.1 工具执行异常

1. 在 JSON 中按 `correlationId` 过滤 `ToolExecutor` / `toolResult`；
2. 记录 `toolName`、`toolCallId`、`toolInput`；
3. 用 `sessionId + toolCallId` 查完整上下文；
4. 若工具触发审计，进入 `t_audit_log` 查 `resource/toolName` 与 `error_message`；
5. 最后在 OTel 中打开同一 `trace_id`，确认耗时集中在模型、网络还是工具执行。

### 4.2 图执行节点失败

1. 用 `graphExecutionId` 过滤 JSON，按时间排序查看节点 `RUNNING -> FAILED`；
2. 查 `GraphExecutionRecorder` 录制的 node event 和错误文本；
3. 检查失败节点前的 DIRECT/BROADCAST 拦截日志；
4. 如果是并发批次，确认是否只有一个节点失败，避免把下游错误误判为根因。

### 4.3 权限拦截 / 审计缺失

1. 搜 `DIRECT 通道拦截阻断` / `BROADCAST 消息被丢弃`；
2. 用 `sessionId + agentId` 找到 `PermissionEngine` 判定日志；
3. 查 `t_audit_log.action` 是否记录对应敏感操作；
4. 审计缺失时按第三节“Kafka 模式”步骤检查 DLT。

## 5. 收尾动作

- 复现成功后保存：`correlationId`、`sessionId`、`graphExecutionId`、`trace_id`、相关 JSON 行；
- 若为权限/审计事件，导出 SQL 查询结果；
- 如果日志量过大，先按 `level=ERROR`、`logger_name` 和时间窗口过滤，再放宽到 INFO。

## 6. 排障速查

```powershell
# 1. 查错误
Select-String .\logs\aether-agent.json -Pattern '<CORRELATION_ID>'

# 2. 查会话所有事件
Select-String .\logs\aether-agent.json -Pattern '<SESSION_ID>'

# 3. 查审计
psql "$DB_URL" -c "SELECT * FROM t_audit_log WHERE detail LIKE '%<CORRELATION_ID>%' ORDER BY created_at DESC;"
```

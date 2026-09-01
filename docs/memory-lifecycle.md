# 记忆生命周期设计（P1-4.3）

> 目标：记忆子系统从"能存能取"升级为"生命周期完整"——**衰减遗忘 + 写入门槛 + 冲突合并**，
> 解决三个问题：记忆只增不减、噪音记忆污染召回、用户偏好改了旧记忆还在打架。

## 1. 机制总览

```
写入侧                                   存储侧                        读取侧
──────                                   ──────                       ──────
EncodingFlow(LLM 打分) ──→ [写入门槛] ──→ aether_memories ←── [遗忘曲线] 周期衰减
                            │ importance<0.6 拒绝           保留分 < 0.2 → archived=true
                            └ 通过 → 冲突合并(策略) → upsert（同 id 复活）
检索：search 默认 WHERE archived = false（归档记忆不可见）
回填：命中记忆 access_count+1 / last_accessed_at=NOW()（遗忘曲线的输入信号）
```

## 2. 遗忘曲线（MemoryDecayJob）

- **保留分公式**（SQL 内计算，批处理效率）：

  ```
  retention = 0.5 × recency + 0.3 × frequency + 0.2 × importance
  recency   = max(0, 1 - 距last_accessed_at秒数 / (half_life_days × 86400))
  frequency = min(1, access_count / 10)
  ```

- `retention < min-retention-score` → `archived=true`（软删，非物理删除）；
- **复活**：归档记忆被再次写入（upsert 同 id）→ `archived=false` 重新进入召回；
- 调度：共享 `scheduledPool` 每 `interval-minutes` 一轮（对齐 StaleDelegationScanner 模式），
  单轮失败降级不取消周期；分批 `batch-size`、单轮上限 `max-archive-per-run`；
- 仅作用于 pgvector 后端（`PgMemoryDecayStore`，`aether.memory.pgvector.enabled=true`）；
  文件后端不执行（无归档需求场景保持零成本）。

**配置**（`aether.memory.decay.*`）：`enabled=true` / `interval-minutes=60` / `half-life-days=30` /
`min-retention-score=0.2` / `max-archive-per-run=1000` / `batch-size=200`。

**指标**：`aether.memory.decay.archived.total`（Counter）、`aether.memory.decay.active.count` /
`aether.memory.decay.archived.count`（Gauge）、`aether.memory.decay.slim.ratio`（瘦身率 =
archived/(archived+active)，即路线图"记忆库瘦身率"）。

## 3. 写入质量门槛（MemoryWriteGate）

- **触发条件**：`EncodingFlow` 经 LLM 成功打分（`llmCalled && llmSuccess`）且
  `importance < min-importance(0.6 ≈ 3/5)` → 拒绝入库；
- **放行路径**：无 LLM 环境（默认 importance 0.5 不可信）、LLM 编码失败、门槛关闭——
  门槛只拦"LLM 明确判定为琐碎"，不降低系统在降级环境下的可用性；
- 被拒记忆不落库，返回 `source="gate_rejected"` 占位记录（metadata 标记），指标计数。

**配置**（`aether.memory.write-gate.*`）：`enabled=true` / `min-importance=0.6`。
**指标**：`aether.memory.write.rejected.total` / `aether.memory.write.accepted.total`。

## 4. 冲突合并（MemoryConflictResolver）

相似度 ≥ `recall.consolidation-threshold(0.85)` 时对同主题新旧记忆执行策略：

| 策略（`aether.memory.conflict.strategy`） | 行为 | 适用 |
|---|---|---|
| `concat`（默认） | 新旧内容拼接 + 重要性均值（原行为，回退安全） | 兼容优先 |
| `new-wins` | 新内容整体替换 | 偏好更新场景，旧记忆立即失效 |
| `llm` | LLM 判定 merge（融合改写）/ replace（新胜旧）；失败回退 concat | 语义最优（需 ChatModel） |

**指标**：`aether.memory.conflict.resolved.total`。
**写入侧联动**：合并 upsert 强制 `archived=false`（冲突复活优先于遗忘）。

## 5. 评测（确定性，CI 常驻）

`MemoryLifecycleEvalTest`（无网络依赖）四断言：
1. **忘得掉**：20 轮写入的 10 条低保留分记忆一轮衰减全部归档；
2. **记得住**：10 条高重要性记忆在归档集之外；
3. **不打架**：偏好更新（new-wins）后旧偏好内容不残留；
4. **门槛生效**：LLM 打分 0.2 的琐碎内容被拦，0.9 的偏好入库。

真实库回归：`PgMemoryLifecycleIT`（Testcontainers pgvector，`mvn verify -Pintegration`）
验证 归档 → 召回不可见 → upsert 复活 → access_count 回填 全闭环。

## 6. 状态流转

```
            写入(通过门槛)
                 │
                 ▼
           ┌──────────┐   retention<阈值(decay job)   ┌──────────┐
           │  active  │ ────────────────────────────▶ │ archived │
           │(可被召回)│ ◀──────────────────────────── │(召回不可见)│
           └──────────┘     再次 upsert 同 id(复活)    └──────────┘
```

## 7. 变更清单

| 文件 | 变更 |
|---|---|
| `MemoryRecord` | +`archived` 字段（Builder 默认 false） |
| `MemoryProperties` | +`decay` / `write-gate` / `conflict` 子段 |
| `lifecycle/MemoryDecayStore` `MemoryDecayJob` `MemoryDecayMetrics` | 衰减端口/调度/指标 |
| `lifecycle/MemoryWriteGate` | 写入门槛 |
| `lifecycle/MemoryConflictResolver` | 冲突合并三策略 |
| `DefaultMemoryFacade` | 门槛/合并接线 + 计数 |
| `PgvectorVectorStore` | search 过滤 archived、upsert 复活、touch 回填、列自愈 |
| `PgMemoryDecayStore` | 保留分 SQL 归档实现 |
| `data/sql/V5__p1_messaging_and_memory.sql` §2 | archived 列 + 索引（与 Java 自愈等价） |

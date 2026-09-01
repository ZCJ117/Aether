# RAG 三级检索管道设计（P1-4.2）

> 目标：检索从"pgvector 纯向量"升级为**三级管道**：查询改写 → 混合召回（RRF）→ 重排，
> 每级带配置开关与延迟预算，逐级降级、按延迟预算取舍（roadmap 4.2）。

## 1. 数据流

```
query（用户口语）
  │
  ▼ 一级 QueryRewriter（LLM 改写，aether.rag.rewrite.*）
检索友好 query + 2-3 同义变体
  │
  ▼ 二级 HybridSearch（单 SQL，aether.rag.hybrid.*）
  │   · 语义路：pgvector HNSW cosine Top-30（embedding <=> ?::vector）
  │   · 词法路：PG 全文 to_tsvector('simple', content_bigram) @@ plainto_tsquery，GIN 表达式索引，Top-30
  │   · RRF 融合：score = Σ 1/(60 + rank)，FULL OUTER JOIN 双路，全 SQL 内完成（零新组件）
  ▼
Top-50 候选
  │
  ▼ 三级 PythonReranker（aether.rag.rerank.*，走既有 PythonServicePort → document-service /rerank）
Top-10 结果（带 StageTrace 轨迹）
```

**中文适配（零扩展组件）**：PG 自带 'simple' 分词对中文无效、zhparser 需扩展。
`CjkBigram`（Java）在写入时把内容归一化为"空格分隔 token 流"（CJK 连续段切 2-gram +
拉丁词），存 `content_bigram` 列并建 `GIN(to_tsvector('simple', content_bigram))`
表达式索引；查询侧同样 bigram 化后走 `plainto_tsquery`——中文词法召回无需任何数据库扩展。

## 2. 切换条件（逐级降级矩阵）

| 级 | 启用条件 | 降级触发 | 降级行为 |
|---|---|---|---|
| 一级 改写 | `aether.rag.rewrite.enabled=true` 且 ChatModel 装配 | 无模型 / 超时(`rewrite.timeout-ms`=800) / 异常 / 解析失败 | 使用原 query |
| 二级 混合 | `hybrid.enabled=true` 且 HybridSearchPort 装配（pgvector） | 端口异常 / 无有效向量 | 纯向量（VectorStore.search，零向量走时间序） |
| 三级 重排 | `rerank.enabled=true` 且 RerankPort 装配 | Python 不可达 / 超时(`rerank.timeout-ms`=800) / 空响应 | 保留 RRF 序截断 Top-N |

- 总开关 `aether.rag.enabled=false`（默认）→ 整条管道旁路，`RecallFlow.recallShallow` 走原路径，零行为变化；
- 管道空结果 / 异常 → RecallFlow 回退原检索路径（双保险）；
- Deep 召回维持原 LLM 查询分解 + LLM 重排链路（已具备同等能力）。

## 3. 配置速查（application.yml）

```yaml
aether:
  rag:
    enabled: false        # 总开关
    rewrite: { enabled: false, timeout-ms: 800, variants: 2 }
    hybrid:  { enabled: true, vector-top-k: 30, lexical-top-k: 30, candidate-limit: 50, rrf-k: 60 }
    rerank:  { enabled: false, timeout-ms: 800, top-n: 10 }
```

**推荐组合（按延迟预算）**：
- 低预算（P99 敏感）：仅二级（`hybrid.enabled=true`，其余关）——单 SQL 增量 ~1-3ms；
- 标准预算：二级 + 三级（Python 重排 +800ms 预算）；
- 完整：三级全开（改写 +800ms）。改写与重排均 fail-open，不会放大故障。

## 4. 质量评测（确定性，CI 常驻）

`RetrievalEvalTest`：15 篇中文语料 × **30 条标注 query**（`aether-domain/src/test/resources/rag/queries.jsonl`，
含口语化改写样本，由评测直接加载），指标 Recall@5 / MRR：

| 策略 | Recall@5 | MRR |
|---|---|---|
| pure-vector（伪语义基线） | 0.800 | 0.688 |
| hybrid (RRF) | **0.867** | 0.681 |
| hybrid + rerank | **0.867** | **0.757** |

结论与回归断言（质量不变量）：混合召回提升召回率（词法路补回语义混淆文档）；
重排恢复 MRR；管道端到端不劣于纯向量。真实模型在线评测（接 DeepSeek + 真实 embedding
的 Runner）为后续工作——当前 CI 跑的是上述零网络依赖的确定性评测。

## 5. 重排服务（document-service /rerank）

- 请求：`{query, documents:[...]}`；响应：`{results:[{index, score}...], scorer}`（按分降序）；
- 默认实现：**零依赖启发式**（bigram token F1，确定性、无 GPU）；
- 升级路径：环境变量 `RERANK_MODEL=BAAI/bge-reranker-v2-m3` → CrossEncoder 打分
  （需 `pip install sentence-transformers`；加载失败自动回退启发式）；
- 调用通道：`PythonServicePort.rerank` → `PythonServiceClient`（RestTemplate + SSRF 拦截器，复用 `aether.python.doc-url`）。

## 6. 代码地图

| 文件 | 职责 |
|---|---|
| `retrieval/rag/RetrievalPipeline` | 编排 + 逐级降级 + `aether.rag.stage.duration` / `aether.rag.degrade.total` |
| `retrieval/rag/QueryRewriter` | 一级改写（fail-open） |
| `retrieval/rag/CjkBigram` | 中文 bigram 归一化 |
| `retrieval/rag/HybridSearchPort` / `PgHybridSearchRepository` | 二级 RRF 单 SQL + FTS 自愈（列/GIN/存量回填） |
| `retrieval/rag/RerankPort` / `PythonReranker` | 三级重排（超时/降级） |
| `PgvectorVectorStore` | upsert 同步维护 `content_bigram` |
| `RecallFlow` | 管道接管 shallow 召回（可选） |

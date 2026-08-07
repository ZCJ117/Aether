# Aether 记忆系统真实召回改造设计

> 日期：2026-08-07
> 状态：已批准（用户确认 1A + 2A + 3A 方案组合）
> 范围：`aether` 后端记忆系统（分支 `feat/memory-hermes-alignment` 工作区）

---

## 1. 背景与目标

Aether 记忆系统（`aether-domain/.../agent/service/memory/`，对齐 hermes 生命周期模式）当前存在**核心缺口：没有真实 embedding 生产链路，检索是"伪检索"**：

| # | 环节 | 现状 |
|---|---|---|
| 1 | 写入向量 | `DefaultMemoryFacade.remember` 新建记录 `embedding(null)` + `upsert(id, null, record)` → 存储层 embedding 全为 NULL |
| 2 | 查询向量 | `RecallFlow.embed()` 依赖可选 Spring AI `EmbeddingModel`，但**全项目未装配任何 `EmbeddingModel` bean** → 返回 1024/1280 维全零向量 |
| 3 | pgvector search | `PgvectorVectorStore.search` 忽略 queryVector，硬编码 `0.5 AS similarity` + `ORDER BY last_accessed_at DESC` |
| 4 | MemoryStore search | `computeRelevance = 0.3 + min(0.5, len/10000) + random*0.2`，与查询无关 |
| 5 | 基础设施 | `ModelProvider.buildOpenAiApi()` 已构建 `OpenAiApi`（base-url + `embeddings-path: v1/embeddings`），但记忆系统未接 |

**目标**：接入真实 `EmbeddingModel`，让 pgvector 与 MemoryStore 两个后端都做**真实余弦召回**，修复合并检测传 null 向量的问题，并对存量 NULL 向量记录做启动回填。

**成功标准（可验证）**：
1. 写入路径产生非 null 的真实 embedding（新记录 + 合并记录）。
2. `DefaultMemoryFacade.remember` 合并检测搜索收到真实查询向量（非 null）。
3. pgvector search 执行 `1 - (embedding <=> ?::vector)` 并按余弦排序（列已迁移为 `vector` 时）。
4. MemoryStore search 按真实余弦排序（替换伪打分）。
5. 存量 NULL 向量记录启动时自动回填（异步、可开关、有上限）。
6. 未配置 embedding 时全链路回退现状（时间排序），不崩溃。

---

## 2. 澄清结论（决策依据）

| 问题 | 用户确认 |
|---|---|
| Q1 embedding 来源 | `v1/embeddings` 端点可用（`ModelProvider.buildOpenAiApi` 管道复用） |
| Q2 模型/维度 | 1024 维（bge-m3 等）；DDL 与 `DEFAULT_DIM` 均改 1024 |
| Q3 后端范围 | 两个后端都修（pgvector 真余弦 + MemoryStore 内存余弦） |
| Q4 存量回填 | 启动时自动回填 |

方案决策：**1A（静态 @Bean 装配 EmbeddingModel）+ 2A（HNSW 索引 + 文档化迁移 + 降级回退）+ 3A（扩展 VectorStore 接口 + 独立回填编排器）**。

---

## 3. 架构与组件清单

```
┌─ 新增：MemoryEmbeddingConfig      ── 静态 @Bean：aether.memory.embedding.* → OpenAiEmbeddingModel
│ 新增：MemoryEmbeddingBackfillRunner ── ApplicationReadyEvent 异步回填编排
│ 新增：测试（见 §7）
│
├─ 修改：VectorStore（接口）        ── +findMissingEmbeddings(limit) / +updateEmbedding(id,vec)
├─ 修改：DefaultMemoryFacade        ── 注入 EmbeddingModel；remember() 生成向量；合并检测传真实向量；合并后重嵌
├─ 修改：RecallFlow                 ── DEFAULT_DIM 改从配置读（默认 1024）
├─ 修改：MemoryStore（文件后端）     ── search 真余弦；删除伪打分；+2 个回填方法
├─ 修改：PgvectorVectorStore        ── search 余弦 SQL + 启动幂等 HNSW 索引 + 降级回退；+2 个回填方法
├─ 修改：MemoryProperties           ── +Embedding 段 / +Backfill 段；vector-dimension 默认 1024
├─ 修改：application.yml(.dev)      ── 新增 embedding/backfill 段；vector-dimension: 1024
└─ 修改：docs/postgresql_schema.sql ── vector(1280)→vector(1024)；HNSW 索引说明
```

### 3.1 EmbeddingModel 装配（决策 1A）

`MemoryEmbeddingConfig`（`aether-domain/.../memory/core/`）：

- `@Configuration` + `@ConditionalOnProperty(prefix="aether.memory", name="enabled", havingValue="true", matchIfMissing=true)`
- `@Bean @ConditionalOnMissingBean(EmbeddingModel.class)`
- 条件：`aether.memory.embedding.base-url` 非空才创建；否则返回空（无 bean），启动打印 WARN「记忆 embedding 未配置，语义检索不可用」。
- 构建方式：复用 `ModelProvider.buildOpenAiApi(ModelConfig)` 的超时双通道模式（RestClient + WebClient 各配 connect/read 超时），`OpenAiEmbeddingModel.builder().openAiApi(api).build()`。
- 依赖注入：`ModelProviderRegistry` 解析默认 provider（或直接构造 `OpenAiApi`），避免与 Agent 运行时装配（`ChatModelNode` 动态注册 bean）耦合，保证启动期即就绪。

### 3.2 VectorStore 接口扩展（决策 3A）

```java
public interface VectorStore {
    // ... 既有方法不变 ...
    /** 查找 embedding 缺失的记录（回填用），最多 limit 条 */
    CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit);
    /** 回填更新单条向量 */
    CompletableFuture<Void> updateEmbedding(String id, float[] vector);
}
```

仅 2 个实现类（`MemoryStore`、`PgvectorVectorStore`），改动可控。测试侧无 `VectorStore` 直实现（`FakeMemoryFacade` 实现的是 `MemoryFacade`），不受影响。

---

## 4. 数据流

### 4.1 写入（turn 后）

```
DefaultMemoryFacade.remember(content, scope, options)
  ① encodingFlow.encode(content)                  # LLM 元数据（不变）
  ② embedding = embed(content)                    # 新增：真实向量；EmbeddingModel 缺失 → null
  ③ if encoded.shouldConsolidate() && threshold>0:
       similar = vectorStore.search(embedding, 3, [scope])   # 修复：传真实向量（原 null）
       ≥0.85 → 合并：
         mergedContent = old.content + "\n\n" + content
         mergedEmbedding = embed(mergedContent)              # 新增：合并后重嵌
         upsert(merged.id, mergedEmbedding, merged)
  ④ else: upsert(id, embedding, record)            # 修复：embedding 从此非 null
```

### 4.2 检索（turn 前）

```
RecallFlow.recallShallow(query)
  queryVec = embeddingModel.embed(query)          # 真实 1024 维；无模型 → 零向量
  vectorStore.search(queryVec, maxResults*2, scopes)
  rerankByWeight(0.6×semantic + 0.3×recency + 0.1×importance)   # 不变
```

**pgvector**（`PgvectorVectorStore.search`）：

```sql
SELECT id, content, scope_path, scope_private, categories, importance, source,
       created_at, last_accessed_at, access_count,
       1 - (embedding <=> ?::vector) AS similarity
FROM aether_memories
WHERE embedding IS NOT NULL
  [AND (scope_path LIKE ? OR ...)]
ORDER BY embedding <=> ?::vector
LIMIT ?
```

**MemoryStore**（`MemoryStore.search`）：逐 JSON 文件解析 → 读取 Base64 存储向量（`"null"` 跳过）→ 真实余弦 `cosine(stored, queryVec)` 作为 `score`。

### 4.3 回填（启动）

```
ApplicationReadyEvent → 独立线程（异步，不阻塞启动）
  while (true):
    missing = vectorStore.findMissingEmbeddings(batchSize=50)
    空 → 结束
    逐条: vec = embed(content); vectorStore.updateEmbedding(id, vec)   # 单条失败跳过
    累计 ≥ maxPerRun(500) → WARN 剩余未回填数，结束
```

守卫：`aether.memory.backfill.enabled=true`（默认）+ `EmbeddingModel` bean 存在。首次升级启动回填全部存量，后续启动查无 NULL 即空转。

---

## 5. 配置

`application.yml`（dev 同）：

```yaml
aether:
  memory:
    embedding:                      # 新增
      base-url: https://api.deepseek.com   # 空则不装配 bean，回退现状
      api-key: ${DEEPSEEK_API_KEY}
      path: v1/embeddings
      model: bge-m3
      dimension: 1024                      # 校验用
    recall:
      vector-dimension: 1024        # 修改：1280 → 1024
    backfill:                       # 新增
      enabled: true
      batch-size: 50
      max-per-run: 500
```

`MemoryProperties` 扩展：

```java
@Data @ConfigurationProperties(prefix = "aether.memory")
public class MemoryProperties {
    private Recall recall = new Recall();        // vectorDimension 默认改 1024
    private Embedding embedding = new Embedding();
    private Backfill backfill = new Backfill();

    @Data public static class Embedding {
        private String baseUrl = "";
        private String apiKey = "";
        private String path = "v1/embeddings";
        private String model = "";
        private int dimension = 1024;
    }
    @Data public static class Backfill {
        private boolean enabled = true;
        private int batchSize = 50;
        private int maxPerRun = 500;
    }
}
```

`docs/postgresql_schema.sql`：迁移说明与注释 `vector(1280)` → `vector(1024)`；ivfflat 注释段改为 HNSW（`USING hnsw (embedding vector_cosine_ops)`）。**基础建表保持 `embedding TEXT` 不变**（兼容无 pgvector 实例部署）；pgvector 路径走文档化 `ALTER TABLE ... TYPE vector(1024) USING embedding::vector` + 代码启动幂等建索引。

---

## 6. 错误处理与降级

| 场景 | 行为 |
|---|---|
| `base-url` 未配置 → 无 EmbeddingModel bean | 启动 WARN；写入存 NULL、检索回退时间排序（= 现状） |
| pgvector 列仍 TEXT / 扩展缺失 / `<=>` 抛异常 | catch → WARN → 回退时间排序，不崩 |
| 查询向量为 null 或全零 | search 侧 guard → 回退时间排序 |
| 回填单条 embed 失败 | 跳过该条继续；达 max-per-run → WARN 剩余数并停止 |
| MemoryStore 目录无向量文件 | 返回空列表 |

**pgvector 降级实现要点**：`search` 内先做"余弦可用性"判定（queryVector 非 null 且非全零），再尝试余弦 SQL；异常（含列类型不匹配）统一 catch 到既有的时间排序回退分支并 WARN。启动幂等建索引 `CREATE INDEX IF NOT EXISTS ... USING hnsw` 用 try/catch 包裹（列仍 TEXT 时建索引会失败，WARN 不阻断）。

---

## 7. 测试计划

`aether-domain/src/test/.../service/memory/`（注意 surefire 坑：`-Dtest=<Class> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`）：

| 测试类 | 覆盖 |
|---|---|
| `MemoryStoreSearchTest` | 临时目录写已知向量 → 断言按余弦排序；`"null"` embedding 文件被跳过；空目录返回空 |
| `DefaultMemoryFacadeMergeTest` | 断言 remember 合并检测 search **收到非 null 向量**；≥0.85 触发合并；合并后重嵌 |
| `MemoryEmbeddingBackfillRunnerTest` | fake VectorStore：updateEmbedding 被调、失败跳过、超上限停止 |
| `MemoryEmbeddingConfigTest` | base-url 有值→bean 存在；空→无 bean |
| `MemoryPropertiesTest` | embedding/backfill/1024 配置绑定 |
| Pgvector SQL 提取方法测试 | 余弦 SQL 构建+参数绑定提取为可单测方法；真实 PG 行为标注为手动/后续 testcontainers |

---

## 8. 边界与已知限制

1. **pgvector 真余弦依赖列已迁移为 `vector(1024)`**：未迁移时代码自动回退时间排序（行为不变），不报错。真实语义召回需要运维执行 schema ALTER + 确认 pgvector 扩展可用。
2. **`OpenAiEmbeddingModel` 的 API 依赖 spring-ai 1.1.0**（`EmbeddingModel.embed(String) → float[]`，`RecallFlow` 现有调用已编译通过），装配不引入新依赖。
3. **embedding 调用成本**：每次 `remember` 一次 embedding；合并路径额外一次（重嵌合并内容）；回填按存量规模一次性。无 token 预算门控（与既有 `EncodingFlow`/`llmRerank` 策略一致，可后续补）。
4. **维度一致性**：`recall.vector-dimension`（1024）与 `embedding.dimension`（1024）默认一致；装配时若 embedding 返回维度与配置不符，记 WARN 不阻断。
5. **`stats()` 空壳、TTL 淘汰、`classifyScope` 4 词关键词** 等既有缺陷**不在本次范围**（保持手术式修改）。

---

## 9. 实施顺序建议

1. `MemoryProperties` 扩展 + `application.yml`（先让配置就位）。
2. `MemoryEmbeddingConfig` + `EmbeddingModel` bean 装配。
3. `VectorStore` 接口 + 两个实现类的余弦 search 与回填方法。
4. `DefaultMemoryFacade` 写入链路（生成向量 + 修复合并 null）。
5. `MemoryEmbeddingBackfillRunner` 回填编排。
6. `docs/postgresql_schema.sql` 文档更新。
7. 测试补齐 → `mvn -pl aether-domain -am test` 全绿。

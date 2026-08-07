# Aether 记忆系统真实召回改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Aether 记忆系统的 pgvector 与 MemoryStore 两个后端都做真实余弦召回，修复合并检测传 null 向量，并启动时回填存量 NULL 向量记录。

**Architecture:** 装配一个静态 `EmbeddingModel` bean（复用 `ModelProvider.buildOpenAiApi` 超时约定）；`DefaultMemoryFacade.remember` 生成真实向量并用于合并检测与写入；两个 `VectorStore` 后端的 `search` 改真余弦；`VectorStore` 接口扩展 `findMissingEmbeddings`/`updateEmbedding`，由 `MemoryEmbeddingBackfillRunner` 在 `ApplicationReadyEvent` 异步回填。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring AI 1.1.0-M3（`spring-ai-openai`/`spring-ai-commons`）、PostgreSQL pgvector、Maven surefire。

**设计文档:** `docs/superpowers/specs/2026-08-07-pgvector-cosine-recall-design.md`（已批准）

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `aether-domain/.../memory/core/MemoryProperties.java` | 修改 | 新增 `Embedding`/`Backfill` 段；`recall.vectorDimension` 默认 1024 |
| `aether-domain/.../memory/VectorStore.java` | 修改 | 新增 `findMissingEmbeddings`/`updateEmbedding` |
| `aether-domain/.../memory/MemoryStore.java` | 修改 | search 真余弦 + 回填方法 + 维度配置化 |
| `aether-infrastructure/.../repository/PgvectorVectorStore.java` | 修改 | search 余弦 SQL + HNSW 索引 + 回填 + 维度配置化 + 降级 |
| `aether-domain/.../memory/DefaultMemoryFacade.java` | 修改 | 生成向量；合并检测传真实向量；合并后重嵌 |
| `aether-domain/.../memory/RecallFlow.java` | 修改 | `DEFAULT_DIM` 改从配置读 |
| `aether-domain/.../memory/core/MemoryEmbeddingConfig.java` | 新建 | 静态 `@Bean` 装配 `OpenAiEmbeddingModel`（带自定义 Condition） |
| `aether-domain/.../memory/core/MemoryEmbeddingBackfillRunner.java` | 新建 | `ApplicationReadyEvent` 异步回填编排 |
| `aether-app/src/main/resources/application.yml` (+`-dev.yml`) | 修改 | 新增 embedding/backfill 段；vector-dimension=1024 |
| `docs/postgresql_schema.sql` | 修改 | vector(1280)→vector(1024)；HNSW 索引说明 |
| `aether-domain/src/test/.../memory/MemoryStoreSearchTest.java` | 新建 | 文件后端余弦排序/跳过/回填 |
| `aether-domain/src/test/.../memory/DefaultMemoryFacadeMergeTest.java` | 新建 | 合并检测传真实向量 + 重嵌 |
| `aether-domain/src/test/.../memory/core/MemoryEmbeddingConfigTest.java` | 新建 | Condition 与装配 |
| `aether-domain/src/test/.../memory/core/MemoryEmbeddingBackfillRunnerTest.java` | 新建 | 回填全量/上限/失败跳过 |
| `aether-domain/src/test/.../memory/MemoryPropertiesTest.java` | 修改 | 1024 + 新段绑定 |
| `aether-infrastructure/src/test/.../repository/PgvectorVectorStoreSqlTest.java` | 新建 | 余弦 SQL 构建断言 |

**通用测试命令**（`aether` 目录下，注意 surefire 坑）：
```bash
mvn -pl aether-domain -am test -Dtest=<TestClass> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
基建层：`mvn -pl aether-infrastructure -am test -Dtest=<TestClass> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`

---

### Task 1: MemoryProperties 扩展

**Files:**
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProperties.java`
- Test: `aether/aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryPropertiesTest.java`

- [ ] **Step 1: 更新测试（先红）**

在 `MemoryPropertiesTest` 的 `defaultsMatchHermes()` 末尾追加断言，并把 1280 断言改为 1024；`settersBind()` 追加新段 setter 断言：

```java
    @Test
    void defaultsMatchHermes() {
        MemoryProperties p = new MemoryProperties();
        assertTrue(p.isEnabled());
        assertTrue(p.isUserProfileEnabled());
        assertEquals(2200, p.getMemoryCharLimit());
        assertEquals(1375, p.getUserCharLimit());
        assertEquals(10, p.getNudgeInterval());
        assertEquals(6, p.getFlushMinTurns());
        assertEquals(10, p.getRecall().getMaxResults());
        assertEquals(0.6f, p.getRecall().getSemanticWeight());
        assertEquals(0.3f, p.getRecall().getRecencyWeight());
        assertEquals(0.1f, p.getRecall().getImportanceWeight());
        assertEquals(0.85f, p.getRecall().getConsolidationThreshold());
        assertEquals(1024, p.getRecall().getVectorDimension());          // 1280 → 1024
        // 新增：embedding 段
        assertEquals("", p.getEmbedding().getBaseUrl());
        assertEquals("v1/embeddings", p.getEmbedding().getPath());
        assertEquals(1024, p.getEmbedding().getDimension());
        // 新增：backfill 段
        assertTrue(p.getBackfill().isEnabled());
        assertEquals(50, p.getBackfill().getBatchSize());
        assertEquals(500, p.getBackfill().getMaxPerRun());
    }

    @Test
    void settersBind() {
        MemoryProperties p = new MemoryProperties();
        p.setEnabled(false);
        p.setNudgeInterval(0);
        p.setMemoryCharLimit(500);
        p.getRecall().setMaxResults(5);
        p.getRecall().setConsolidationThreshold(0.9f);
        assertFalse(p.isEnabled());
        assertEquals(0, p.getNudgeInterval());
        assertEquals(500, p.getMemoryCharLimit());
        assertEquals(5, p.getRecall().getMaxResults());
        assertEquals(0.9f, p.getRecall().getConsolidationThreshold());
        // 新增：embedding/backfill setter
        p.getEmbedding().setBaseUrl("http://localhost:11434");
        p.getBackfill().setEnabled(false);
        p.getBackfill().setBatchSize(10);
        assertEquals("http://localhost:11434", p.getEmbedding().getBaseUrl());
        assertFalse(p.getBackfill().isEnabled());
        assertEquals(10, p.getBackfill().getBatchSize());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryPropertiesTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`getVectorDimension()` 仍是 1280，`getEmbedding()`/`getBackfill()` 编译失败——方法不存在）

- [ ] **Step 3: 实现属性扩展**

修改 `MemoryProperties.java`：字段区追加两段，`Recall.vectorDimension` 默认改 1024，并追加两个嵌套类：

```java
    /** 检索参数 */
    private Recall recall = new Recall();

    /** Embedding 模型配置（记忆向量生成） */
    private Embedding embedding = new Embedding();

    /** 存量向量回填 */
    private Backfill backfill = new Backfill();
```

`Recall.vectorDimension` 注释与默认值：
```java
        /** 向量维度（pgvector，需与 embedding 模型一致） */
        private int vectorDimension = 1024;
```

类尾部追加（在 `Recall` 嵌套类之后、类的右花括号之前）：
```java
    /** Embedding 模型配置子段 */
    @Data
    public static class Embedding {
        /** API Base URL；空则不装配 EmbeddingModel，语义检索降级 */
        private String baseUrl = "";
        /** API Key */
        private String apiKey = "";
        /** Embeddings 路径 */
        private String path = "v1/embeddings";
        /** 模型名（v1/embeddings 接受的 model 参数） */
        private String model = "";
        /** 向量维度 */
        private int dimension = 1024;
    }

    /** 存量回填配置子段 */
    @Data
    public static class Backfill {
        /** 回填开关 */
        private boolean enabled = true;
        /** 每批扫描量 */
        private int batchSize = 50;
        /** 单次启动回填上限 */
        private int maxPerRun = 500;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryPropertiesTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryProperties.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryPropertiesTest.java
git commit -m "feat(memory): 扩展 MemoryProperties（embedding/backfill 配置段，向量维度默认 1024）"
```

---

### Task 2: VectorStore 接口 + MemoryStore 真余弦与回填（Pgvector 占位）

**Files:**
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/VectorStore.java`
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/MemoryStore.java`
- Modify: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgvectorVectorStore.java`（仅占位实现新方法，保证编译）
- Test: `aether/aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/MemoryStoreSearchTest.java`

- [ ] **Step 1: 写失败测试**

新建 `MemoryStoreSearchTest.java`（包 `cn.zcj.aether.domain.agent.service.memory`）：

```java
package cn.zcj.aether.domain.agent.service.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreSearchTest {

    private Path tempDir;
    private MemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("mem-search-test");
        store = new MemoryStore();
        ReflectionTestUtils.setField(store, "configuredMemoryDir", tempDir.toString());
    }

    private MemoryRecord record(String id, String content) {
        return MemoryRecord.builder()
            .id(id)
            .content(content)
            .scope(MemoryScope.global())
            .importance(0.5f)
            .source("test")
            .build();
    }

    @Test
    void searchRanksByCosineSimilarity() {
        store.upsert("match", new float[]{0.99f, 0.1f, 0f}, record("match", "最相关内容")).join();
        store.upsert("orthogonal", new float[]{0f, 1f, 0f}, record("orthogonal", "无关内容")).join();

        List<MemorySearchResult> results = store.search(new float[]{1f, 0f, 0f}, 2, List.of(MemoryScope.global())).join();

        assertEquals(2, results.size());
        assertEquals("match", results.get(0).getRecord().getId());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void searchSkipsNullEmbedding() {
        store.upsert("no-vec", null, record("no-vec", "无向量")).join();
        store.upsert("has-vec", new float[]{1f, 0f, 0f}, record("has-vec", "有向量")).join();

        List<MemorySearchResult> results = store.search(new float[]{1f, 0f, 0f}, 10, List.of(MemoryScope.global())).join();

        assertEquals(1, results.size());
        assertEquals("has-vec", results.get(0).getRecord().getId());
    }

    @Test
    void backfillFindsAndUpdatesMissingEmbeddings() {
        store.upsert("missing", null, record("missing", "无向量")).join();
        store.upsert("present", new float[]{1f, 0f, 0f}, record("present", "有向量")).join();

        List<MemoryRecord> missing = store.findMissingEmbeddings(10).join();
        assertEquals(1, missing.size());
        assertEquals("missing", missing.get(0).getId());

        store.updateEmbedding("missing", new float[]{1f, 0f, 0f}).join();
        List<MemoryRecord> after = store.findMissingEmbeddings(10).join();
        assertTrue(after.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryStoreSearchTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（编译失败：`VectorStore` 无 `findMissingEmbeddings`/`updateEmbedding`；运行期 `searchRanksByCosineSimilarity` 因伪打分可能断言失败）

- [ ] **Step 3: 扩展接口**

`VectorStore.java` 尾部追加（保持既有方法不变）：
```java
    /** 查找 embedding 缺失的记录（回填用），最多 limit 条 */
    CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit);

    /** 回填更新单条向量 */
    CompletableFuture<Void> updateEmbedding(String id, float[] vector);
```

- [ ] **Step 4: 实现 MemoryStore**

`MemoryStore.java`：
1. 字段追加（`configuredMemoryDir` 之后）：
```java
    /** 向量维度（pgvector 对齐；文件后端仅用于一致性） */
    @Value("${aether.memory.recall.vector-dimension:1024}")
    private int vectorDimension = 1024;
```
2. `search` 方法整体替换为余弦实现，删除 `computeRelevance`：
```java
    @Override
    public CompletableFuture<List<MemorySearchResult>> search(
            float[] queryVector, int maxResults, List<MemoryScope> scopes) {

        return CompletableFuture.supplyAsync(() -> {
            List<MemorySearchResult> results = new ArrayList<>();
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) {
                return results;
            }

            File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return results;

            // 无效查询向量（未配置 EmbeddingModel / 全零）→ 回退按文件修改时间排序
            if (isInvalidQueryVector(queryVector)) {
                List<MemorySearchResult> fallback = new ArrayList<>();
                for (File file : files) {
                    try {
                        MemoryRecord record = parseMemoryRecord(file, Files.readString(file.toPath()));
                        if (record != null) {
                            fallback.add(MemorySearchResult.of(record, recencyScore(file.lastModified())));
                        }
                    } catch (IOException ignored) {
                        // 单文件读取失败跳过
                    }
                }
                fallback.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
                return fallback.subList(0, Math.min(fallback.size(), maxResults));
            }

            for (File file : files) {
                try {
                    String content = Files.readString(file.toPath());
                    MemoryRecord record = parseMemoryRecord(file, content);
                    if (record == null) continue;

                    float[] stored = parseStoredEmbedding(content);
                    if (stored == null) continue; // "null" 或缺失 → 跳过

                    double similarity = cosineSimilarity(queryVector, stored);
                    results.add(MemorySearchResult.of(record, similarity));
                } catch (Exception e) {
                    log.debug("读取记忆文件失败: {}", file.getName());
                }
            }

            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return results.subList(0, Math.min(results.size(), maxResults));
        });
    }
```
3. `computeRelevance` 方法删除，替换为以下辅助方法（放在原 `computeRelevance` 位置）：
```java
    /** 判断查询向量是否无效（null/空/全零） */
    private boolean isInvalidQueryVector(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float f : v) {
            if (f != 0f) return false;
        }
        return true;
    }

    /** 从 JSON 内容解析存储的 Base64 向量；缺失或 "null" 返回 null */
    private float[] parseStoredEmbedding(String jsonContent) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(jsonContent);
            if (!node.has("embedding")) return null;
            String enc = node.get("embedding").asText();
            if (enc == null || enc.isEmpty() || "null".equals(enc)) return null;
            return bytesToFloatArray(Base64.getDecoder().decode(enc));
        } catch (Exception e) {
            return null;
        }
    }

    /** 余弦相似度（0.0~1.0；零向量返回 0.0） */
    private double cosineSimilarity(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 文件修改时间衰减分（降级排序用） */
    private double recencyScore(long lastModifiedMillis) {
        long daysAgo = java.time.Duration.between(
                java.time.Instant.ofEpochMilli(lastModifiedMillis), java.time.Instant.now()).toDays();
        if (daysAgo <= 1) return 1.0;
        if (daysAgo <= 7) return 0.8;
        if (daysAgo <= 30) return 0.5;
        return 0.2;
    }
```
4. `bytesToFloatArray` 追加到 `floatArrayToBytes` 旁边：
```java
    private float[] bytesToFloatArray(byte[] bytes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        int n = bytes.length / 4;
        float[] floats = new float[n];
        for (int i = 0; i < n; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
```
5. `dimension()` 改为返回配置值：
```java
    @Override
    public int dimension() {
        return vectorDimension;
    }
```
6. 在 `deleteByScope` 之后追加两个接口方法实现：
```java
    @Override
    public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<MemoryRecord> missing = new ArrayList<>();
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) return missing;

            File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return missing;

            for (File file : files) {
                if (missing.size() >= limit) break;
                try {
                    String content = Files.readString(file.toPath());
                    if (parseStoredEmbedding(content) == null) {
                        MemoryRecord record = parseMemoryRecord(file, content);
                        if (record != null) missing.add(record);
                    }
                } catch (Exception e) {
                    log.debug("回填扫描读取失败: {}", file.getName());
                }
            }
            return missing;
        });
    }

    @Override
    public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
        return CompletableFuture.runAsync(() -> {
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) return;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
                if (files == null) return;
                for (File file : files) {
                    String content = Files.readString(file.toPath());
                    var node = mapper.readTree(content);
                    String fileId = file.getName().replace(".json", "");
                    String jsonId = node.has("id") ? node.get("id").asText() : fileId;
                    if (!jsonId.equals(id)) continue;

                    String embeddingStr = vector != null
                        ? Base64.getEncoder().encodeToString(floatArrayToBytes(vector)) : "null";
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("embedding", embeddingStr);
                    Files.writeString(file.toPath(), mapper.writeValueAsString(node));
                    log.debug("回填向量更新: id={}", id);
                    return;
                }
                log.warn("回填更新未找到记录: id={}", id);
            } catch (Exception e) {
                log.warn("回填向量更新失败: id={}", id, e);
            }
        });
    }
```

- [ ] **Step 5: Pgvector 占位实现（保证编译）**

`PgvectorVectorStore.java` 的 `deleteByScope` 之后追加占位（Task 3 将替换为真实实现）：
```java
    @Override
    public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
        return CompletableFuture.completedFuture(null);
    }
```

- [ ] **Step 6: 运行测试确认通过 + 模块编译**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryStoreSearchTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 个测试全绿）

- [ ] **Step 7: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/VectorStore.java aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/MemoryStore.java aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgvectorVectorStore.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/MemoryStoreSearchTest.java
git commit -m "feat(memory): MemoryStore 真实余弦检索与回填方法，VectorStore 接口扩展"
```

---

### Task 3: PgvectorVectorStore 真余弦 + HNSW 索引 + 回填

**Files:**
- Modify: `aether/aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgvectorVectorStore.java`
- Test: `aether/aether-infrastructure/src/test/java/cn/zcj/aether/repository/PgvectorVectorStoreSqlTest.java`

- [ ] **Step 1: 写失败测试**

新建 `PgvectorVectorStoreSqlTest.java`（包 `cn.zcj.aether.repository`）：

```java
package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgvectorVectorStoreSqlTest {

    @Test
    void buildCosineSearchSqlContainsVectorOps() {
        String sql = PgvectorVectorStore.buildCosineSearchSql(List.of());
        assertTrue(sql.contains("<=> ?::vector"));
        assertTrue(sql.contains("embedding IS NOT NULL"));
        assertTrue(sql.contains("ORDER BY embedding <=> ?::vector"));
        assertTrue(sql.contains("LIMIT ?"));
        assertTrue(sql.contains("AS similarity"));
    }

    @Test
    void buildCosineSearchSqlAppendsScopeFilter() {
        String sql = PgvectorVectorStore.buildCosineSearchSql(
            List.of(new MemoryScope("agent/1", false), new MemoryScope("user", true)));
        long count = sql.chars().filter(c -> c == '?').count();
        // 2 个 scope LIKE + 2 个 queryVector + 1 个 LIMIT = 5
        assertEquals(5, count);
        assertTrue(sql.contains("scope_path LIKE ? OR scope_path LIKE ?"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl aether-infrastructure -am test -Dtest=PgvectorVectorStoreSqlTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`buildCosineSearchSql` 不存在）

- [ ] **Step 3: 实现 Pgvector**

`PgvectorVectorStore.java`：
1. 追加 import：
```java
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
```
2. 替换维度字段（第 62 行 `private static final int DEFAULT_DIMENSION = 1280;` 改为实例字段）：
```java
    private static final int DEFAULT_DIMENSION = 1024;

    /** 向量维度（来自配置，默认 1024） */
    @Value("${aether.memory.recall.vector-dimension:1024}")
    private int vectorDimension = DEFAULT_DIMENSION;
```
3. `search` 方法整体替换为余弦实现（含降级回退）：
```java
    @Override
    public CompletableFuture<List<MemorySearchResult>> search(
            float[] queryVector, int topK, List<MemoryScope> scopes) {
        return CompletableFuture.supplyAsync(() -> {
            // 无效查询向量（null/全零）→ 回退按最后访问时间排序
            if (isInvalidQueryVector(queryVector)) {
                return searchByRecency(topK, scopes);
            }
            try {
                String sql = buildCosineSearchSql(scopes);
                String queryVecStr = vectorToDbString(queryVector);
                return jdbc.query(sql, ps -> {
                    int idx = 1;
                    ps.setString(idx++, queryVecStr);
                    if (scopes != null) {
                        for (MemoryScope sc : scopes) {
                            ps.setString(idx++, sc.path() + "%");
                        }
                    }
                    ps.setString(idx++, queryVecStr);
                    ps.setInt(idx, topK);
                }, this::mapRow);
            } catch (Exception e) {
                log.warn("Pgvector 余弦搜索失败（列可能未迁移为 vector），回退时间排序: {}",
                    e.getMessage());
                return searchByRecency(topK, scopes);
            }
        }, executor);
    }
```
4. 在类内追加辅助方法与索引初始化：
```java
    /** 余弦检索 SQL（package-private 便于单测）。占位符顺序：queryVec, ...scopePaths, queryVec, LIMIT */
    static String buildCosineSearchSql(List<MemoryScope> scopes) {
        StringBuilder scopeFilter = new StringBuilder();
        if (scopes != null && !scopes.isEmpty()) {
            scopeFilter.append(" AND (");
            for (int i = 0; i < scopes.size(); i++) {
                if (i > 0) scopeFilter.append(" OR ");
                scopeFilter.append("scope_path LIKE ?");
            }
            scopeFilter.append(")");
        }
        return "SELECT id, content, scope_path, scope_private, categories, importance, source, " +
            "created_at, last_accessed_at, access_count, " +
            "1 - (embedding <=> ?::vector) AS similarity " +
            "FROM aether_memories WHERE embedding IS NOT NULL" + scopeFilter +
            " ORDER BY embedding <=> ?::vector LIMIT ?";
    }

    /** 回退：按最后访问时间排序（无 pgvector / 未迁移 schema / 无效查询向量时使用） */
    private List<MemorySearchResult> searchByRecency(int topK, List<MemoryScope> scopes) {
        StringBuilder scopeFilter = new StringBuilder();
        if (scopes != null && !scopes.isEmpty()) {
            scopeFilter.append(" WHERE ");
            for (int i = 0; i < scopes.size(); i++) {
                if (i > 0) scopeFilter.append(" OR ");
                scopeFilter.append("scope_path LIKE ?");
            }
        }
        String sql = "SELECT id, content, scope_path, scope_private, categories, importance, source, " +
            "created_at, last_accessed_at, access_count, 0.5 AS similarity " +
            "FROM aether_memories" + scopeFilter +
            " ORDER BY last_accessed_at DESC LIMIT ?";
        return jdbc.query(sql, ps -> {
            int idx = 1;
            if (scopes != null) {
                for (MemoryScope sc : scopes) {
                    ps.setString(idx++, sc.path() + "%");
                }
            }
            ps.setInt(idx, topK);
        }, this::mapRow);
    }

    /** 判断查询向量是否无效（null/空/全零） */
    private boolean isInvalidQueryVector(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float f : v) {
            if (f != 0f) return false;
        }
        return true;
    }

    /** 启动时幂等创建 HNSW 索引；列未迁移为 vector 时失败仅告警 */
    @PostConstruct
    void ensureHnswIndex() {
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memories_embedding_hnsw " +
                "ON aether_memories USING hnsw (embedding vector_cosine_ops)");
            log.info("HNSW 向量索引已就绪");
        } catch (Exception e) {
            log.warn("HNSW 索引创建失败（需 embedding 列为 vector 类型）: {}", e.getMessage());
        }
    }
```
5. 替换 Task 2 的占位实现为真实回填方法：
```java
    @Override
    public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return jdbc.query(
                    "SELECT id, content, scope_path, scope_private, categories, importance, source, " +
                    "created_at, last_accessed_at, access_count, 1.0 AS similarity " +
                    "FROM aether_memories WHERE embedding IS NULL LIMIT ?",
                    ps -> ps.setInt(1, limit), this::mapRow);
            } catch (Exception e) {
                log.warn("回填扫描失败: {}", e.getMessage());
                return List.of();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (vector == null || vector.length == 0) return;
                jdbc.update("UPDATE aether_memories SET embedding = ?::vector WHERE id = ?",
                    vectorToDbString(vector), id);
            } catch (Exception e) {
                log.warn("回填向量更新失败: id={}, error={}", id, e.getMessage());
            }
        }, executor);
    }
```
6. `dimension()` 改为返回实例字段（第 180 行 `return DEFAULT_DIMENSION;` → `return vectorDimension;`）。

- [ ] **Step 4: 运行测试确认通过 + 编译**

Run: `mvn -pl aether-infrastructure -am test -Dtest=PgvectorVectorStoreSqlTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 个测试全绿），且 `mvn -pl aether-domain -am compile` 通过

- [ ] **Step 5: 提交**

```bash
git add aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgvectorVectorStore.java aether-infrastructure/src/test/java/cn/zcj/aether/repository/PgvectorVectorStoreSqlTest.java
git commit -m "feat(memory): PgvectorVectorStore 真余弦检索 + HNSW 索引 + 回填方法"
```

---

### Task 4: DefaultMemoryFacade 写入链路（embedding 生成 + 合并修复 + 重嵌）

**Files:**
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/DefaultMemoryFacade.java`
- Test: `aether/aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/DefaultMemoryFacadeMergeTest.java`

- [ ] **Step 1: 写失败测试**

新建 `DefaultMemoryFacadeMergeTest.java`（包 `cn.zcj.aether.domain.agent.service.memory`）：

```java
package cn.zcj.aether.domain.agent.service.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMemoryFacadeMergeTest {

    private static final String ENCODE_JSON =
        "{\"categories\":[\"测试\"],\"importance\":0.8,\"shouldConsolidate\":true,\"consolidationHint\":\"x\"}";

    /** 捕获 search 查询向量 + 记录 upsert 的 VectorStore 桩 */
    static class CapturingVectorStore implements VectorStore {
        float[] lastQueryVector;
        final MemorySearchResult seeded;
        final List<MemoryRecord> upserted = new ArrayList<>();

        CapturingVectorStore(MemorySearchResult seeded) { this.seeded = seeded; }

        @Override
        public CompletableFuture<List<MemorySearchResult>> search(
                float[] queryVector, int topK, List<MemoryScope> scopes) {
            this.lastQueryVector = queryVector;
            return CompletableFuture.completedFuture(seeded == null ? List.of() : List.of(seeded));
        }

        @Override
        public CompletableFuture<Void> upsert(String id, float[] vector, MemoryRecord record) {
            upserted.add(record);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> upsertBatch(List<MemoryRecord> records) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> delete(String id) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> deleteByScope(MemoryScope scope) { return CompletableFuture.completedFuture(null); }
        @Override public int dimension() { return 1024; }
        @Override public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void mergeDetectionUsesRealQueryVectorAndReembeds() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
            ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(ENCODE_JSON)))).build());

        EncodingFlow encodingFlow = new EncodingFlow(chatModel);
        MemoryRecord old = MemoryRecord.builder()
            .id("old-1").content("旧内容").importance(0.4f).scope(MemoryScope.global()).build();
        CapturingVectorStore store = new CapturingVectorStore(MemorySearchResult.of(old, 0.9f));
        DefaultMemoryFacade facade = new DefaultMemoryFacade(encodingFlow, new RecallFlow(), store);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{1f, 0f, 0f});
        ReflectionTestUtils.setField(facade, "embeddingModel", embeddingModel);

        MemoryRecord merged = facade.remember("新内容", MemoryScope.global(),
            new MemoryFacade.StoreOptions(true, 0.85f)).join();

        // 修复点：合并检测收到真实查询向量（非 null）
        assertNotNull(store.lastQueryVector);
        assertEquals(1f, store.lastQueryVector[0]);

        // 合并结果 upsert 一次，内容拼接、重要性加权平均、向量重嵌
        assertEquals(1, store.upserted.size());
        MemoryRecord saved = store.upserted.get(0);
        assertEquals("old-1", saved.getId());
        assertTrue(saved.getContent().contains("旧内容"));
        assertTrue(saved.getContent().contains("新内容"));
        assertEquals(0.6f, saved.getImportance(), 0.001f); // (0.4+0.8)/2
        assertNotNull(saved.getEmbedding());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=DefaultMemoryFacadeMergeTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`store.lastQueryVector` 为 null——当前 remember 传 `null` 给 search）

- [ ] **Step 3: 实现 DefaultMemoryFacade**

`DefaultMemoryFacade.java`：
1. 追加 import 与可选字段（`eventPublisher` 字段之后）：
```java
import org.springframework.ai.embedding.EmbeddingModel;
```
```java
    /** 可选：无 EmbeddingModel 时写入 null 向量，检索端降级 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmbeddingModel embeddingModel;
```
2. `remember` 方法：在 `encoded` 计算后插入向量生成，合并分支用真实向量搜索 + 重嵌，新建分支存真实向量。替换方法体中的相关片段：

`// 2. 如果应合并，搜索相似记忆` 之前插入：
```java
                // 2.5 生成真实向量（未配置 EmbeddingModel → null，检索端降级）
                float[] vector = embedOrNull(content);
```
将合并搜索调用改为传 `vector`：
```java
                if (encoded.shouldConsolidate() && options.consolidationThreshold() > 0) {
                    List<MemorySearchResult> similar = vectorStore.search(
                        vector, 3, List.of(scope)).join();
```
合并分支内，将
```java
                            String mergedContent = sim.getRecord().getContent() + "\n\n" + content;
```
替换为（先计算合并内容与重嵌向量，再构建记录）——用以下完整代码替换 `// 更新已有记忆的重要性（加权平均）` 起的构建块：
```java
                            // 更新已有记忆的重要性（加权平均）+ 合并内容重嵌
                            String mergedContent = sim.getRecord().getContent() + "\n\n" + content;
                            float[] mergedVector = embedOrNull(mergedContent);
                            MemoryRecord merged = MemoryRecord.builder()
                                .id(sim.getRecord().getId())
                                .content(mergedContent)
                                .embedding(mergedVector)
                                .scope(sim.getRecord().getScope())
                                .categories(mergeCategories(sim.getRecord().getCategories(), encoded.categories()))
                                .importance(mergedImportance)
                                .createdAt(sim.getRecord().getCreatedAt())
                                .lastAccessedAt(Instant.now())
                                .accessCount(sim.getRecord().getAccessCount())
                                .isPrivate(sim.getRecord().isPrivate())
                                .source(sim.getRecord().getSource())
                                .build();
                            vectorStore.upsert(merged.getId(), mergedVector, merged).join();
```
新建分支内，将
```java
                MemoryRecord record = MemoryRecord.builder()
                    .id(id)
                    .content(content)
                    .embedding(null) // embedding 由 VectorStore 实现按需生成
```
替换为：
```java
                MemoryRecord record = MemoryRecord.builder()
                    .id(id)
                    .content(content)
                    .embedding(vector)
```
并将
```java
                vectorStore.upsert(id, null, record).join();
```
替换为：
```java
                vectorStore.upsert(id, vector, record).join();
```
3. 类尾部（`mergeCategories` 之后）追加辅助方法：
```java
    /** 生成记忆向量；EmbeddingModel 缺失或调用失败返回 null（检索端降级） */
    private float[] embedOrNull(String text) {
        if (embeddingModel == null) return null;
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.warn("记忆向量生成失败，存入 null: {}", e.getMessage());
            return null;
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=DefaultMemoryFacadeMergeTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/DefaultMemoryFacade.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/DefaultMemoryFacadeMergeTest.java
git commit -m "fix(memory): remember 生成真实向量、合并检测传真实向量、合并后重嵌"
```

---

### Task 5: RecallFlow 维度配置化

**Files:**
- Modify: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/RecallFlow.java`

- [ ] **Step 1: 改造维度来源**

`RecallFlow.java`：
1. 追加字段（`chatModel` 字段之后）：
```java
    /** 向量维度（无 EmbeddingModel 时零向量兜底的维度，来自配置） */
    @org.springframework.beans.factory.annotation.Value("${aether.memory.recall.vector-dimension:1024}")
    private int vectorDimension = 1024;
```
2. 删除 `private static final int DEFAULT_DIM = 1280;`（第 108 行），`embed()` 回退改为实例字段：
```java
    private float[] embed(String text) {
        if (embeddingModel == null) {
            // 无 EmbeddingModel 时回退：返回零向量（检索端会降级为时间排序）
            log.debug("EmbeddingModel 未配置，使用回退向量");
            return new float[vectorDimension];
        }
        return embeddingModel.embed(text);
    }
```

- [ ] **Step 2: 编译 + 既有测试回归**

Run: `mvn -pl aether-domain -am test -Dtest='Memory*,BuiltinMemoryProvider*' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（记忆相关既有测试全绿）

- [ ] **Step 3: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/RecallFlow.java
git commit -m "refactor(memory): RecallFlow 向量维度改为配置驱动（默认 1024）"
```

---

### Task 6: MemoryEmbeddingConfig 装配

**Files:**
- Create: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingConfig.java`
- Test: `aether/aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingConfigTest.java`

- [ ] **Step 1: 写失败测试**

新建 `MemoryEmbeddingConfigTest.java`（包 `cn.zcj.aether.domain.agent.service.memory.core`）：

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.ConditionContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryEmbeddingConfigTest {

    @Test
    void conditionRequiresNonBlankBaseUrl() {
        ConditionContext ctx = mock(ConditionContext.class);
        Environment env = mock(Environment.class);
        when(ctx.getEnvironment()).thenReturn(env);
        var cond = new MemoryEmbeddingConfig.MemoryEmbeddingConfiguredCondition();

        when(env.getProperty("aether.memory.embedding.base-url")).thenReturn(null);
        assertFalse(cond.matches(ctx, null));
        when(env.getProperty("aether.memory.embedding.base-url")).thenReturn("   ");
        assertFalse(cond.matches(ctx, null));
        when(env.getProperty("aether.memory.embedding.base-url")).thenReturn("http://localhost:11434");
        assertTrue(cond.matches(ctx, null));
    }

    @Test
    void buildsOpenAiEmbeddingModelFromConfig() {
        MemoryProperties props = new MemoryProperties();
        props.getEmbedding().setBaseUrl("http://localhost:11434");
        props.getEmbedding().setApiKey("key");
        props.getEmbedding().setPath("v1/embeddings");
        props.getEmbedding().setModel("bge-m3");

        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("openai");
        // 用真实 OpenAiApi 回答默认方法，避免构造时依赖 null
        when(provider.buildOpenAiApi(any(ModelConfig.class))).thenAnswer(inv -> {
            ModelConfig mc = inv.getArgument(0);
            return OpenAiApi.builder()
                .baseUrl(mc.getBaseUrl()).apiKey(mc.getApiKey()).embeddingsPath("v1/embeddings")
                .build();
        });
        ModelProviderRegistry registry = mock(ModelProviderRegistry.class);
        when(registry.getAllProviders()).thenReturn(List.of(provider));

        EmbeddingModel model = new MemoryEmbeddingConfig().memoryEmbeddingModel(props, registry);

        assertNotNull(model);
        ArgumentCaptor<ModelConfig> captor = ArgumentCaptor.forClass(ModelConfig.class);
        verify(provider).buildOpenAiApi(captor.capture());
        assertEquals("http://localhost:11434", captor.getValue().getBaseUrl());
        assertEquals("key", captor.getValue().getApiKey());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryEmbeddingConfigTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现配置类**

新建 `MemoryEmbeddingConfig.java`（包 `cn.zcj.aether.domain.agent.service.memory.core`）：

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.lang.Nullable;

/**
 * 记忆 Embedding 模型装配。
 *
 * <p>复用 {@link ModelProvider#buildOpenAiApi} 的超时双通道约定；
 * 未配置 {@code aether.memory.embedding.base-url} 时不注册 bean（语义检索降级为时间排序）。</p>
 */
@Slf4j
@Configuration
public class MemoryEmbeddingConfig {

    @Bean(name = "memoryEmbeddingModel")
    @Conditional(MemoryEmbeddingConfiguredCondition.class)
    @Nullable
    public EmbeddingModel memoryEmbeddingModel(MemoryProperties props, ModelProviderRegistry registry) {
        var cfg = props.getEmbedding();
        ModelConfig modelConfig = ModelConfig.builder()
            .baseUrl(cfg.getBaseUrl())
            .apiKey(cfg.getApiKey())
            .embeddingsPath(cfg.getPath() == null || cfg.getPath().isBlank() ? "v1/embeddings" : cfg.getPath())
            .build();

        ModelProvider provider = registry.getAllProviders().stream()
            .filter(p -> "openai".equals(p.providerName()))
            .findFirst()
            .orElseGet(() -> registry.getAllProviders().isEmpty() ? null : registry.getAllProviders().get(0));
        if (provider == null) {
            log.warn("无可用 ModelProvider，记忆 embedding 不可用（语义检索降级）");
            return null;
        }
        OpenAiApi api = provider.buildOpenAiApi(modelConfig);
        String model = cfg.getModel() == null || cfg.getModel().isBlank() ? "bge-m3" : cfg.getModel();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().withModel(model).build();
        log.info("记忆 Embedding 装配完成: baseUrl={}, model={}, dim={}",
            cfg.getBaseUrl(), model, cfg.getDimension());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    /** 条件：aether.memory.embedding.base-url 非空才装配 EmbeddingModel */
    public static class MemoryEmbeddingConfiguredCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String baseUrl = context.getEnvironment().getProperty("aether.memory.embedding.base-url");
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过 + 编译**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryEmbeddingConfigTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS；`mvn -pl aether-domain -am compile` 通过

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingConfig.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingConfigTest.java
git commit -m "feat(memory): 静态装配 OpenAiEmbeddingModel（base-url 非空条件，复用 buildOpenAiApi）"
```

---

### Task 7: MemoryEmbeddingBackfillRunner 回填编排

**Files:**
- Create: `aether/aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingBackfillRunner.java`
- Test: `aether/aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingBackfillRunnerTest.java`

- [ ] **Step 1: 写失败测试**

新建 `MemoryEmbeddingBackfillRunnerTest.java`（包 `cn.zcj.aether.domain.agent.service.memory.core`）：

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryEmbeddingBackfillRunnerTest {

    /** 内存版 VectorStore 桩：逐个吐出 missing，记录 updateEmbedding */
    static class StubVectorStore implements VectorStore {
        final Deque<MemoryRecord> missing;
        final List<String> updatedIds = new ArrayList<>();

        StubVectorStore(List<MemoryRecord> missing) { this.missing = new ArrayDeque<>(missing); }

        @Override public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
            List<MemoryRecord> batch = new ArrayList<>();
            for (int i = 0; i < limit && !missing.isEmpty(); i++) batch.add(missing.poll());
            return CompletableFuture.completedFuture(batch);
        }
        @Override public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
            updatedIds.add(id);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<List<MemorySearchResult>> search(
                float[] q, int topK, List<MemoryScope> scopes) { return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletableFuture<Void> upsert(String id, float[] v, MemoryRecord r) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> upsertBatch(List<MemoryRecord> rs) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> delete(String id) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> deleteByScope(MemoryScope s) { return CompletableFuture.completedFuture(null); }
        @Override public int dimension() { return 1024; }
    }

    private MemoryRecord rec(String id) {
        return MemoryRecord.builder().id(id).content("内容-" + id).scope(MemoryScope.global()).build();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<EmbeddingModel> providerStub(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @Test
    void backfillsAllRecords() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setBatchSize(2);
        props.getBackfill().setMaxPerRun(5);
        StubVectorStore store = new StubVectorStore(List.of(rec("a"), rec("b"), rec("c")));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertEquals(List.of("a", "b", "c"), store.updatedIds);
    }

    @Test
    void stopsAtMaxPerRun() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setBatchSize(50);
        props.getBackfill().setMaxPerRun(2);
        StubVectorStore store = new StubVectorStore(List.of(rec("r0"), rec("r1"), rec("r2")));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertEquals(List.of("r0", "r1"), store.updatedIds);
    }

    @Test
    void skipsFailedEmbeddingAndContinues() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setMaxPerRun(10);
        StubVectorStore store = new StubVectorStore(List.of(rec("x"), rec("y")));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new RuntimeException("boom"));

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertTrue(store.updatedIds.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryEmbeddingBackfillRunnerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现回填编排**

新建 `MemoryEmbeddingBackfillRunner.java`（包 `cn.zcj.aether.domain.agent.service.memory.core`）：

```java
package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 存量记忆向量回填编排。
 *
 * <p>应用就绪后异步扫描 embedding 缺失的记录，调用 EmbeddingModel 补向量。
 * 受 {@code aether.memory.backfill.*} 配置门控；未装配 EmbeddingModel 时静默跳过。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aether.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryEmbeddingBackfillRunner {

    private final VectorStore vectorStore;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties props;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-backfill");
        t.setDaemon(true);
        return t;
    });

    public MemoryEmbeddingBackfillRunner(VectorStore vectorStore,
                                         ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                         MemoryProperties props) {
        this.vectorStore = vectorStore;
        this.embeddingModelProvider = embeddingModelProvider;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isEnabled()) return;
        if (!props.getBackfill().isEnabled()) return;
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            log.info("未装配 EmbeddingModel，跳过存量记忆回填");
            return;
        }
        log.info("启动存量记忆回填: batchSize={}, maxPerRun={}",
            props.getBackfill().getBatchSize(), props.getBackfill().getMaxPerRun());
        executor.submit(() -> runBackfill(model));
    }

    /** 回填主循环（package-private 便于单测直接调用） */
    void runBackfill(EmbeddingModel model) {
        int total = 0;
        int maxPerRun = props.getBackfill().getMaxPerRun();
        while (total < maxPerRun) {
            List<MemoryRecord> missing = vectorStore.findMissingEmbeddings(props.getBackfill().getBatchSize()).join();
            if (missing.isEmpty()) {
                log.info("记忆回填完成，共 {} 条", total);
                return;
            }
            for (MemoryRecord rec : missing) {
                try {
                    float[] vec = model.embed(rec.getContent());
                    vectorStore.updateEmbedding(rec.getId(), vec).join();
                    total++;
                } catch (Exception e) {
                    log.warn("回填单条失败，跳过: id={}, error={}", rec.getId(), e.getMessage());
                }
                if (total >= maxPerRun) break;
            }
        }
        log.warn("记忆回填达到单次上限 {}，剩余未回填", maxPerRun);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=MemoryEmbeddingBackfillRunnerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 个测试全绿）

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingBackfillRunner.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/memory/core/MemoryEmbeddingBackfillRunnerTest.java
git commit -m "feat(memory): ApplicationReadyEvent 异步存量向量回填编排"
```

---

### Task 8: 配置文件更新

**Files:**
- Modify: `aether/aether-app/src/main/resources/application.yml`
- Modify: `aether/aether-app/src/main/resources/application-dev.yml`

- [ ] **Step 1: application.yml**

`application.yml` 的 `aether.memory` 段：将 `recall.vector-dimension: 1280` 改为 `1024`，并在 `backfill` 之后新增两段：

```yaml
    recall:
      max-results: 10            # prefetch 检索 Top-K（hermes supermemory max_recall_results）
      semantic-weight: 0.6       # 语义相似度权重
      recency-weight: 0.3        # 时间衰减权重
      importance-weight: 0.1     # 重要性权重
      consolidation-threshold: 0.85  # 相似度合并阈值
      vector-dimension: 1024     # 向量维度（pgvector，与 embedding 模型一致）
    embedding:                   # 记忆向量生成（未配置 base-url 则语义检索降级）
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY:}
      path: v1/embeddings
      model: bge-m3
      dimension: 1024
    backfill:                    # 存量 NULL 向量回填
      enabled: true
      batch-size: 50
      max-per-run: 500
```

- [ ] **Step 2: application-dev.yml**

`application-dev.yml` 无需改动（`aether.memory` 段继承 application.yml），确认 `pgvector.enabled: true` 保持。

- [ ] **Step 3: 启动冒烟（可选）**

Run: `cd aether && mvn -pl aether-app -am compile -DskipTests`
Expected: 编译通过

- [ ] **Step 4: 提交**

```bash
git add aether-app/src/main/resources/application.yml aether-app/src/main/resources/application-dev.yml
git commit -m "chore(memory): 配置新增 embedding/backfill 段，向量维度改 1024"
```

---

### Task 9: docs/postgresql_schema.sql 更新

**Files:**
- Modify: `aether/docs/postgresql_schema.sql`

- [ ] **Step 1: 更新 schema 文档**

`docs/postgresql_schema.sql` 的 `aether_memories` 段：
1. 块注释中 `'[x1,x2,...,x1280]'` → `'[x1,x2,...,x1024]'`；`ALTER ... TYPE vector(1280)` → `vector(1024)`。
2. 末尾索引注释段替换为 HNSW 说明：

```sql
-- [需 pgvector] HNSW 向量索引 — 安装 pgvector 且 embedding 列已 ALTER 为 vector(1024) 后取消注释:
-- CREATE INDEX IF NOT EXISTS idx_memories_embedding_hnsw ON aether_memories
--     USING hnsw (embedding vector_cosine_ops);
-- 应用启动时也会幂等尝试创建该索引（失败仅告警）。
```
3. `COMMENT ON COLUMN aether_memories.embedding` 中 `vector(1280)` → `vector(1024)`。

- [ ] **Step 2: 提交**

```bash
git add docs/postgresql_schema.sql
git commit -m "docs(memory): aether_memories 向量维度 1280→1024，索引说明改 HNSW"
```

---

### Task 10: 全量测试验证与收尾

- [ ] **Step 1: 全量测试**

Run:
```bash
cd aether
mvn -pl aether-domain -am test -Dtest='Memory*,BuiltinMemoryProvider*,DefaultMemoryFacade*' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aether-infrastructure -am test -Dtest='Pgvector*' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。若 `DefaultMemoryFacade` 或既有 `memory/core/*` 测试出现回归，先修复再继续。

- [ ] **Step 2: 全量编译**

Run: `cd aether && mvn -pl aether-app -am compile -DskipTests`
Expected: 编译通过

- [ ] **Step 3: 逻辑核对（对照设计文档 §4 数据流）**

- [ ] `DefaultMemoryFacade.remember` 新建记录 `upsert(id, vector, record)` 中 vector 来自 `embedOrNull(content)`（不再传 null）。
- [ ] 合并检测 `vectorStore.search(vector, 3, [scope])` 传真实向量。
- [ ] `PgvectorVectorStore.search` 使用 `buildCosineSearchSql`，无效向量/异常回退 `searchByRecency`。
- [ ] `MemoryStore.search` 余弦排序、跳过 `"null"` embedding 文件。
- [ ] `MemoryEmbeddingBackfillRunner.onReady` 门控齐全（enabled/backfill.enabled/EmbeddingModel 存在）。

- [ ] **Step 4: 提交任何收尾修改**

```bash
git add -A
git commit -m "chore(memory): 真实召回改造回归修复"
```
（若无修改则跳过本步。）

---

## 执行修正记录（2026-08-07 实施时发现并已修正的计划偏差）

以下为 subagent 实施过程中发现并修正的问题，**以代码为准**：

| # | 计划原文 | 实际修正 | 提交 |
|---|---|---|---|
| 1 | Task 3 `findMissingEmbeddings` 用 `this::mapRow`（返回 `List<MemorySearchResult>`） | 接口需返回 `List<MemoryRecord>`；抽出 `extractRecord(ResultSet)` 供 `mapRow` 与回填 ResultSetExtractor 共用 | `e15224b` |
| 2 | Task 3 测试 `sql.chars()...length()` | `IntStream` 无 `length()`，改 `.count()` | `e15224b` |
| 3 | Task 6 `OpenAiEmbeddingOptions.builder().withModel(model)` | 真实 spring-ai 1.1.0-M3 API 为 `.model(String)` | `77c37b9f` |
| 4 | Task 6 测试构造真实 `OpenAiApi` | wrench fat-jar 遮蔽 `Jackson2ObjectMapperBuilder.yaml()` → NoSuchMethodError；改 mock `OpenAiApi` | `77c37b9f` |
| 5 | Task 7 `runBackfill` 无失败保护 | embedding 持续失败会无限空转（maxPerRun 只计成功）；加 `batchSuccess==0` 整批失败即停守卫 | `c47dc237` |
| 6 | Task 2/3 之外：`aether-infrastructure/pom.xml` 需补测试依赖与 surefire 排除 | 新增 `spring-boot-starter-test`（测试编译）+ `classpathDependencyExcludes` 排除 wrench fat-jar 捆绑的旧 logback-classic（否则 infra 测试 AbstractMethodError） | `e15224b`、`20fd194` |
| 7 | Task 6 类级缺 `@ConditionalOnProperty(aether.memory.enabled)` | 按设计 §3.1 补类级条件 | `994277d` |

环境注记：构建实际使用 Maven 本地仓库 `D:\apache-maven-3.9.9\mvn_repo`（非 `~/.m2`）；`xfg-wrench-starter-design-framework-3.0.0.jar` 为 fat-jar，捆绑旧版 logback-classic(1.2.x) 与缺失 `yaml()` 的 `Jackson2ObjectMapperBuilder`，在测试类路径上造成两次遮蔽——均已在相关模块的 surefire 配置或测试中规避。

---

## Self-Review 记录（写计划时已对照设计文档）

**Spec coverage:**
- 写入向量生成 → Task 4
- 合并检测传真实向量 + 合并后重嵌 → Task 4
- pgvector 余弦 SQL + HNSW 索引 + 降级回退 → Task 3
- MemoryStore 余弦 + 删除伪打分 → Task 2
- 双后端维度 1024 + DDL 文档 → Task 2/3/8/9
- 存量回填（异步/开关/上限/失败跳过）→ Task 7
- EmbeddingModel 静态装配（base-url 条件，降级）→ Task 6
- 配置 → Task 1/8
- 测试 → Task 2/3/4/6/7 + 既有测试回归

**Placeholder scan:** 无 TBD/TODO；每个改代码步骤均含完整代码。

**Type consistency:**
- `MemoryRecord` 字段与 `@Value @Builder` 一致（id/content/embedding/scope/importance/source 等）。
- `VectorStore` 新增两方法签名在 Task 2/3/4/7 各桩实现一致。
- `StoreOptions` = `MemoryFacade.StoreOptions`（record，(boolean useLLMEncoding, float consolidationThreshold)）。
- `OpenAiEmbeddingModel(OpenAiApi, MetadataMode, OpenAiEmbeddingOptions)` 为 spring-ai 1.1.0-M3 构造函数（已 javap 验证无 builder）。
- `EmbeddingModel.embed(String)` 返回 `float[]`（已 javap 验证）。
- `ChatResponse.builder().generations(...)`、`Generation(AssistantMessage)` 已 javap 验证。
- 已知假设：`MemoryEmbeddingConfig` 中 `ModelProvider.buildOpenAiApi` 为接口 default 方法（已读源码确认）；Mockito `when(provider.buildOpenAiApi(any()))` 仅用于测试桩（默认方法可 mock）。

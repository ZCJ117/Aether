package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Pgvector 向量存储实现。
 * 使用 PostgreSQL + pgvector 扩展进行向量相似度搜索。
 *
 * 前置条件：
 * 1. PostgreSQL 数据库已安装 pgvector 扩展
 * 2. 执行初始化 SQL 创建表：
 *    CREATE EXTENSION IF NOT EXISTS vector;
 *    CREATE TABLE IF NOT EXISTS aether_memories (
 *        id VARCHAR(64) PRIMARY KEY,
 *        content TEXT NOT NULL,
 *        embedding vector(1280),
 *        scope_path VARCHAR(512),
 *        scope_private BOOLEAN DEFAULT false,
 *        categories JSONB DEFAULT '[]',
 *        importance REAL DEFAULT 0.5,
 *        metadata JSONB DEFAULT '{}',
 *        source VARCHAR(64) DEFAULT 'agent_extracted',
 *        created_at TIMESTAMP DEFAULT NOW(),
 *        last_accessed_at TIMESTAMP DEFAULT NOW(),
 *        access_count INTEGER DEFAULT 0
 *    );
 *    CREATE INDEX IF NOT EXISTS idx_memories_embedding ON aether_memories
 *        USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
 *
 * 配置：
 *   spring.datasource.url=jdbc:postgresql://localhost:5432/aether
 *   aether.memory.pgvector.enabled=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.memory.pgvector.enabled", havingValue = "true")
public class PgvectorVectorStore implements VectorStore {

    private static final int DEFAULT_DIMENSION = 1024;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 向量维度（来自配置，默认 1024） */
    @Value("${aether.memory.recall.vector-dimension:1024}")
    private int vectorDimension = DEFAULT_DIMENSION;

    private final JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public PgvectorVectorStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        log.info("PgvectorVectorStore 已初始化，向量维度: {}", DEFAULT_DIMENSION);
    }

    @Override
    public CompletableFuture<Void> upsert(String id, float[] vector, MemoryRecord record) {
        return CompletableFuture.runAsync(() -> {
            try {
                String embeddingStr = vectorToDbString(vector);
                String categoriesJson = objectMapper.writeValueAsString(
                    record.getCategories() != null ? record.getCategories() : List.of());
                String metadataJson = record.getMetadata() != null
                    ? objectMapper.writeValueAsString(record.getMetadata()) : "{}";

                // 当 embedding 为 null 时直接存 TEXT，不依赖 pgvector 扩展
                jdbc.update(conn -> {
                    PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO aether_memories (id, content, embedding, scope_path, scope_private, " +
                        "   categories, importance, metadata, source, created_at, last_accessed_at, access_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?) " +
                        "ON CONFLICT (id) DO UPDATE SET " +
                        "   content = EXCLUDED.content, " +
                        "   embedding = EXCLUDED.embedding, " +
                        "   importance = EXCLUDED.importance, " +
                        "   last_accessed_at = EXCLUDED.last_accessed_at, " +
                        "   access_count = aether_memories.access_count + 1");
                    ps.setString(1, id);
                    ps.setString(2, record.getContent());
                    ps.setString(3, embeddingStr);
                    ps.setString(4, record.getScope() != null ? record.getScope().path() : "");
                    ps.setBoolean(5, record.getScope() != null && record.getScope().isPrivate());
                    ps.setString(6, categoriesJson);
                    ps.setFloat(7, record.getImportance());
                    ps.setString(8, metadataJson);
                    ps.setString(9, record.getSource() != null ? record.getSource() : "agent_extracted");
                    ps.setTimestamp(10, record.getCreatedAt() != null
                        ? java.sql.Timestamp.from(record.getCreatedAt())
                        : new java.sql.Timestamp(System.currentTimeMillis()));
                    ps.setTimestamp(11, record.getLastAccessedAt() != null
                        ? java.sql.Timestamp.from(record.getLastAccessedAt())
                        : new java.sql.Timestamp(System.currentTimeMillis()));
                    ps.setInt(12, record.getAccessCount());
                    return ps;
                });
            } catch (Exception e) {
                log.warn("Pgvector upsert 失败: id={}, error={}", id, e.getMessage());
            }
        }, executor);
    }

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

    @Override
    public CompletableFuture<Void> upsertBatch(List<MemoryRecord> records) {
        CompletableFuture<?>[] futures = records.stream()
            .map(r -> upsert(r.getId(), r.getEmbedding(), r))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @Override
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() ->
            jdbc.update("DELETE FROM aether_memories WHERE id = ?", id), executor);
    }

    @Override
    public CompletableFuture<Void> deleteByScope(MemoryScope scope) {
        return CompletableFuture.runAsync(() ->
            jdbc.update("DELETE FROM aether_memories WHERE scope_path LIKE ?",
                scope.path() + "%"), executor);
    }

    @Override
    public int dimension() {
        return vectorDimension;
    }

    @Override
    public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return jdbc.query(
                    "SELECT id, content, scope_path, scope_private, categories, importance, source, " +
                    "created_at, last_accessed_at, access_count " +
                    "FROM aether_memories WHERE embedding IS NULL LIMIT ?",
                    ps -> ps.setInt(1, limit), rs -> {
                        List<MemoryRecord> list = new ArrayList<>();
                        while (rs.next()) {
                            try {
                                list.add(extractRecord(rs));
                            } catch (JsonProcessingException e) {
                                log.warn("回填扫描记录反序列化失败: id={}", rs.getString("id"));
                            }
                        }
                        return list;
                    });
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

    // =========================================================
    // 内部方法
    // =========================================================

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

    /** 将 float[] 转为 pgvector 可接受的字符串格式 '[1.0,2.0,3.0,...]' */
    private String vectorToDbString(float[] vector) {
        if (vector == null || vector.length == 0) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private List<MemorySearchResult> mapRow(ResultSet rs) throws SQLException {
        List<MemorySearchResult> results = new ArrayList<>();
        while (rs.next()) {
            try {
                double similarity = rs.getDouble("similarity");
                results.add(new MemorySearchResult(extractRecord(rs), similarity));
            } catch (JsonProcessingException e) {
                log.warn("Pgvector 搜索结果反序列化失败: id={}", rs.getString("id"));
            }
        }
        return results;
    }

    /** 从 ResultSet 构建 MemoryRecord（不含 similarity），供回填与搜索复用 */
    private MemoryRecord extractRecord(ResultSet rs) throws SQLException, JsonProcessingException {
        @SuppressWarnings("unchecked")
        List<String> categories = objectMapper.readValue(rs.getString("categories"), List.class);
        return MemoryRecord.builder()
            .id(rs.getString("id"))
            .content(rs.getString("content"))
            .scope(new MemoryScope(rs.getString("scope_path"), rs.getBoolean("scope_private")))
            .categories(categories)
            .importance(rs.getFloat("importance"))
            .source(rs.getString("source"))
            .createdAt(toInstant(rs.getTimestamp("created_at")))
            .lastAccessedAt(toInstant(rs.getTimestamp("last_accessed_at")))
            .accessCount(rs.getInt("access_count"))
            .isPrivate(rs.getBoolean("scope_private"))
            .build();
    }

    private Instant toInstant(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}

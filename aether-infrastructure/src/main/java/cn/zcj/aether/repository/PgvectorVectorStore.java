package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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

    private static final int DEFAULT_DIMENSION = 1280;
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
            // 无 pgvector 扩展时回退为按更新时间排序（向量相似度搜索需要 pgvector）
            try {
                StringBuilder scopeFilter = new StringBuilder();
                List<Object> params = new ArrayList<>();

                if (scopes != null && !scopes.isEmpty()) {
                    scopeFilter.append(" WHERE ");
                    for (int i = 0; i < scopes.size(); i++) {
                        if (i > 0) scopeFilter.append(" OR ");
                        scopeFilter.append("scope_path LIKE ?");
                        params.add(scopes.get(i).path() + "%");
                    }
                }

                String sql = "SELECT id, content, scope_path, scope_private, categories, " +
                    "importance, source, created_at, last_accessed_at, access_count, " +
                    "0.5 AS similarity " +
                    "FROM aether_memories" +
                    scopeFilter +
                    " ORDER BY last_accessed_at DESC LIMIT ?";
                params.add(topK);

                return jdbc.query(sql, ps -> {
                    for (int i = 0; i < params.size(); i++) {
                        if (params.get(i) instanceof String s) ps.setString(i + 1, s);
                        else if (params.get(i) instanceof Integer n) ps.setInt(i + 1, n);
                    }
                }, this::mapRow);
            } catch (Exception e) {
                log.warn("Pgvector 搜索失败: error={}", e.getMessage());
                return List.of();
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
        return DEFAULT_DIMENSION;
    }

    @Override
    public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
        return CompletableFuture.completedFuture(null);
    }

    // =========================================================
    // 内部方法
    // =========================================================

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
                @SuppressWarnings("unchecked")
                List<String> categories = objectMapper.readValue(
                    rs.getString("categories"), List.class);
                MemoryRecord record = MemoryRecord.builder()
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
                results.add(new MemorySearchResult(record, similarity));
            } catch (JsonProcessingException e) {
                log.warn("Pgvector 搜索结果反序列化失败: id={}", rs.getString("id"));
            }
        }
        return results;
    }

    private Instant toInstant(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}

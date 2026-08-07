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

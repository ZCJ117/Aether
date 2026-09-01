package cn.zcj.aether.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.2): RRF 混合检索 SQL 生成单测 —— 双路/单路形态与占位符数量。
 */
class PgHybridSearchRepositorySqlTest {

    @Test
    void dualPathSqlContainsBothCteAndRrf() {
        String sql = PgHybridSearchRepository.buildHybridSearchSql(2, true, 60);
        assertTrue(sql.contains("semantic AS"), "语义 CTE");
        assertTrue(sql.contains("lexical AS"), "词法 CTE");
        assertTrue(sql.contains("FULL OUTER JOIN lexical l ON s.id = l.id"), "RRF 需要全外连接");
        assertTrue(sql.contains("1.0/(60 + s.rank)"), "RRF k=60");
        assertTrue(sql.contains("to_tsvector('simple', content_bigram)"));
        assertTrue(sql.contains("plainto_tsquery('simple', ?)"));
        assertTrue(sql.contains("archived = false"), "归档记忆过滤");
        assertTrue(sql.contains("ORDER BY rrf_score DESC LIMIT ?"));
    }

    @Test
    void rrfKIsParameterized() {
        assertTrue(PgHybridSearchRepository.buildHybridSearchSql(0, true, 120).contains("1.0/(120 + s.rank)"),
                "rrfK 必须来自 aether.rag.hybrid.rrf-k 配置而非硬编码");
    }

    @Test
    void placeholderCountMatchesBindingOrder() {
        // 语义路: vec, 2×scope, vec, limit = 5；词法路: lex, lex, 2×scope, lex, limit = 6；final = 1
        String dual = PgHybridSearchRepository.buildHybridSearchSql(2, true, 60);
        assertEquals(12, dual.chars().filter(c -> c == '?').count());

        // 语义路: vec, vec, limit = 3；词法路: lex×3, limit = 4；final = 1
        String noScope = PgHybridSearchRepository.buildHybridSearchSql(0, true, 60);
        assertEquals(8, noScope.chars().filter(c -> c == '?').count());
    }
}

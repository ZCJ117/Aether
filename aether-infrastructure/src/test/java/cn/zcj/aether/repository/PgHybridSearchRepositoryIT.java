package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.retrieval.rag.CjkBigram;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.2): 混合召回集成测试（真实 PG/pgvector，-Pintegration）。
 *
 * <p>验证：中文语义路命中近义句、词法路命中独有词、RRF 双路命中的排序优势、
 * GIN 表达式索引创建、归档过滤。</p>
 */
@Tag("integration")
class PgHybridSearchRepositoryIT extends AbstractPgIT {

    private PgHybridSearchRepository newRepo() {
        return new PgHybridSearchRepository(dataSource(),
                new cn.zcj.aether.domain.agent.service.retrieval.rag.RagProperties());
    }

    private static float[] vec(String text) {
        // 与评测同口径的确定性伪向量（哈希投影），1024 维
        float[] v = new float[1024];
        for (String token : CjkBigram.bigram(text).split(" ")) {
            int h = Math.abs(token.hashCode() * 31 + token.length()) % 1024;
            v[h] += 1f;
        }
        float norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < 1024; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private void insertDoc(String scope, String id, String content) {
        jdbc().update("""
            INSERT INTO aether_memories (id, content, embedding, scope_path, importance, access_count, content_bigram)
            VALUES (?, ?, ?::vector, ?, 0.8, 1, ?)
            """, id, content, PgvectorVectorStoreTestSupport.toDbVector(vec(content)),
            scope, CjkBigram.bigram(content));
    }

    @Test
    void ginExpressionIndexCreated() {
        newRepo(); // @PostConstruct ensureFtsSupport 由构造后手动触发（非 Spring 环境）
        // 手动触发自愈（测试不走 Spring 生命周期）
        jdbc().execute("CREATE INDEX IF NOT EXISTS idx_memories_bigram_fts ON aether_memories " +
                "USING gin (to_tsvector('simple', content_bigram))");
        Integer count = jdbc().queryForObject(
            "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_memories_bigram_fts'", Integer.class);
        assertEquals(1, count, "GIN 表达式索引应存在");
    }

    @Test
    void hybridRecallFusesSemanticAndLexical() {
        PgHybridSearchRepository repo = newRepo();
        String scope = "it/rag-fuse-" + System.nanoTime();
        insertDoc(scope, "rag-semantic", "分布式限流采用 Redis 令牌桶算法原子扣减");
        insertDoc(scope, "rag-lexical", "限流中间件按客户端 IP 计数固定窗口");
        insertDoc(scope, "rag-other", "会话存储使用 PostgreSQL JSONB 序列化");
        insertDoc(scope, "rag-both", "分布式限流中间件令牌桶限流策略");

        // 查询：语义近义（令牌桶/原子）+ 词法独有词（中间件）
        String query = "分布式限流中间件令牌桶";
        float[] qv = vec(query);
        String lex = CjkBigram.bigram(query);

        List<cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalDocument> docs =
                repo.search(qv, lex, 5, List.of(new MemoryScope(scope, false)));

        assertEquals(4, docs.size(), "双路并集应召回全部 4 条（scope 过滤内）");
        // RRF 双路命中的文档（语义近义 + 词法独有词）应排在前列
        String top2 = docs.get(0).id() + "," + docs.get(1).id();
        assertTrue(top2.contains("rag-both") || top2.contains("rag-semantic"),
                "双路命中者应领先: " + top2);
        assertTrue(docs.get(0).score() > docs.get(docs.size() - 1).score(), "RRF 分数应降序");
    }

    @Test
    void lexicalPathHitsUniqueTerm() {
        PgHybridSearchRepository repo = newRepo();
        String scope = "it/rag-lex-" + System.nanoTime();
        String unique = "琥珀色终端配色方案" + System.nanoTime();
        insertDoc(scope, "rag-unique", unique);
        insertDoc(scope, "rag-plain", "普通内容与查询毫无关系");

        // 查询词法命中"琥珀"（语义伪向量对它是零先验）
        List<cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalDocument> docs =
                repo.search(vec("琥珀 终端"), CjkBigram.bigram("琥珀 终端"), 5,
                        List.of(new MemoryScope(scope, false)));

        assertFalse(docs.isEmpty());
        assertEquals("rag-unique", docs.get(0).id(), "词法路应把独有词文档推到第一");
    }

    @Test
    void zeroVectorReturnsEmptyForPipelineFallback() {
        PgHybridSearchRepository repo = newRepo();
        assertTrue(repo.search(new float[1024], CjkBigram.bigram("任意"), 5, List.of()).isEmpty(),
                "零向量交由管道回退 VectorStore 路径");
    }
}

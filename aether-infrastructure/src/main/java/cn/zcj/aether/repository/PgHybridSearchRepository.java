package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.retrieval.rag.CjkBigram;
import cn.zcj.aether.domain.agent.service.retrieval.rag.HybridSearchPort;
import cn.zcj.aether.domain.agent.service.retrieval.rag.RagProperties;
import cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * P1(4.2): 混合召回 PG 实现 —— pgvector 语义路 + PG 全文（bigram tsvector GIN）词法路，
 * RRF（Reciprocal Rank Fusion, k=60）在<b>单条 SQL 内</b>完成融合，零新组件（roadmap 4.2 第 2 步）。
 *
 * <p>中文词法：查询与内容均经 {@link CjkBigram#bigram} 归一化为空格分隔 token，
 * 配合 {@code to_tsvector('simple', content_bigram)} + GIN 表达式索引，无需 zhparser 扩展。</p>
 *
 * <p>占位符顺序（scopeCount&gt;0 时）：
 * {@code [queryVec, scopes×n, queryVec, vecLimit] + [lexQ, lexQ, scopes×n, lexQ, lexLimit] + finalLimit}</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.memory.pgvector.enabled", havingValue = "true")
public class PgHybridSearchRepository implements HybridSearchPort {

    private final JdbcTemplate jdbc;
    /** RAG 配置唯一来源（aether.rag.hybrid.*：topK/rrfK），无独立 @Value 重复绑定。 */
    private final RagProperties ragProperties;

    public PgHybridSearchRepository(DataSource dataSource, RagProperties ragProperties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    void ensureFtsSupport() {
        try {
            jdbc.execute("ALTER TABLE aether_memories ADD COLUMN IF NOT EXISTS content_bigram TEXT");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memories_bigram_fts ON aether_memories " +
                "USING gin (to_tsvector('simple', content_bigram))");
            log.info("RAG 混合检索 FTS 支撑（content_bigram + GIN）已就绪");
        } catch (Exception e) {
            log.warn("content_bigram 列/索引创建失败: {}", e.getMessage());
        }
        backfillBigrams();
    }

    /** 存量行 bigram 回填（Java 侧生成，上限 5000 行；空表零开销，新写入行由 upsert 自动携带）。 */
    void backfillBigrams() {
        try {
            List<Object[]> rows = jdbc.query(
                "SELECT id, content FROM aether_memories WHERE content_bigram IS NULL LIMIT 5000",
                (rs, i) -> new Object[]{CjkBigram.bigram(rs.getString("content")), rs.getString("id")});
            if (rows.isEmpty()) {
                return;
            }
            jdbc.batchUpdate("UPDATE aether_memories SET content_bigram = ? WHERE id = ?", rows);
            log.info("RAG bigram 存量回填完成: {} 行", rows.size());
        } catch (Exception e) {
            log.warn("bigram 存量回填失败（忽略，新写入行自动携带）: {}", e.getMessage());
        }
    }

    @Override
    public List<RetrievalDocument> search(float[] queryVector, String lexicalQuery, int topK,
                                          List<MemoryScope> scopes) {
        // 有效向量必需（语义/词法双路）；null/零向量交由管道回退 VectorStore 纯向量/时间序路径
        if (queryVector == null || isZero(queryVector)) {
            return List.of();
        }
        String vec = toDbVector(queryVector);
        String lex = lexicalQuery == null || lexicalQuery.isBlank() ? null : lexicalQuery;
        List<MemoryScope> scopeList = scopes == null ? List.of() : scopes;

        try {
            int rrfK = Math.max(1, ragProperties.getHybrid().getRrfK());
            String sql = buildHybridSearchSql(scopeList.size(), lex != null, rrfK);
            return jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                bind(ps, scopeList, vec, lex,
                        ragProperties.getHybrid().getVectorTopK(),
                        ragProperties.getHybrid().getLexicalTopK(), topK);
                return ps;
            }, this::mapRow);
        } catch (Exception e) {
            log.warn("混合召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 单测可见：语义 CTE + 词法 CTE + RRF 融合 SQL。scopeCount 为 LIKE 占位组数。
     * rrf = Σ 1/(k + rank)，k 来自 aether.rag.hybrid.rrf-k（标准取值 60）。
     */
    static String buildHybridSearchSql(int scopeCount, boolean hasLexical, int rrfK) {
        StringBuilder scopeFilter = new StringBuilder();
        if (scopeCount > 0) {
            scopeFilter.append(" AND (");
            for (int i = 0; i < scopeCount; i++) {
                if (i > 0) {
                    scopeFilter.append(" OR ");
                }
                scopeFilter.append("scope_path LIKE ?");
            }
            scopeFilter.append(")");
        }

        StringBuilder sql = new StringBuilder("""
                WITH semantic AS (
                    SELECT id, content, scope_path, scope_private, importance, source,
                           created_at, last_accessed_at, access_count,
                           ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rank
                    FROM aether_memories
                    WHERE embedding IS NOT NULL AND archived = false""");
        sql.append(scopeFilter);
        sql.append("\n                    ORDER BY embedding <=> ?::vector LIMIT ?\n                )");

        if (hasLexical) {
            sql.append(",\n                lexical AS (\n");
            sql.append("""
                        SELECT id,
                               ROW_NUMBER() OVER (ORDER BY ts_rank(to_tsvector('simple', content_bigram),
                                   plainto_tsquery('simple', ?)) DESC) AS rank
                        FROM aether_memories
                        WHERE content_bigram IS NOT NULL AND archived = false
                          AND to_tsvector('simple', content_bigram) @@ plainto_tsquery('simple', ?)""");
            sql.append(scopeFilter);
            sql.append("""

                        ORDER BY ts_rank(to_tsvector('simple', content_bigram),
                            plainto_tsquery('simple', ?)) DESC
                        LIMIT ?)""");
        }

        sql.append("""

                SELECT COALESCE(s.id, l.id) AS id,
                       s.content, s.scope_path, s.scope_private, s.importance, s.source,
                       s.created_at, s.last_accessed_at, s.access_count,
                       COALESCE(1.0/(""").append(rrfK).append(" + s.rank), 0) + COALESCE(1.0/(")
                .append(rrfK).append("""
                         + l.rank), 0) AS rrf_score
                FROM semantic s
                """);
        if (hasLexical) {
            sql.append("FULL OUTER JOIN lexical l ON s.id = l.id\n");
        }
        sql.append("                ORDER BY rrf_score DESC LIMIT ?");
        return sql.toString();
    }

    private void bind(PreparedStatement ps, List<MemoryScope> scopes, String vec, String lex,
                      int vectorTopK, int lexicalTopK, int finalTopK) throws SQLException {
        int i = 1;
        ps.setString(i++, vec);
        for (MemoryScope sc : scopes) {
            ps.setString(i++, sc.path() + "%");
        }
        ps.setString(i++, vec);
        ps.setInt(i++, vectorTopK);
        if (lex != null) {
            ps.setString(i++, lex);  // SELECT 子句 ts_rank
            ps.setString(i++, lex);  // WHERE @@ 匹配
            for (MemoryScope sc : scopes) {
                ps.setString(i++, sc.path() + "%");
            }
            ps.setString(i++, lex);  // ORDER BY ts_rank
            ps.setInt(i++, lexicalTopK);
        }
        ps.setInt(i, finalTopK);
    }

    private List<RetrievalDocument> mapRow(ResultSet rs) throws SQLException {
        List<RetrievalDocument> results = new ArrayList<>();
        while (rs.next()) {
            results.add(new RetrievalDocument(
                    rs.getString("id"),
                    rs.getString("content"),
                    rs.getDouble("rrf_score"),
                    "rrf-hybrid"));
        }
        return results;
    }

    private static boolean isZero(float[] v) {
        for (float f : v) {
            if (f != 0f) {
                return false;
            }
        }
        return true;
    }

    private static String toDbVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        return sb.append("]").toString();
    }
}

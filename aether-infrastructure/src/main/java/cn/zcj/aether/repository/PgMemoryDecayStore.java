package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.lifecycle.MemoryDecayStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * P1(4.3): 记忆衰减 PG 实现 —— 保留分 SQL 内计算 + 批量软删归档。
 *
 * <p>保留分 = 0.5×新近度（半衰线性）+ 0.3×频次（access_count/10 封顶）+ 0.2×importance；
 * 新近度 = max(0, 1 - 距 last_accessed_at 秒数 / (halfLifeDays×86400))。
 * 归档（archived=true）而非物理删除：召回不可见，但可通过再次 upsert 复活。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.memory.pgvector.enabled", havingValue = "true")
public class PgMemoryDecayStore implements MemoryDecayStore {

    private static final String ARCHIVE_SQL = """
        WITH candidates AS (
            SELECT id,
                   0.5 * GREATEST(0, 1 - EXTRACT(EPOCH FROM (NOW() - last_accessed_at)) / (? * 86400.0))
                 + 0.3 * LEAST(1.0, access_count / 10.0)
                 + 0.2 * importance AS retention
            FROM aether_memories
            WHERE archived = false
            ORDER BY last_accessed_at ASC
            LIMIT ?
        )
        UPDATE aether_memories m
           SET archived = true
          FROM candidates c
         WHERE m.id = c.id
           AND c.retention < ?
        """;

    private final JdbcTemplate jdbc;

    public PgMemoryDecayStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public int archiveStale(double halfLifeDays, double minRetentionScore, int batchSize) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement(ARCHIVE_SQL);
            ps.setDouble(1, halfLifeDays);
            ps.setInt(2, batchSize);
            ps.setDouble(3, minRetentionScore);
            return ps;
        });
    }

    @Override
    public long countActive() {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM aether_memories WHERE archived = false", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public long countArchived() {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM aether_memories WHERE archived = true", Long.class);
        return n == null ? 0 : n;
    }
}

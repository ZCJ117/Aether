package cn.zcj.aether.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

/**
 * P1(1.3): 会话统计聚合表仓储（dashboard_stats）——
 * DashboardStatsConsumer 按 agentId 增量 upsert，替代仪表盘实时聚合查询。
 */
@Slf4j
@Repository
public class DashboardStatsRepository {

    private static final String UPSERT_DELTA_SQL = """
        INSERT INTO dashboard_stats
            (agent_id, sessions_total, turns_total, tool_calls_total,
             tool_errors_total, tokens_total, cost_usd, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
        ON CONFLICT (agent_id) DO UPDATE SET
            sessions_total    = dashboard_stats.sessions_total    + EXCLUDED.sessions_total,
            turns_total       = dashboard_stats.turns_total       + EXCLUDED.turns_total,
            tool_calls_total  = dashboard_stats.tool_calls_total  + EXCLUDED.tool_calls_total,
            tool_errors_total = dashboard_stats.tool_errors_total + EXCLUDED.tool_errors_total,
            tokens_total      = dashboard_stats.tokens_total      + EXCLUDED.tokens_total,
            cost_usd          = dashboard_stats.cost_usd          + EXCLUDED.cost_usd,
            updated_at        = NOW()
        """;

    private final JdbcTemplate jdbcTemplate;

    public DashboardStatsRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 增量聚合一次事件（调用方需先用 ProcessedEventRepository 幂等去重）。 */
    public void applyDelta(String agentId, long sessions, long turns, long toolCalls,
                           long toolErrors, long tokens, double costUsd) {
        jdbcTemplate.update(UPSERT_DELTA_SQL,
                agentId, sessions, turns, toolCalls, toolErrors, tokens, costUsd);
    }
}

package cn.zcj.aether.infrastructure.persistence;

import cn.zcj.aether.domain.agent.service.chat.DashboardStatsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

/**
 * P1(1.3): dashboard_stats 读模型 PG 实现 ——
 * 仅在 {@code aether.dashboard.stats-source=kafka} 时装配（默认 live 实时查询，行为不变）。
 */
@Repository
@ConditionalOnProperty(name = "aether.dashboard.stats-source", havingValue = "kafka")
public class PgDashboardStatsStore implements DashboardStatsStore {

    private final JdbcTemplate jdbcTemplate;

    public PgDashboardStatsStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public List<AgentStatsSnapshot> loadAll() {
        return jdbcTemplate.query(
            "SELECT agent_id, sessions_total, turns_total, tool_calls_total, " +
            "tool_errors_total, tokens_total, cost_usd FROM dashboard_stats",
            (rs, i) -> new AgentStatsSnapshot(
                    rs.getString("agent_id"),
                    rs.getLong("sessions_total"),
                    rs.getLong("turns_total"),
                    rs.getLong("tool_calls_total"),
                    rs.getLong("tool_errors_total"),
                    rs.getLong("tokens_total"),
                    rs.getDouble("cost_usd")));
    }
}

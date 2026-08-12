package cn.zcj.aether.infrastructure.deleg;

import cn.zcj.aether.domain.agent.service.subagent.AsyncDelegationStore;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL 异步委派仓储 — 对齐 hermes async_delegations 表（async_delegation.py L142）。
 * <p>激活条件：PostgreSQL 驱动可用 + aether.delegation.persistence=true（跟随 PgSessionRepository 模式）。
 * 测试无真实 DB：Mockito mock JdbcTemplate（跟随 PgvectorVectorStoreSqlTest 基建）。</p>
 */
@Slf4j
@Repository
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnProperty(name = "aether.delegation.persistence", havingValue = "true", matchIfMissing = false)
public class PgAsyncDelegationStore implements AsyncDelegationStore {

    private final JdbcTemplate jdbcTemplate;

    public PgAsyncDelegationStore(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
        log.info("PgAsyncDelegationStore 已初始化");
    }

    /** 测试注入 mock JdbcTemplate。 */
    PgAsyncDelegationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO t_async_delegation
            (id, parent_session_id, parent_agent_id, task_payload, tool_names,
             state, attempt_count, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String MARK_TERMINAL_SQL = """
        UPDATE t_async_delegation
        SET state = ?, result_summary = ?, updated_at = ?
        WHERE id = ?
        """;

    private static final String MARK_QUEUED_SQL = """
        UPDATE t_async_delegation
        SET state = 'QUEUED', attempt_count = ?, updated_at = ?
        WHERE id = ?
        """;

    private static final String UPDATE_HEARTBEAT_SQL =
        "UPDATE t_async_delegation SET last_heartbeat_at = ?, updated_at = ? WHERE id = ?";

    private static final String MARK_DELIVERED_SQL =
        "UPDATE t_async_delegation SET completion_delivered = TRUE WHERE id = ?";

    private static final String LIST_BY_SESSION_SQL = """
        SELECT id, parent_session_id, parent_agent_id, task_payload, tool_names,
               state, attempt_count, result_summary, created_at, updated_at,
               last_heartbeat_at, completion_delivered
        FROM t_async_delegation WHERE parent_session_id = ? ORDER BY created_at
        """;

    private static final String FIND_PENDING_STALE_SQL = """
        SELECT id, parent_session_id, parent_agent_id, task_payload, tool_names,
               state, attempt_count, result_summary, created_at, updated_at,
               last_heartbeat_at, completion_delivered
        FROM t_async_delegation
        WHERE state IN ('QUEUED','PENDING','RUNNING') AND updated_at < ?
        ORDER BY updated_at LIMIT ?
        """;

    private static final String FIND_UNDELIVERED_SQL = """
        SELECT id, parent_session_id, parent_agent_id, task_payload, tool_names,
               state, attempt_count, result_summary, created_at, updated_at,
               last_heartbeat_at, completion_delivered
        FROM t_async_delegation
        WHERE state NOT IN ('QUEUED','PENDING','RUNNING') AND completion_delivered = FALSE
        ORDER BY updated_at LIMIT ?
        """;

    @Override
    public void save(DelegationRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                record.getId(),
                record.getParentSessionId(),
                record.getParentAgentId(),
                record.getTaskPayload(),
                joinToolNames(record.getToolNames()),
                record.getState() != null ? record.getState().name() : "QUEUED",
                record.getAttemptCount(),
                toTs(record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now()),
                toTs(record.getUpdatedAt() != null ? record.getUpdatedAt() : Instant.now()));
    }

    @Override
    public List<DelegationRecord> listBySession(String sessionId) {
        return jdbcTemplate.query(LIST_BY_SESSION_SQL, DelegationRowMapper.INSTANCE, sessionId);
    }

    @Override
    public List<DelegationRecord> findPendingStale(Instant staleBefore, int limit) {
        return jdbcTemplate.query(FIND_PENDING_STALE_SQL, DelegationRowMapper.INSTANCE,
                Timestamp.from(staleBefore), limit);
    }

    @Override
    public List<DelegationRecord> findUndeliveredTerminal(int limit) {
        return jdbcTemplate.query(FIND_UNDELIVERED_SQL, DelegationRowMapper.INSTANCE, limit);
    }

    @Override
    public void markTerminal(String id, SubagentState terminal, String resultSummary) {
        jdbcTemplate.update(MARK_TERMINAL_SQL,
                terminal != null ? terminal.name() : "FAILED",
                resultSummary, toTs(Instant.now()), id);
    }

    @Override
    public void markQueuedForRetry(String id, int newAttemptCount) {
        jdbcTemplate.update(MARK_QUEUED_SQL, newAttemptCount, toTs(Instant.now()), id);
    }

    @Override
    public void updateHeartbeat(String id, Instant at) {
        jdbcTemplate.update(UPDATE_HEARTBEAT_SQL, toTs(at), toTs(Instant.now()), id);
    }

    @Override
    public void markCompletionDelivered(String id) {
        jdbcTemplate.update(MARK_DELIVERED_SQL, id);
    }

    private static String joinToolNames(List<String> toolNames) {
        return toolNames == null || toolNames.isEmpty() ? null
                : toolNames.stream().collect(Collectors.joining(","));
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** 宽松映射：未知 state 返回 null（防止恢复扫描被异常击垮）。 */
    private static SubagentState safeState(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return SubagentState.valueOf(s);
        } catch (IllegalArgumentException e) {
            log.warn("PgAsyncDelegationStore: 未知 state '{}'，映射为 null", s);
            return null;
        }
    }

    /** 测试暴露 RowMapper。 */
    RowMapper<DelegationRecord> rowMapperForTest() {
        return DelegationRowMapper.INSTANCE;
    }

    private static final class DelegationRowMapper implements RowMapper<DelegationRecord> {
        private static final DelegationRowMapper INSTANCE = new DelegationRowMapper();

        @Override
        public DelegationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            String toolNames = rs.getString("tool_names");
            return DelegationRecord.builder()
                    .id(rs.getString("id"))
                    .parentSessionId(rs.getString("parent_session_id"))
                    .parentAgentId(rs.getString("parent_agent_id"))
                    .taskPayload(rs.getString("task_payload"))
                    .toolNames(toolNames == null || toolNames.isBlank()
                            ? List.of()
                            : Arrays.asList(toolNames.split(",")))
                    .state(safeState(rs.getString("state")))
                    .attemptCount(rs.getInt("attempt_count"))
                    .resultSummary(rs.getString("result_summary"))
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : null)
                    .updatedAt(rs.getTimestamp("updated_at") != null
                            ? rs.getTimestamp("updated_at").toInstant() : null)
                    .lastHeartbeatAt(rs.getTimestamp("last_heartbeat_at") != null
                            ? rs.getTimestamp("last_heartbeat_at").toInstant() : null)
                    .completionDelivered(rs.getBoolean("completion_delivered"))
                    .build();
        }
    }
}

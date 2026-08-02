package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL 实现的会话持久化仓储 — P0-4。
 * 激活条件: PostgreSQL 驱动可用 + aether.session.persistence=true
 */
@Slf4j
@Repository
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnProperty(name = "aether.session.persistence", havingValue = "true", matchIfMissing = false)
public class PgSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PgSessionRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        log.info("PgSessionRepository 已初始化");
    }

    private static final String UPSERT_SQL = """
        INSERT INTO aether_session (session_id, user_id, agent_id, status, state_json, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (session_id) DO UPDATE SET
            status = EXCLUDED.status,
            state_json = EXCLUDED.state_json,
            updated_at = EXCLUDED.updated_at
        """;

    private static final String SELECT_SQL = """
        SELECT id, session_id, user_id, agent_id, status, state_json, created_at, updated_at
        FROM aether_session WHERE session_id = ?
        """;

    private static final String SOFT_DELETE_SQL =
        "UPDATE aether_session SET status = 'ARCHIVED', updated_at = ? WHERE session_id = ?";

    private static final String LIST_BY_USER_SQL = """
        SELECT id, session_id, user_id, agent_id, status, state_json, created_at, updated_at
        FROM aether_session WHERE user_id = ? AND status = 'ACTIVE' ORDER BY updated_at DESC
        """;

    private static final String LIST_BY_USER_AGENT_SQL = """
        SELECT id, session_id, user_id, agent_id, status, state_json, created_at, updated_at
        FROM aether_session WHERE user_id = ? AND agent_id = ? AND status = 'ACTIVE' ORDER BY updated_at DESC
        """;

    private static final String COUNT_ACTIVE_SQL =
        "SELECT COUNT(*) AS cnt FROM aether_session WHERE status = 'ACTIVE'";

    private static final String COUNT_BY_AGENT_SQL =
        "SELECT agent_id, COUNT(*) AS cnt FROM aether_session WHERE status = 'ACTIVE' GROUP BY agent_id";

    @Override
    public CompletableFuture<Void> save(SessionEntity entity) {
        return CompletableFuture.runAsync(() -> {
            Instant now = Instant.now();
            jdbcTemplate.update(UPSERT_SQL,
                entity.getSessionId(),
                entity.getUserId(),
                entity.getAgentId(),
                entity.getStatus() != null ? entity.getStatus() : "ACTIVE",
                entity.getStateJson(),
                entity.getCreatedAt() != null
                    ? Timestamp.from(entity.getCreatedAt()) : Timestamp.from(now),
                Timestamp.from(now)
            );
            log.info("会话已持久化: sessionId={}, status={}, stateJsonLen={}",
                entity.getSessionId(), entity.getStatus(),
                entity.getStateJson() != null ? entity.getStateJson().length() : 0);
        }).exceptionally(ex -> {
            log.error("会话持久化失败: sessionId={}, status={}", entity.getSessionId(), entity.getStatus(), ex);
            return null;
        });
    }

    @Override
    public Optional<SessionEntity> findBySessionId(String sessionId) {
        List<SessionEntity> results = jdbcTemplate.query(SELECT_SQL, new SessionRowMapper(), sessionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public CompletableFuture<Void> deleteBySessionId(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            jdbcTemplate.update(SOFT_DELETE_SQL, Timestamp.from(Instant.now()), sessionId);
            log.debug("会话已归档: sessionId={}", sessionId);
        });
    }

    @Override
    public List<SessionEntity> listByUserId(String userId) {
        return jdbcTemplate.query(LIST_BY_USER_SQL, new SessionRowMapper(), userId);
    }

    @Override
    public List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId) {
        return jdbcTemplate.query(LIST_BY_USER_AGENT_SQL, new SessionRowMapper(), userId, agentId);
    }

    @Override
    public int countActiveSessions() {
        var row = jdbcTemplate.queryForMap(COUNT_ACTIVE_SQL);
        return ((Number) row.get("cnt")).intValue();
    }

    @Override
    public java.util.Map<String, Integer> countSessionsByAgent() {
        var rows = jdbcTemplate.queryForList(COUNT_BY_AGENT_SQL);
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (var row : rows) {
            result.put((String) row.get("agent_id"), ((Number) row.get("cnt")).intValue());
        }
        return result;
    }

    private static class SessionRowMapper implements RowMapper<SessionEntity> {
        @Override
        public SessionEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SessionEntity.builder()
                .sessionId(rs.getString("session_id"))
                .userId(rs.getString("user_id"))
                .agentId(rs.getString("agent_id"))
                .status(rs.getString("status"))
                .stateJson(rs.getString("state_json"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .build();
        }
    }
}

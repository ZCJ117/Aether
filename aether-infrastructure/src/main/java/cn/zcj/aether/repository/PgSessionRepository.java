package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL 实现的会话持久化仓储 — P0-4。
 *
 * <p>P0-3 默认安全激活：零配置（未设 aether.session.*）即启用，使会话跨重启可恢复；
 * 显式 {@code aether.session.store=redis|none} 或 {@code aether.session.persistence=false}
 * 时关闭（与 RedisSessionRepository 互斥）。</p>
 * <p><b>【架构亮点 · 状态机与持久化】</b><br>
 * 面试举证点：会话/委派持久化走"幂等 UPSERT + 共享异步线程池"——UPSERT_SQL（L50-57）
 * 以 ON CONFLICT (session_id) 保证并发写最终一致、不重复插入；写操作统一提交到共享
 * persistExecutor（L36-37）异步线程池；save 失败经 L100-104 显式告警 + Micrometer 指标
 * （不静默吞掉），软删除仅置 status='ARCHIVED'（L64-65）保留审计痕迹。</p>
 * <p><b>【架构权衡点 · 诚实结论】</b><br>
 * 全仓未使用 {@code @Transactional} 与 JPA {@code @Version} 乐观锁列。会话/委派并发正确性
 * 不由数据库事务/行版本承担，而由「内存 CAS（子 Agent 状态机 SubagentRuntime）+ 幂等 UPSERT
 * （会话/委派持久化）」两层机制承担：写冲突靠 ON CONFLICT 幂等合并，读不一致由状态机终态
 * 不可覆盖保证。这是已识别的架构权衡点，需在评审中明确其适用边界（单写入方 + 共享主键幂等）。</p>
 */
@Slf4j
@Repository
@ConditionalOnClass(name = "org.postgresql.Driver")
// 双键条件：store 缺省或 postgres 且 persistence 缺省或 true（@ConditionalOnProperty 非 @Repeatable，合并为 SpEL）
@ConditionalOnExpression(
        "'${aether.session.store:postgres}' == 'postgres' && '${aether.session.persistence:true}' == 'true'")
public class PgSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbcTemplate;
    /** P0-1 统一线程资源管理：注入共享 sessionPersistPool（aether.thread-pools.session-persist，默认 2/4/queue1000）。 */
    // 【持久化】共享异步线程池：所有 save/delete 提交至此 executor，避免持久化阻塞业务主线程
    private final java.util.concurrent.ExecutorService persistExecutor;
    /** P0-3 写失败指标（Micrometer counter aether.session.persist.failures）；无 MeterRegistry 时仅日志。 */
    private final SessionPersistenceMetrics metrics;

    public PgSessionRepository(JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("sessionPersistPool") java.util.concurrent.ExecutorService persistExecutor,
            SessionPersistenceMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.persistExecutor = persistExecutor;
        this.metrics = metrics;
        log.info("PgSessionRepository 已初始化（默认安全激活）");
    }

    // 【持久化】幂等 UPSERT：ON CONFLICT (session_id) 保证并发写最终一致、不重复插入，是并发正确性的一层保障
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

    // 【持久化】软删除：仅置 status='ARCHIVED' 而非物理删除，保留审计痕迹、避免外键/历史关联断裂
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
        }, persistExecutor).exceptionally(ex -> {
            // 【持久化】写失败不静默：显式告警 + Micrometer 指标（aether.session.persist.failures），保证丢失可观测
            // O6 不再静默：显式告警 + 指标（aether.session.persist.failures + 最近错误快照）
            metrics.recordFailure(entity.getSessionId(), "save", ex);
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
        }, persistExecutor).exceptionally(ex -> {
            // O6: 删除（软删）失败同样可见，不再由 Future 静默吞掉
            metrics.recordFailure(sessionId, "delete", ex);
            return null;
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

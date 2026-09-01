package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgSessionRepository 单元测试 — P0-3（Mockito JdbcTemplate 风格，对齐 PgAsyncDelegationStoreTest）。
 *
 * 验证：UPSERT 绑定参数（与 schema.sql aether_session 列对齐）、软删除绑定、findBySessionId 行映射。
 */
class PgSessionRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PgSessionRepository repo =
            new PgSessionRepository(jdbc, executor, new SessionPersistenceMetrics(null));

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static SessionEntity entity() {
        Instant now = Instant.now();
        return SessionEntity.builder()
                .sessionId("s-1").userId("u-1").agentId("a-1")
                .status("ACTIVE").stateJson("{\"currentTurn\":1}")
                .createdAt(now).updatedAt(now).build();
    }

    @Test
    void saveBindsUpsertColumns() {
        repo.save(entity()).join();

        verify(jdbc).update(anyString(),
                eq("s-1"), eq("u-1"), eq("a-1"), eq("ACTIVE"), eq("{\"currentTurn\":1}"),
                any(Timestamp.class), any(Timestamp.class));
    }

    @Test
    void deleteBindsSessionId() {
        repo.deleteBySessionId("s-1").join();

        verify(jdbc).update(anyString(), any(Timestamp.class), eq("s-1"));
    }

    @Test
    void findBySessionIdMapsRow() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("s-1")))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<SessionEntity> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("session_id")).thenReturn("s-1");
                    when(rs.getString("user_id")).thenReturn("u-1");
                    when(rs.getString("agent_id")).thenReturn("a-1");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    when(rs.getString("state_json")).thenReturn("{\"currentTurn\":1}");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.ofEpochMilli(1000)));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.ofEpochMilli(2000)));
                    return List.of(rm.mapRow(rs, 0));
                });

        Optional<SessionEntity> found = repo.findBySessionId("s-1");
        assertTrue(found.isPresent());
        assertEquals("s-1", found.get().getSessionId());
        assertEquals("u-1", found.get().getUserId());
        assertEquals("a-1", found.get().getAgentId());
        assertEquals("{\"currentTurn\":1}", found.get().getStateJson());
        assertEquals(Instant.ofEpochMilli(1000), found.get().getCreatedAt());
    }
}

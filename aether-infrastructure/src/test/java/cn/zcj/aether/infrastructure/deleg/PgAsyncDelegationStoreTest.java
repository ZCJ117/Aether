package cn.zcj.aether.infrastructure.deleg;

import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgAsyncDelegationStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PgAsyncDelegationStore store = new PgAsyncDelegationStore(jdbc);

    private static DelegationRecord queued(String id) {
        Instant now = Instant.now();
        return DelegationRecord.builder()
                .id(id).parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of("code", "search")).state(SubagentState.QUEUED)
                .attemptCount(1).createdAt(now).updatedAt(now).build();
    }

    @Test
    void saveBindsColumns() {
        store.save(queued("ad-1"));
        verify(jdbc).update(anyString(),
                eq("ad-1"), eq("s1"), eq("a1"), eq("task"), eq("code,search"),
                eq("QUEUED"), eq(1), any(Timestamp.class), any(Timestamp.class));
    }

    @Test
    void markTerminalUpdatesStateAndSummary() {
        store.markTerminal("ad-1", SubagentState.COMPLETED, "[结论]");
        verify(jdbc).update(anyString(), eq("COMPLETED"), eq("[结论]"), any(Timestamp.class), eq("ad-1"));
    }

    @Test
    void markQueuedForRetryIncrementsAttempt() {
        store.markQueuedForRetry("ad-1", 2);
        // MARK_QUEUED_SQL 中 'QUEUED' 内联于 SQL，绑定参数仅 attempt_count/updated_at/id 3 个
        verify(jdbc).update(anyString(), eq(2), any(Timestamp.class), eq("ad-1"));
    }

    @Test
    void updateHeartbeatBindsTimestamp() {
        Instant at = Instant.ofEpochMilli(1_700_000_000_000L);
        store.updateHeartbeat("ad-1", at);
        // UPDATE_HEARTBEAT_SQL 绑定 last_heartbeat_at + updated_at 两个时间戳 + id
        verify(jdbc).update(anyString(), any(Timestamp.class), any(Timestamp.class), eq("ad-1"));
    }

    @Test
    void markCompletionDeliveredBindsId() {
        store.markCompletionDelivered("ad-1");
        verify(jdbc).update(anyString(), eq("ad-1"));
    }

    @Test
    void listBySessionQueriesAndMaps() throws Exception {
        DelegationRecord expected = queued("ad-1");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("s1"))).thenReturn(List.of(expected));
        List<DelegationRecord> result = store.listBySession("s1");
        assertEquals(1, result.size());
        assertEquals("ad-1", result.get(0).getId());
    }

    @Test
    void findPendingStaleAndUndeliveredQuery() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class), eq(50)))
                .thenReturn(List.of());
        assertTrue(store.findPendingStale(Instant.now().minusSeconds(60), 50).isEmpty());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(100))).thenReturn(List.of());
        assertTrue(store.findUndeliveredTerminal(100).isEmpty());
    }

    @Test
    void findPendingStaleSqlPinsActiveStatesAndStaleFilter() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class), eq(50)))
                .thenReturn(List.of());
        store.findPendingStale(Instant.now().minusSeconds(60), 50);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Timestamp.class), eq(50));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("'QUEUED','PENDING','RUNNING'"));
        assertTrue(sql.contains("updated_at < ?"));
    }

    @Test
    void findUndeliveredSqlPinsTerminalExclusionAndDeliveryFlag() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(100))).thenReturn(List.of());
        store.findUndeliveredTerminal(100);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), eq(100));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("NOT IN ('QUEUED','PENDING','RUNNING')"));
        assertTrue(sql.contains("completion_delivered = FALSE"));
    }

    @Test
    void rowMapperMapsToolNamesSplit() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("ad-1");
        when(rs.getString("parent_session_id")).thenReturn("s1");
        when(rs.getString("parent_agent_id")).thenReturn("a1");
        when(rs.getString("task_payload")).thenReturn("task");
        when(rs.getString("tool_names")).thenReturn("code,search");
        when(rs.getString("state")).thenReturn("RUNNING");
        when(rs.getInt("attempt_count")).thenReturn(2);
        when(rs.getBoolean("completion_delivered")).thenReturn(false);

        DelegationRecord rec = store.rowMapperForTest().mapRow(rs, 0);
        assertEquals("ad-1", rec.getId());
        assertEquals(2, rec.getToolNames().size());
        assertEquals(SubagentState.RUNNING, rec.getState());
        assertEquals(2, rec.getAttemptCount());
    }
}

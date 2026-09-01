package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(2.2): 会话持久化集成测试（真实 PG，-Pintegration）——roadmap 2.2 第 2 步①：
 * save/restore/list/软删 + state_json 往返。表结构来自 AbstractPgIT 演进链
 * （baseline aether_session + V3/V5 增量），消除"SQL 正确性靠生产验证"盲区。
 */
@Tag("integration")
class PgSessionRepositoryIT extends AbstractPgIT {

    /** 同步执行器：save/delete 的 CompletableFuture 立即完成，测试免轮询。 */
    private static final ExecutorService DIRECT = new AbstractExecutorService() {
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    };

    private PgSessionRepository newRepo() {
        return new PgSessionRepository(jdbc(), DIRECT, new SessionPersistenceMetrics(null));
    }

    private static String uniqueSessionId() {
        return "it-sess-" + System.nanoTime();
    }

    private static SessionEntity entity(String sessionId, String userId, String agentId, String stateJson) {
        Instant now = Instant.now();
        return SessionEntity.builder()
                .sessionId(sessionId)
                .userId(userId)
                .agentId(agentId)
                .status("ACTIVE")
                .stateJson(stateJson)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    void saveAndRestoreRoundTripsStateJson() {
        PgSessionRepository repo = newRepo();
        String sessionId = uniqueSessionId();
        // 含中文/嵌套结构/引号的 state_json —— 验证 JSONB 列往返不损坏
        String stateJson = "{\"turn\":3,\"history\":[{\"role\":\"user\","
                + "\"content\":\"查询 \\\"令牌桶\\\" 配置\"}],\"ctx\":{\"lang\":\"zh-CN\"}}";

        repo.save(entity(sessionId, "user-1", "test-agent", stateJson)).join();

        SessionEntity restored = repo.findBySessionId(sessionId).orElseThrow();
        assertEquals(sessionId, restored.getSessionId());
        assertEquals("user-1", restored.getUserId());
        assertEquals("test-agent", restored.getAgentId());
        assertEquals("ACTIVE", restored.getStatus());
        assertEquals(stateJson, restored.getStateJson(), "state_json 必须逐字节往返");
    }

    @Test
    void saveUpsertOverwritesStateJson() {
        PgSessionRepository repo = newRepo();
        String sessionId = uniqueSessionId();

        repo.save(entity(sessionId, "user-2", "test-agent", "{\"turn\":1}")).join();
        repo.save(entity(sessionId, "user-2", "test-agent", "{\"turn\":2,\"compact\":true}")).join();

        SessionEntity restored = repo.findBySessionId(sessionId).orElseThrow();
        assertEquals("{\"turn\":2,\"compact\":true}", restored.getStateJson(), "同 sessionId 二次保存应覆盖");
    }

    @Test
    void listByUserReturnsActiveOnlyAndSupportsAgentFilter() {
        PgSessionRepository repo = newRepo();
        String userId = "it-user-" + System.nanoTime();
        String agentA = "agent-a-" + System.nanoTime();
        String agentB = "agent-b-" + System.nanoTime();
        String s1 = uniqueSessionId();
        String s2 = uniqueSessionId();
        repo.save(entity(s1, userId, agentA, "{}")).join();
        repo.save(entity(s2, userId, agentB, "{}")).join();

        List<SessionEntity> byUser = repo.listByUserId(userId);
        assertEquals(2, byUser.size(), "两个 ACTIVE 会话都应列出");
        assertTrue(byUser.stream().anyMatch(s -> s.getSessionId().equals(s1)));

        List<SessionEntity> byAgent = repo.listByUserIdAndAgentId(userId, agentA);
        assertEquals(1, byAgent.size());
        assertEquals(s1, byAgent.get(0).getSessionId());
    }

    @Test
    void softDeleteArchivesButKeepsRow() {
        PgSessionRepository repo = newRepo();
        String userId = "it-user-" + System.nanoTime();
        String sessionId = uniqueSessionId();
        repo.save(entity(sessionId, userId, "test-agent", "{\"turn\":9}")).join();
        int activeBefore = repo.countActiveSessions();

        repo.deleteBySessionId(sessionId).join();

        // 软删：行仍在、状态 ARCHIVED，可按 sessionId 找回（审计/恢复用），但不再出现在 ACTIVE 列表
        SessionEntity archived = repo.findBySessionId(sessionId)
                .orElseThrow(() -> new AssertionError("软删后行必须仍在"));
        assertEquals("ARCHIVED", archived.getStatus());
        assertTrue(repo.listByUserId(userId).stream().noneMatch(s -> s.getSessionId().equals(sessionId)),
                "软删会话不得再出现在 ACTIVE 列表");
        assertEquals(activeBefore - 1, repo.countActiveSessions());

        // 软删后同 sessionId 重新 save → upsert 恢复 ACTIVE（会话恢复语义）
        repo.save(entity(sessionId, userId, "test-agent", "{\"turn\":10}")).join();
        assertEquals("ACTIVE", repo.findBySessionId(sessionId).orElseThrow().getStatus());
    }

    @Test
    void countByAgentAggregatesActiveSessions() {
        PgSessionRepository repo = newRepo();
        String userId = "it-user-" + System.nanoTime();
        String agent = "agent-cnt-" + System.nanoTime();
        String s1 = uniqueSessionId();
        String s2 = uniqueSessionId();
        repo.save(entity(s1, userId, agent, "{}")).join();
        repo.save(entity(s2, userId, agent, "{}")).join();

        int count = repo.countSessionsByAgent().getOrDefault(agent, 0);
        assertTrue(count >= 2, "该 agent 至少聚合到本次 2 个 ACTIVE 会话，实际=" + count);

        repo.deleteBySessionId(s1).join();
        assertTrue(repo.countSessionsByAgent().getOrDefault(agent, 0) < count,
                "软删后聚合数应下降");
    }
}

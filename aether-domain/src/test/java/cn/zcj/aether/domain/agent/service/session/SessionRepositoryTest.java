package cn.zcj.aether.domain.agent.service.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionRepository 接口契约测试 — P0-5
 * 使用内存实现验证接口行为
 */
class SessionRepositoryTest {

    private final SessionRepository repo = new InMemorySessionRepository();

    @Test
    void shouldSaveAndFindSession() throws Exception {
        var entity = SessionEntity.builder()
                .sessionId("sess-001")
                .userId("user1")
                .agentId("agent1")
                .status("ACTIVE")
                .stateJson("{\"currentTurn\":3}")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        repo.save(entity).get();

        Optional<SessionEntity> found = repo.findBySessionId("sess-001");
        assertTrue(found.isPresent());
        assertEquals("user1", found.get().getUserId());
        assertEquals("ACTIVE", found.get().getStatus());
    }

    @Test
    void shouldReturnEmptyWhenSessionNotFound() {
        assertTrue(repo.findBySessionId("nonexistent").isEmpty());
    }

    @Test
    void shouldDeleteSession() throws Exception {
        repo.save(SessionEntity.builder()
                .sessionId("to-delete")
                .userId("user1")
                .agentId("agent1")
                .status("ACTIVE")
                .stateJson("{}")
                .build()).get();

        repo.deleteBySessionId("to-delete").get();

        assertTrue(repo.findBySessionId("to-delete").isEmpty());
    }

    @Test
    void shouldListSessionsByUserId() throws Exception {
        repo.save(SessionEntity.builder()
                .sessionId("s1").userId("userA").agentId("a1")
                .status("ACTIVE").stateJson("{}").build()).get();
        repo.save(SessionEntity.builder()
                .sessionId("s2").userId("userA").agentId("a2")
                .status("ACTIVE").stateJson("{}").build()).get();

        var list = repo.listByUserId("userA");
        assertEquals(2, list.size());
    }

    // ----- 内存实现（测试用）-----

    private static class InMemorySessionRepository implements SessionRepository {
        private final java.util.concurrent.ConcurrentHashMap<String, SessionEntity> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public java.util.concurrent.CompletableFuture<Void> save(SessionEntity entity) {
            store.put(entity.getSessionId(), entity);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public Optional<SessionEntity> findBySessionId(String sessionId) {
            return Optional.ofNullable(store.get(sessionId));
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> deleteBySessionId(String sessionId) {
            store.remove(sessionId);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.List<SessionEntity> listByUserId(String userId) {
            return store.values().stream()
                    .filter(e -> userId.equals(e.getUserId()))
                    .toList();
        }

        @Override
        public java.util.List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId) {
            return store.values().stream()
                    .filter(e -> userId.equals(e.getUserId()) && agentId.equals(e.getAgentId()))
                    .toList();
        }

        @Override
        public int countActiveSessions() {
            return (int) store.values().stream()
                    .filter(e -> "ACTIVE".equals(e.getStatus()))
                    .count();
        }

        @Override
        public java.util.Map<String, Integer> countSessionsByAgent() {
            return store.values().stream()
                    .filter(e -> "ACTIVE".equals(e.getStatus()))
                    .collect(java.util.stream.Collectors.groupingBy(
                            SessionEntity::getAgentId,
                            java.util.stream.Collectors.summingInt(x -> 1)));
        }
    }
}

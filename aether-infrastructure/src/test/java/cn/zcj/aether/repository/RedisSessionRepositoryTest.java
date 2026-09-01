package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSessionRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    private RedisSessionRepository repository;

    private static final ExecutorService DIRECT = new AbstractExecutorService() {
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPipelinedWrite() {
        doAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(redisTemplate);
            return List.of();
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @BeforeEach
    void setUp() {
        repository = new RedisSessionRepository(redisTemplate, DIRECT, new SessionPersistenceMetrics(null));
    }

    @Test
    void saveWritesValueAndAllSecondaryIndexes() {
        stubPipelinedWrite();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        repository.save(entity("session-1", "user-1", "agent-1", "{\"turn\":1}")).join();

        verify(valueOperations).set(eq(RedisSessionRepository.SESSION_PREFIX + "session-1"), any(String.class), any());
        verify(hashOperations).put(RedisSessionRepository.USER_INDEX_PREFIX + "user-1", "session-1", "agent-1");
        verify(hashOperations).put(RedisSessionRepository.AGENT_INDEX_PREFIX + "agent-1", "session-1", "user-1");
        verify(setOperations).add(RedisSessionRepository.ACTIVE_INDEX, "session-1");
    }

    @Test
    void saveFailureIsPropagatedAndRecorded() {
        SessionPersistenceMetrics metrics = new SessionPersistenceMetrics(null);
        repository = new RedisSessionRepository(redisTemplate, DIRECT, metrics);
        doAnswer(invocation -> {
            throw new IllegalStateException("redis unavailable");
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));

        assertThatThrownBy(() -> repository.save(
                entity("session-1", "user-1", "agent-1", "{}")).join())
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(metrics.getLastFailure()).isNotNull();
        assertThat(metrics.getLastFailure().sessionId()).isEqualTo("session-1");
        assertThat(metrics.getLastFailure().operation()).isEqualTo("redis-save");
    }

    @Test
    void listByUserIdFiltersCorruptedOrForeignIndexEntries() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doReturn(Map.of("session-1", "agent-1", "stale", "agent-1"))
                .when(hashOperations).entries(RedisSessionRepository.USER_INDEX_PREFIX + "user-1");
        String json = "{\"sessionId\":\"session-1\",\"userId\":\"user-1\",\"agentId\":\"agent-1\","
                + "\"status\":\"ACTIVE\",\"stateJson\":\"{\\\"turn\\\":2}\","
                + "\"createdAt\":\"" + Instant.now() + "\",\"updatedAt\":\"" + Instant.now() + "\"}";
        when(valueOperations.get(RedisSessionRepository.SESSION_PREFIX + "session-1")).thenReturn(json);
        when(valueOperations.get(RedisSessionRepository.SESSION_PREFIX + "stale")).thenReturn(null);

        List<SessionEntity> sessions = repository.listByUserId("user-1");

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSessionId()).isEqualTo("session-1");
        assertThat(sessions.get(0).getStateJson()).isEqualTo("{\"turn\":2}");
    }

    @Test
    void activeCountsIgnoreExpiredEntries() throws Exception {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.members(RedisSessionRepository.ACTIVE_INDEX))
                .thenReturn(java.util.Set.of("session-1", "session-2"));
        when(valueOperations.get(RedisSessionRepository.SESSION_PREFIX + "session-1"))
                .thenReturn("{\"sessionId\":\"session-1\",\"userId\":\"u\",\"agentId\":\"a\","
                        + "\"status\":\"ACTIVE\",\"createdAt\":\"" + Instant.now() + "\","
                        + "\"updatedAt\":\"" + Instant.now() + "\"}");
        when(valueOperations.get(RedisSessionRepository.SESSION_PREFIX + "session-2")).thenReturn(null);

        assertThat(repository.countActiveSessions()).isEqualTo(1);
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
}

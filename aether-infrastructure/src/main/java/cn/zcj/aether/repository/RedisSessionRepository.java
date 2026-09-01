package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Redis implementation of session persistence with secondary indexes.
 *
 * <p>激活条件：{@code aether.session.store=redis}。除了单会话 KV 外，本实现维护
 * user/agent Hash 索引与 active Set，使会话列表和仪表盘统计在 Redis 模式下可用。
 * 过期或归档的索引项在读取时清理，保证列表结果仍然有效。</p>
 */
@Slf4j
@Repository
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(name = "aether.session.store", havingValue = "redis")
public class RedisSessionRepository implements SessionRepository {

    static final String SESSION_PREFIX = "aether:session:data:";
    static final String USER_INDEX_PREFIX = "aether:session:user:";
    static final String AGENT_INDEX_PREFIX = "aether:session:agent:";
    static final String ACTIVE_INDEX = "aether:session:active";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ExecutorService persistExecutor;
    private final SessionPersistenceMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RedisSessionRepository(StringRedisTemplate redisTemplate,
                                  ExecutorService persistExecutor,
                                  SessionPersistenceMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.persistExecutor = persistExecutor;
        this.metrics = metrics;
        log.info("RedisSessionRepository 已就绪（KV + user/agent/active 二级索引）");
    }

    @Override
    public CompletableFuture<Void> save(SessionEntity entity) {
        return CompletableFuture.runAsync(() -> {
            try {
                SessionEntity normalized = withTimestamps(entity);
                writeSession(normalized);
                log.debug("Redis 会话已保存: sessionId={}", entity.getSessionId());
            } catch (Exception e) {
                metrics.recordFailure(entity.getSessionId(), "redis-save", e);
                throw new IllegalStateException("Redis 会话保存失败: " + entity.getSessionId(), e);
            }
        });
    }

    @Override
    public Optional<SessionEntity> findBySessionId(String sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId);
            return value == null ? Optional.empty() : Optional.of(objectMapper.readValue(value, SessionEntity.class));
        } catch (Exception e) {
            log.error("Redis 查询失败: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<Void> deleteBySessionId(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try {
                SessionEntity existing = findBySessionId(sessionId).orElse(null);
                if (existing != null) {
                    writeSession(withStatus(existing, "ARCHIVED"));
                }
                log.debug("Redis 会话已归档: sessionId={}", sessionId);
            } catch (Exception e) {
                metrics.recordFailure(sessionId, "redis-delete", e);
                throw new IllegalStateException("Redis 会话删除失败: " + sessionId, e);
            }
        });
    }

    private void writeSession(SessionEntity entity) {
        String sessionJson;
        try {
            sessionJson = objectMapper.writeValueAsString(entity);
        } catch (DataAccessException | java.io.IOException e) {
            throw new IllegalStateException("Redis 会话序列化失败: " + entity.getSessionId(), e);
        }

        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                String sessionKey = SESSION_PREFIX + entity.getSessionId();
                operations.opsForValue().set(sessionKey, sessionJson, SESSION_TTL);
                operations.opsForHash().put(USER_INDEX_PREFIX + entity.getUserId(),
                        entity.getSessionId(), entity.getAgentId());
                operations.opsForHash().put(AGENT_INDEX_PREFIX + entity.getAgentId(),
                        entity.getSessionId(), entity.getUserId());
                operations.expire(USER_INDEX_PREFIX + entity.getUserId(), SESSION_TTL);
                operations.expire(AGENT_INDEX_PREFIX + entity.getAgentId(), SESSION_TTL);
                if (isActive(entity)) {
                    operations.opsForSet().add(ACTIVE_INDEX, entity.getSessionId());
                } else {
                    operations.opsForSet().remove(ACTIVE_INDEX, entity.getSessionId());
                }
                return null;
            }
        });
    }

    @Override
    public List<SessionEntity> listByUserId(String userId) {
        try {
            Map<Object, Object> agents = redisTemplate.opsForHash().entries(USER_INDEX_PREFIX + userId);
            return agents.keySet().stream()
                    .map(String::valueOf)
                    .map(this::findBySessionId)
                    .flatMap(Optional::stream)
                    .filter(this::isActive)
                    .filter(session -> userId.equals(session.getUserId()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Redis 会话列表失败: userId={}", userId, e);
            return List.of();
        }
    }

    @Override
    public List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId) {
        return listByUserId(userId).stream()
                .filter(session -> agentId.equals(session.getAgentId()))
                .collect(Collectors.toList());
    }

    @Override
    public int countActiveSessions() {
        try {
            Set<String> sessionIds = redisTemplate.opsForSet().members(ACTIVE_INDEX);
            if (sessionIds == null) return 0;
            return (int) sessionIds.stream()
                    .map(this::findBySessionId)
                    .flatMap(Optional::stream)
                    .filter(this::isActive)
                    .count();
        } catch (Exception e) {
            log.error("Redis 活跃会话统计失败", e);
            return 0;
        }
    }

    @Override
    public Map<String, Integer> countSessionsByAgent() {
        try {
            Set<String> sessionIds = redisTemplate.opsForSet().members(ACTIVE_INDEX);
            if (sessionIds == null) return Map.of();
            Map<String, Long> counts = sessionIds.stream()
                    .map(this::findBySessionId)
                    .flatMap(Optional::stream)
                    .filter(this::isActive)
                    .collect(Collectors.groupingBy(SessionEntity::getAgentId, Collectors.counting()));
            return counts.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().intValue()));
        } catch (Exception e) {
            log.error("Redis Agent 会话统计失败", e);
            return Map.of();
        }
    }

    private SessionEntity withTimestamps(SessionEntity entity) {
        Instant now = Instant.now();
        return SessionEntity.builder()
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .status(entity.getStatus() == null ? "ACTIVE" : entity.getStatus())
                .stateJson(entity.getStateJson())
                .createdAt(entity.getCreatedAt() == null ? now : entity.getCreatedAt())
                .updatedAt(now)
                .build();
    }

    private boolean isActive(SessionEntity entity) {
        return entity != null && "ACTIVE".equals(entity.getStatus());
    }

    private SessionEntity withStatus(SessionEntity entity, String status) {
        return SessionEntity.builder()
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .status(status)
                .stateJson(entity.getStateJson())
                .createdAt(entity.getCreatedAt())
                .updatedAt(Instant.now())
                .build();
    }
}

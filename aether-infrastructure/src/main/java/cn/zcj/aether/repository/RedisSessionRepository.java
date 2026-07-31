package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Redis 实现的会话持久化仓储 — P0-4（可选）。
 *
 * 激活条件（全部满足时才加载）：
 *   1. classpath 上有 spring-data-redis
 *   2. application.yml 设置了 aether.session.persistence=true
 *   3. Spring 容器中存在 redisTemplate Bean
 */
@Slf4j
@Repository
@ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
@ConditionalOnProperty(name = "aether.session.persistence", havingValue = "true", matchIfMissing = false)
public class RedisSessionRepository implements SessionRepository {

    private final Object redisTemplate;

    public RedisSessionRepository(org.springframework.context.ApplicationContext ctx) {
        this.redisTemplate = ctx.getBean("redisTemplate");
        log.info("RedisSessionRepository 已就绪");
    }

    @Override
    public CompletableFuture<Void> save(SessionEntity entity) {
        return CompletableFuture.runAsync(() -> {
            try {
                Object rt = redisTemplate;
                Object valueOps = rt.getClass().getMethod("opsForValue").invoke(rt);
                String sessionKey = "aether:session:" + entity.getSessionId();
                valueOps.getClass().getMethod("set", Object.class, Object.class, java.time.Duration.class)
                        .invoke(valueOps, sessionKey, entity, java.time.Duration.ofDays(7));
                log.debug("Redis 会话已保存: sessionId={}", entity.getSessionId());
            } catch (Exception e) {
                log.error("Redis 保存失败: sessionId={}", entity.getSessionId(), e);
            }
        });
    }

    @Override
    public Optional<SessionEntity> findBySessionId(String sessionId) {
        try {
            Object rt = redisTemplate;
            Object valueOps = rt.getClass().getMethod("opsForValue").invoke(rt);
            String key = "aether:session:" + sessionId;
            Object value = valueOps.getClass().getMethod("get", Object.class).invoke(valueOps, key);
            if (value instanceof SessionEntity) {
                return Optional.of((SessionEntity) value);
            }
        } catch (Exception e) {
            log.error("Redis 查询失败: sessionId={}", sessionId, e);
        }
        return Optional.empty();
    }

    @Override
    public CompletableFuture<Void> deleteBySessionId(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Object rt = redisTemplate;
                String key = "aether:session:" + sessionId;
                rt.getClass().getMethod("delete", Object.class).invoke(rt, key);
                log.debug("Redis 会话已删除: sessionId={}", sessionId);
            } catch (Exception e) {
                log.error("Redis 删除失败: sessionId={}", sessionId, e);
            }
        });
    }

    @Override
    public List<SessionEntity> listByUserId(String userId) {
        return List.of(); // 简化实现，按需扩展
    }

    @Override
    public List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId) {
        return List.of(); // Redis 模式暂不支持按 agentId 过滤
    }

    @Override
    public int countActiveSessions() {
        return 0; // Redis 模式暂不支持
    }

    @Override
    public java.util.Map<String, Integer> countSessionsByAgent() {
        return java.util.Map.of(); // Redis 模式暂不支持
    }
}

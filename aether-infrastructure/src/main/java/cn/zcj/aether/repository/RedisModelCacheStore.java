package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.runtime.ModelCacheSnapshot;
import cn.zcj.aether.domain.agent.service.runtime.ModelCacheStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis L2 for the LLM response cache.
 *
 * <p>激活条件：classpath 存在 Redis 且 {@code aether.cache.llm.redis-enabled=true}。
 * Redis 不可用时调用方（ModelCallCache）继续使用 Caffeine L1，不阻断模型链路。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(name = "aether.cache.llm.redis-enabled", havingValue = "true")
public class RedisModelCacheStore implements ModelCacheStore {

    static final String KEY_PREFIX = "aether:llm-cache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisModelCacheStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("LLM Redis L2 缓存已启用");
    }

    @Override
    public Optional<ModelCacheSnapshot> get(String key) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, ModelCacheSnapshot.class));
        } catch (Exception e) {
            log.warn("LLM L2 缓存反序列化失败，删除坏值: key={}", key, e);
            redisTemplate.delete(KEY_PREFIX + key);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, ModelCacheSnapshot snapshot, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, objectMapper.writeValueAsString(snapshot), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("LLM L2 缓存写入失败: " + key, e);
        }
    }

    @Override
    public void clear() {
        java.util.Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}

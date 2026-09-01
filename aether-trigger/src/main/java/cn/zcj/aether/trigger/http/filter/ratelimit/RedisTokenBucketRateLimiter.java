package cn.zcj.aether.trigger.http.filter.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P1(1.2): Redis 令牌桶 —— 集群共享计数的分布式限流实现（{@code mode=redis} 时装配）。
 *
 * <p>Lua 脚本原子执行「计算补充 → 扣减 → 写回 + TTL」，key 按 {@code scope:key}
 * 分桶（如 {@code aether:ratelimit:login:1.2.3.4}）；返回剩余配额供
 * {@code X-RateLimit-Remaining} 响应头。Redis 不可用时抛 {@link RateLimiter.UnavailableException}，
 * 由 Filter 降级内存实现（WARN + 指标），保证限流层自身不成为可用性单点。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.security.rate-limit.mode", havingValue = "redis")
public class RedisTokenBucketRateLimiter implements RateLimiter {

    /** 返回结构：{allowed(0/1), remaining, retryAfterSeconds} */
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();

    static {
        TOKEN_BUCKET_SCRIPT.setLocation(
                new ClassPathResource("ratelimit/ratelimit-token-bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(List.class);
    }

    private final ObjectProvider<StringRedisTemplate> templateProvider;

    @Autowired
    public RedisTokenBucketRateLimiter(ObjectProvider<StringRedisTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryAcquire(String scope, String key, int capacity, int refillPerMinute) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            throw new UnavailableException("StringRedisTemplate 未装配（检查 spring.data.redis.* 配置）", null);
        }
        try {
            long nowMicros = System.currentTimeMillis() * 1000L;
            List<Long> result = template.execute(TOKEN_BUCKET_SCRIPT,
                    List.of("aether:ratelimit:" + scope + ":" + key),
                    String.valueOf(capacity), String.valueOf(refillPerMinute),
                    String.valueOf(nowMicros), "1");
            if (result == null || result.size() < 3) {
                throw new UnavailableException("令牌桶脚本返回异常: " + result, null);
            }
            long allowed = result.get(0);
            long remaining = result.get(1);
            long retryAfter = result.get(2);
            return allowed == 1
                    ? RateLimitDecision.allow(remaining)
                    : RateLimitDecision.reject(remaining, retryAfter);
        } catch (UnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new UnavailableException("Redis 令牌桶调用失败: " + e.getMessage(), e);
        }
    }
}

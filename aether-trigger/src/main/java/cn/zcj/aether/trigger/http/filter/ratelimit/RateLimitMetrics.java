package cn.zcj.aether.trigger.http.filter.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1(1.2): 限流指标 —— 告警规则直接消费（docker/prometheus/rules.yml）。
 *
 * <ul>
 *   <li>{@code aether.ratelimit.triggered.total}{scope}：429 触发数（突增 = 异常流量/疑似攻击）；</li>
 *   <li>{@code aether.ratelimit.fallback.total}：Redis 限流降级内存次数（可用性事件）；</li>
 *   <li>{@code aether.ratelimit.redis.errors.total}：Redis 调用异常数（区分"降级"与"错误"）。</li>
 * </ul>
 * 无 MeterRegistry（单测）时全 no-op。
 */
@Component
public class RateLimitMetrics {

    private final MeterRegistry registry;
    private final Counter fallbackCounter;
    private final Counter redisErrorCounter;
    private final Map<String, Counter> triggeredCounters = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry;
        if (registry == null) {
            this.fallbackCounter = null;
            this.redisErrorCounter = null;
            return;
        }
        this.fallbackCounter = Counter.builder("aether.ratelimit.fallback.total")
                .description("Degrades from redis limiter to in-memory limiter")
                .register(registry);
        this.redisErrorCounter = Counter.builder("aether.ratelimit.redis.errors.total")
                .description("Redis rate limiter invocation errors")
                .register(registry);
    }

    public void recordTriggered(String scope) {
        if (registry == null) {
            return;
        }
        triggeredCounters.computeIfAbsent(scope, s ->
                Counter.builder("aether.ratelimit.triggered.total")
                        .tag("scope", s)
                        .description("Requests rejected by rate limiting (HTTP 429)")
                        .register(registry))
                .increment();
    }

    public void recordFallback() {
        if (fallbackCounter != null) {
            fallbackCounter.increment();
        }
    }

    public void recordRedisError() {
        if (redisErrorCounter != null) {
            redisErrorCounter.increment();
        }
    }
}

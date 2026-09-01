package cn.zcj.aether.trigger.http.filter.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * P1(1.2): 进程内令牌桶（默认实现，{@code mode=memory} 或 Redis 降级时使用）。
 *
 * <p>连续补充（按流逝时间线性补足）而非整窗重置——消除固定窗口的临界突刺问题。
 * 桶级 {@code synchronized} 保证原子性；空闲桶由 scheduledPool 每分钟清理防泄漏。</p>
 */
@Slf4j
@Component
public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clockNanos;

    @Resource(name = "scheduledPool")
    private ScheduledExecutorService scheduler;

    @Autowired
    public InMemoryTokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    /** 测试构造：注入可拨动的纳秒时钟，禁用后台清理。 */
    public InMemoryTokenBucketRateLimiter(LongSupplier clockNanos) {
        this.clockNanos = clockNanos;
    }

    @PostConstruct
    public void scheduleCleanup() {
        if (scheduler == null) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::cleanupStaleBuckets, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public RateLimitDecision tryAcquire(String scope, String key, int capacity, int refillPerMinute) {
        if (capacity <= 0 || refillPerMinute <= 0) {
            // 非法配置：保守放行（与限流禁用等价），避免误伤线上
            return RateLimitDecision.allow(capacity);
        }
        long now = clockNanos.getAsLong();
        double refillPerNano = refillPerMinute / 60.0 / 1_000_000_000.0;
        Bucket bucket = buckets.computeIfAbsent(scope + ":" + key,
                k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double refill = (now - bucket.lastRefillNanos) * refillPerNano;
            if (refill > 0) {
                bucket.tokens = Math.min(capacity, bucket.tokens + refill);
                bucket.lastRefillNanos = now;
            }
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return RateLimitDecision.allow((long) Math.floor(bucket.tokens));
            }
            long retryAfterSec = (long) Math.ceil((1 - bucket.tokens) / refillPerNano / 1_000_000_000.0);
            return RateLimitDecision.reject((long) Math.floor(bucket.tokens), retryAfterSec);
        }
    }

    /** 清理长期未动且已回满的桶（防内存泄漏；共享池由容器关闭，这里仅清理数据）。 */
    void cleanupStaleBuckets() {
        long cutoff = clockNanos.getAsLong() - TimeUnit.MINUTES.toNanos(10);
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                return b.lastRefillNanos < cutoff && b.tokens >= b.capacity - 1e-9;
            }
        });
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
        final int capacity;

        Bucket(int capacity, long nowNanos) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }
    }
}

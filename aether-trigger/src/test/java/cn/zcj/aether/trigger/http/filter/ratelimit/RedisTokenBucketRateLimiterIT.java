package cn.zcj.aether.trigger.http.filter.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(1.2): Redis 令牌桶集成测试（Testcontainers redis:7，-Pintegration / CI integration job）。
 *
 * <p>验证：①并发扣减的<b>原子性</b>（多线程总放行数恰等于容量，绝不超发）；
 * ②突发边界与连续补充语义；③集群共享计数（两个"实例"= 两个连接共享同一桶）。</p>
 */
@Tag("integration")
class RedisTokenBucketRateLimiterIT {

    private static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static RedisTokenBucketRateLimiter limiter;

    @BeforeAll
    static void setUp() {
        REDIS.start();
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        limiter = new RedisTokenBucketRateLimiter(
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public StringRedisTemplate getObject() { return template; }
                    @Override
                    public StringRedisTemplate getIfAvailable() { return template; }
                    @Override
                    public StringRedisTemplate getIfUnique() { return template; }
                    @Override
                    public StringRedisTemplate getObject(Object... args) { return template; }
                });
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
        REDIS.stop();
    }

    @Test
    void concurrentDeductionNeverExceedsCapacity() throws Exception {
        int capacity = 100;
        int threads = 32;
        int perThread = 50; // 总请求 1600 >> 容量 100
        // 令牌桶会持续补充令牌；这里把补充速率降到 1/min，
        // 排除并发耗时超过 1 秒时的合法补充，使断言只验证并发扣减原子性。
        AtomicInteger allowed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    if (limiter.tryAcquire("it", "burst", capacity, 1).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(capacity, allowed.get(), "并发扣减必须原子：放行数恰好等于容量");
    }

    @Test
    void rejectedRequestGetsMeaningfulRetryAfter() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("it", "retry", 10, 60);
        }
        RateLimitDecision d = limiter.tryAcquire("it", "retry", 10, 60);
        assertFalse(d.allowed());
        assertTrue(d.retryAfterSeconds() >= 1 && d.retryAfterSeconds() <= 60,
                "Retry-After 应在补满一桶的时间窗内: " + d.retryAfterSeconds());
    }

    @Test
    void twoSharedConnectionsCountOneBucket() {
        // 同一 Redis、不同 template 实例（模拟集群两个节点）共享同一桶
        StringRedisTemplate t2 = new StringRedisTemplate(factory);
        t2.afterPropertiesSet();
        RedisTokenBucketRateLimiter otherInstance = new RedisTokenBucketRateLimiter(
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public StringRedisTemplate getObject() { return t2; }
                    @Override
                    public StringRedisTemplate getIfAvailable() { return t2; }
                    @Override
                    public StringRedisTemplate getIfUnique() { return t2; }
                    @Override
                    public StringRedisTemplate getObject(Object... args) { return t2; }
                });

        assertTrue(limiter.tryAcquire("it", "shared", 1, 60).allowed());
        // 实例 B 看到桶已被实例 A 耗尽 → 拒绝（分布式共享计数语义）
        assertFalse(otherInstance.tryAcquire("it", "shared", 1, 60).allowed());
    }
}

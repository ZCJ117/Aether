package cn.zcj.aether.trigger.http.filter.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(1.2): 内存令牌桶单元测试 —— 突发扣尽、连续补充、拒绝恢复、scope 隔离、剩余量语义。
 */
class InMemoryTokenBucketRateLimiterTest {

    /** 可拨动的纳秒时钟。 */
    private static AtomicLong nanos = new AtomicLong(0);

    private InMemoryTokenBucketRateLimiter newLimiter() {
        return new InMemoryTokenBucketRateLimiter(() -> nanos.get());
    }

    @Test
    void burstDrainsCapacityThenRejects() {
        InMemoryTokenBucketRateLimiter limiter = newLimiter();
        for (int i = 0; i < 5; i++) {
            RateLimitDecision d = limiter.tryAcquire("default", "1.1.1.1", 5, 60);
            assertTrue(d.allowed(), "满桶应允许前 5 次: i=" + i);
        }
        RateLimitDecision rejected = limiter.tryAcquire("default", "1.1.1.1", 5, 60);
        assertFalse(rejected.allowed(), "令牌耗尽应拒绝");
        assertEquals(0, rejected.remaining());
        assertTrue(rejected.retryAfterSeconds() > 0, "拒绝应给出 Retry-After");
    }

    @Test
    void tokensRefillContinuouslyOverTime() {
        InMemoryTokenBucketRateLimiter limiter = newLimiter();
        // 扣尽 60/分钟 = 1 令牌/秒 的桶
        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("default", "2.2.2.2", 60, 60);
        }
        assertFalse(limiter.tryAcquire("default", "2.2.2.2", 60, 60).allowed());
        // 拨动 10 秒 → 应补充约 10 个令牌
        nanos.addAndGet(10_000_000_000L);
        RateLimitDecision d = limiter.tryAcquire("default", "2.2.2.2", 60, 60);
        assertTrue(d.allowed(), "补充后应放行");
        assertEquals(9, d.remaining(), "10 秒补 10 个，用掉 1 个剩 9");
    }

    @Test
    void rejectionRecoversAfterRefill() {
        InMemoryTokenBucketRateLimiter limiter = newLimiter();
        assertTrue(limiter.tryAcquire("login", "3.3.3.3", 1, 60).allowed());
        assertFalse(limiter.tryAcquire("login", "3.3.3.3", 1, 60).allowed());
        nanos.addAndGet(1_000_000_000L); // 1 秒 → 补 1 个
        assertTrue(limiter.tryAcquire("login", "3.3.3.3", 1, 60).allowed());
    }

    @Test
    void scopesAreIsolated() {
        InMemoryTokenBucketRateLimiter limiter = newLimiter();
        assertTrue(limiter.tryAcquire("login", "4.4.4.4", 1, 60).allowed());
        // login 耗尽不影响 default
        assertTrue(limiter.tryAcquire("default", "4.4.4.4", 1, 60).allowed());
        assertFalse(limiter.tryAcquire("login", "4.4.4.4", 1, 60).allowed());
    }

    @Test
    void capacityNeverExceededByRefill() {
        InMemoryTokenBucketRateLimiter limiter = newLimiter();
        // 长时间闲置后补充不应超过容量（无突刺叠加）
        nanos.addAndGet(3600_000_000_000L); // 1 小时
        for (int i = 0; i < 10; i++) {
            RateLimitDecision d = limiter.tryAcquire("default", "5.5.5.5", 3, 60);
            if (i < 3) {
                assertTrue(d.allowed());
            } else {
                assertFalse(d.allowed(), "补充上限为容量，第 " + i + " 次应拒绝");
            }
        }
    }
}

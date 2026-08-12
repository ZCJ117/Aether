package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffTest {

    @Test
    void adaptiveRateLimitTable() {
        assertEquals(30.0, RetryBackoff.adaptiveRateLimitBackoff(1), 0.001);
        assertEquals(60.0, RetryBackoff.adaptiveRateLimitBackoff(2), 0.001);
        assertEquals(90.0, RetryBackoff.adaptiveRateLimitBackoff(3), 0.001);
        assertEquals(120.0, RetryBackoff.adaptiveRateLimitBackoff(4), 0.001);
        assertEquals(120.0, RetryBackoff.adaptiveRateLimitBackoff(99), 0.001);
    }

    @Test
    void jitteredBackoffWithinBounds() {
        // base=2, jitter∈[0, 0.5*delay] → 结果 ∈ [delay, 1.5*delay]
        // attempt1: delay=2 → [2,3]; attempt2: 4 → [4,6]; attempt3: 8 → [8,12]; attempt4: min(16,30) → [16,24]
        assertBounds(1, 2.0, 3.0);
        assertBounds(2, 4.0, 6.0);
        assertBounds(3, 8.0, 12.0);
        assertBounds(4, 16.0, 24.0);
    }

    private static void assertBounds(int attempt, double lo, double hi) {
        for (int i = 0; i < 200; i++) {
            double d = RetryBackoff.jitteredBackoff(attempt);
            assertTrue(d >= lo - 0.0001, "attempt " + attempt + " below lo: " + d);
            assertTrue(d <= hi + 0.0001, "attempt " + attempt + " above hi: " + d);
        }
    }
}

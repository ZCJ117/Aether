package cn.zcj.aether.domain.agent.service.model.failover;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 退避计算工具 — 对齐 hermes retry_utils.py。
 * jittered_backoff（抖动指数退避）+ adaptive_rate_limit_backoff（自适应限流表）。
 */
public final class RetryBackoff {

    /** 对齐 hermes retry_utils.py: base=2s, max=30s, jitter_ratio=0.5 */
    private static final double BASE_DELAY_SEC = 2.0;
    private static final double MAX_DELAY_SEC = 30.0;
    private static final double JITTER_RATIO = 0.5;

    /** 线程安全计数器（对齐 hermes retry_utils.py _jitter_counter） */
    private static final AtomicInteger jitterCounter = new AtomicInteger(0);

    private RetryBackoff() {
    }

    /**
     * 抖动指数退避：delay = min(base * 2^(attempt-1), max) + [0, 0.5*delay]。
     */
    public static double jitteredBackoff(int attempt) {
        int tick = jitterCounter.incrementAndGet();
        int exponent = Math.max(0, attempt - 1);

        double delay;
        if (exponent >= 63 || BASE_DELAY_SEC <= 0) {
            delay = MAX_DELAY_SEC;
        } else {
            delay = Math.min(BASE_DELAY_SEC * Math.pow(2, exponent), MAX_DELAY_SEC);
        }

        double jitter = ThreadLocalRandom.current().nextDouble(0, JITTER_RATIO * delay);
        return delay + jitter;
    }

    /**
     * 自适应限流退避表：{1:30s, 2:60s, 3:90s, >=4:120s} — 对齐 hermes retry_utils.py L108。
     * 用于 RATE_LIMIT / OVERLOADED，比通用指数退避更陡峭。
     */
    public static double adaptiveRateLimitBackoff(int attempt) {
        return switch (attempt) {
            case 1 -> 30.0;
            case 2 -> 60.0;
            case 3 -> 90.0;
            default -> 120.0;
        };
    }
}

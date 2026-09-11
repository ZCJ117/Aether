package cn.zcj.aether.domain.agent.service.model.failover;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 退避计算工具 — 对齐 hermes retry_utils.py。
 * jittered_backoff（抖动指数退避）+ adaptive_rate_limit_backoff（自适应限流表）。
 *
 * <p><b>【架构亮点 · 模型容错层】</b><br>
 * 面试举证点：退避算法是重试风暴的"节流阀"——{@link #jitteredBackoff} 抖动打散重试洪峰避免惊群，
 * {@link #adaptiveRateLimitBackoff}（:41）提供比通用指数更陡峭的限流自适应退避（30/60/90/120s），
 * 尊重上游限流窗口，是分布式系统重试退避的标准实践。</p>
 */
public final class RetryBackoff {

    /** 对齐 hermes retry_utils.py: base=2s, max=30s, jitter_ratio=0.5 */
    private static final double BASE_DELAY_SEC = 2.0;
    private static final double MAX_DELAY_SEC = 30.0;
    private static final double JITTER_RATIO = 0.5;

    private RetryBackoff() {
    }

    /**
     * 抖动指数退避：delay = min(base * 2^(attempt-1), max) + [0, 0.5*delay]。
     */
    public static double jitteredBackoff(int attempt) {
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
     * 自适应限流退避表：{1:30s, 2:60s, 3:90s, >=4:120s} — 对齐 hermes retry_utils.py
     * _ZAI_CODING_OVERLOAD_LONG_BACKOFF（约 L25）。
     * 用于 RATE_LIMIT / OVERLOADED，比通用指数退避更陡峭。
     */
    // 【容错】限流自适应退避：比通用指数退避更陡峭（30/60/90/120s），尊重上游限流窗口，避免无效高频重试
    public static double adaptiveRateLimitBackoff(int attempt) {
        return switch (attempt) {
            case 1 -> 30.0;
            case 2 -> 60.0;
            case 3 -> 90.0;
            default -> 120.0;
        };
    }
}

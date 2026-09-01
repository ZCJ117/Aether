package cn.zcj.aether.trigger.http.filter.ratelimit;

/**
 * P1(1.2): 限流决策结果。
 *
 * @param allowed           是否放行
 * @param remaining         剩余令牌数（响应用 X-RateLimit-Remaining 头回传）
 * @param retryAfterSeconds 拒绝时建议等待秒数（Retry-After 头）；放行为 0
 */
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds) {

    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, Math.max(0, remaining), 0);
    }

    public static RateLimitDecision reject(long remaining, long retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(0, remaining), Math.max(1, retryAfterSeconds));
    }
}

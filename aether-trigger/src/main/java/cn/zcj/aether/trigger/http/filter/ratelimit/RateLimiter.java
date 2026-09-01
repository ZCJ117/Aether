package cn.zcj.aether.trigger.http.filter.ratelimit;

/**
 * P1(1.2): 限流策略接口 —— 固定窗口升级为<b>令牌桶</b>，实现可切换（memory / redis）。
 *
 * <p>语义：以稳态速率 {@code refillPerMinute} 补充令牌，桶容量 {@code capacity} 允许突发；
 * 原子性由各实现保证（内存为桶级锁，Redis 为 Lua 脚本）。</p>
 */
public interface RateLimiter {

    /**
     * 尝试获取 1 个令牌。
     *
     * @param scope           桶命名空间（如 login / default，与 key 组成完整桶）
     * @param key             桶标识（如客户端 IP）
     * @param capacity        桶容量（允许的最大突发）
     * @param refillPerMinute 每分钟补充令牌数（稳态速率）
     * @return 决策：是否放行 + 剩余令牌 + 建议重试等待秒数（拒绝时 >0）
     */
    RateLimitDecision tryAcquire(String scope, String key, int capacity, int refillPerMinute);

    /** Redis/内存实现不可用时抛出，由调用方（Filter）降级内存实现并打点。 */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

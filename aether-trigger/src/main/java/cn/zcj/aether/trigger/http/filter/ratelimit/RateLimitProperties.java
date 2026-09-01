package cn.zcj.aether.trigger.http.filter.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P1(1.2): 限流配置（键前缀 {@code aether.security.rate-limit}）。
 *
 * <p>算法从固定窗口升级为令牌桶；{@code mode} 决定实现：</p>
 * <ul>
 *   <li>{@code memory}（默认）：进程内令牌桶，单实例语义；</li>
 *   <li>{@code redis}：Redis Lua 令牌桶（集群共享计数）；Redis 不可用时自动降级 memory
 *       （WARN + {@code aether.ratelimit.fallback.total} 指标）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aether.security.rate-limit")
public class RateLimitProperties {

    /** 限流总开关 */
    private boolean enabled = true;

    /** 实现模式：memory（默认，单机）| redis（集群部署） */
    private String mode = "memory";

    /** 默认桶容量（允许的最大突发，请求/分钟口径） */
    private int defaultCapacity = 100;

    /** 默认稳态补充速率（令牌/分钟） */
    private int defaultRefillPerMinute = 100;

    /** 登录接口桶容量（防暴力破解） */
    private int loginCapacity = 5;

    /** 登录接口补充速率（令牌/分钟） */
    private int loginRefillPerMinute = 5;
}

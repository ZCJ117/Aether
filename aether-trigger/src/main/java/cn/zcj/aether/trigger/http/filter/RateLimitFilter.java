package cn.zcj.aether.trigger.http.filter;

import cn.zcj.aether.domain.agent.service.security.JwtService;
import cn.zcj.aether.trigger.http.filter.ratelimit.InMemoryTokenBucketRateLimiter;
import cn.zcj.aether.trigger.http.filter.ratelimit.RateLimitDecision;
import cn.zcj.aether.trigger.http.filter.ratelimit.RateLimitMetrics;
import cn.zcj.aether.trigger.http.filter.ratelimit.RateLimitProperties;
import cn.zcj.aether.trigger.http.filter.ratelimit.RedisTokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1(1.2): HTTP 层限流过滤器 —— 策略化令牌桶。
 *
 * <p>实现路由：{@code aether.security.rate-limit.mode=memory|redis}（默认 memory）。
 * redis 模式提供集群共享计数；Redis 不可用时<b>自动降级</b>内存实现（WARN 60s 节流 +
 * {@code aether.ratelimit.fallback.total} 指标），限流层自身不成为可用性单点。</p>
 *
 * <p>限流键（roadmap 1.2）：已认证请求按 <b>userId</b> 分桶（Bearer Token 校验通过取 subject），
 * 未认证 / Token 无效（含登录端点本身无 Token）退回客户端 IP——登录防爆破仍按 IP 聚合，
 * 认证端点不受同 IP 多用户干扰；endpoint 粒度为类别（login|default）。</p>
 *
 * <p>响应头：{@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining}；超限 429 +
 * {@code Retry-After}（来自令牌桶真实补充时间，非固定 60s）。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String SCOPE_DEFAULT = "default";
    private static final String SCOPE_LOGIN = "login";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final long FALLBACK_WARN_INTERVAL_MS = 60_000;

    private final RateLimitProperties properties;
    private final InMemoryTokenBucketRateLimiter memoryLimiter;
    /** mode=redis 时装配；未装配或调用失败降级 memory。 */
    private final RedisTokenBucketRateLimiter redisLimiter;
    private final RateLimitMetrics metrics;
    /** 解析 Bearer Token 取 userId 作限流键（校验失败回退 IP）。 */
    private final JwtService jwtService;

    private final AtomicLong lastFallbackWarnAt = new AtomicLong();

    public RateLimitFilter(RateLimitProperties properties,
                           InMemoryTokenBucketRateLimiter memoryLimiter,
                           @Autowired(required = false)
                           RedisTokenBucketRateLimiter redisLimiter,
                           RateLimitMetrics metrics,
                           JwtService jwtService) {
        this.properties = properties;
        this.memoryLimiter = memoryLimiter;
        this.redisLimiter = redisLimiter;
        this.metrics = metrics;
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        String path = request.getRequestURI();
        boolean isLoginPath = path.endsWith("/auth/login");
        String scope = isLoginPath ? SCOPE_LOGIN : SCOPE_DEFAULT;
        int capacity = isLoginPath ? properties.getLoginCapacity() : properties.getDefaultCapacity();
        int refillPerMinute = isLoginPath
                ? properties.getLoginRefillPerMinute() : properties.getDefaultRefillPerMinute();

        RateLimitDecision decision = decide(scope, key, capacity, refillPerMinute);

        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            metrics.recordTriggered(scope);
            log.warn("限流触发: key={}, path={}, scope={}, remaining={}",
                    key, path, scope, decision.remaining());
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"429\",\"info\":\"请求过于频繁，请稍后重试\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /** 实现路由：mode=redis 优先，Redis 不可用降级内存（打点 + 节流 WARN）。 */
    private RateLimitDecision decide(String scope, String key, int capacity, int refillPerMinute) {
        if ("redis".equals(properties.getMode())) {
            if (redisLimiter != null) {
                try {
                    return redisLimiter.tryAcquire(scope, key, capacity, refillPerMinute);
                } catch (Exception e) {
                    metrics.recordRedisError();
                    warnFallbackThrottled(e);
                }
            } else {
                warnFallbackThrottled(new IllegalStateException(
                        "rate-limit.mode=redis 但 RedisTokenBucketRateLimiter 未装配"));
            }
            metrics.recordFallback();
        }
        return memoryLimiter.tryAcquire(scope, key, capacity, refillPerMinute);
    }

    /** 降级 WARN 节流：60s 内只打一次，避免故障风暴刷日志。 */
    private void warnFallbackThrottled(Exception cause) {
        long now = Instant.now().toEpochMilli();
        long last = lastFallbackWarnAt.get();
        if (now - last >= FALLBACK_WARN_INTERVAL_MS && lastFallbackWarnAt.compareAndSet(last, now)) {
            log.warn("Redis 限流不可用，降级内存令牌桶（集群语义暂时退化为单机）: {}", cause.getMessage());
        }
    }

    /**
     * 限流键：Bearer Token 校验通过按 userId（{@code u:<subject>}），
     * 未认证/Token 无效/解析异常回退客户端 IP（{@code ip:<addr>}）。
     */
    private String resolveKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            try {
                return "u:" + jwtService.validateToken(auth.substring(BEARER_PREFIX.length())).getSubject();
            } catch (Exception e) {
                log.debug("限流键回退 IP（Token 无效/过期）: {}", e.getMessage());
            }
        }
        return "ip:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

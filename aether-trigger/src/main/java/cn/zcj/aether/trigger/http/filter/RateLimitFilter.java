package cn.zcj.aether.trigger.http.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2: HTTP 层限流过滤器（基于内存滑动窗口）。
 *
 * <p>按客户端 IP 限流：每个 IP 每分钟 100 次。
 * 登录接口额外限制：每个 IP 每分钟 5 次（防暴力破解）。
 * 超限返回 429 + Retry-After 头。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int DEFAULT_MAX_REQUESTS = 100;
    private static final int LOGIN_MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Value("${aether.security.rate-limit.enabled:true}")
    private boolean enabled;

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

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String ip = getClientIp(request);
        String path = request.getRequestURI();
        boolean isLoginPath = path.contains("/auth/login");
        int maxRequests = isLoginPath ? LOGIN_MAX_REQUESTS : DEFAULT_MAX_REQUESTS;

        String key = isLoginPath ? "login:" + ip : ip;
        WindowCounter counter = counters.computeIfAbsent(key,
                k -> new WindowCounter(Instant.now()));

        synchronized (counter) {
            if (Duration.between(counter.windowStart, Instant.now()).compareTo(WINDOW) > 0) {
                counter.windowStart = Instant.now();
                counter.count.set(0);
            }
            if (counter.count.incrementAndGet() > maxRequests) {
                log.warn("限流触发: ip={}, path={}, count={}", ip, path, counter.count.get());
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(WINDOW.getSeconds()));
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":\"429\",\"info\":\"请求过于频繁，请稍后重试\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class WindowCounter {
        Instant windowStart;
        AtomicInteger count;
        WindowCounter(Instant start) {
            this.windowStart = start;
            this.count = new AtomicInteger(0);
        }
    }
}

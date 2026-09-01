package cn.zcj.aether.trigger.http.filter.ratelimit;

import cn.zcj.aether.domain.agent.service.security.JwtService;
import cn.zcj.aether.trigger.http.filter.RateLimitFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(1.2): 限流过滤器策略路由测试 —— 429 响应契约、响应头、降级路径、userId/IP 键控。
 */
class RateLimitFilterTest {

    private static RateLimitProperties props(String mode) {
        RateLimitProperties p = new RateLimitProperties();
        p.setMode(mode);
        p.setDefaultCapacity(3);
        p.setDefaultRefillPerMinute(60);
        p.setLoginCapacity(2);
        p.setLoginRefillPerMinute(60);
        return p;
    }

    private static InMemoryTokenBucketRateLimiter memoryLimiter() {
        return new InMemoryTokenBucketRateLimiter(System::nanoTime);
    }

    /** 未认证场景下的 JWT 服务（无 Authorization 头时不会被调用）。 */
    private static JwtService jwt() {
        return Mockito.mock(JwtService.class);
    }

    @Test
    void overLimitReturns429WithHeaders() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                props("memory"), memoryLimiter(), null, new RateLimitMetrics(null), jwt());

        MockHttpServletResponse last = null;
        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            req.setRemoteAddr("9.9.9.9");
            last = new MockHttpServletResponse();
            filter.doFilter(req, last, new MockFilterChain());
        }
        assertNotNull(last);
        assertEquals(429, last.getStatus());
        assertEquals("3", last.getHeader("X-RateLimit-Limit"));
        assertEquals("0", last.getHeader("X-RateLimit-Remaining"));
        assertEquals("1", last.getHeader("Retry-After"));
        assertTrue(last.getContentAsString().contains("429"));
    }

    @Test
    void underLimitPassesThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                props("memory"), memoryLimiter(), null, new RateLimitMetrics(null), jwt());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
        req.setRemoteAddr("8.8.8.8");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                chainCalls.incrementAndGet();
            }
        };
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertEquals(1, chainCalls.get());
        assertEquals("3", res.getHeader("X-RateLimit-Limit"));
        assertEquals("2", res.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void disabledFilterBypassesEverything() throws Exception {
        RateLimitProperties p = props("memory");
        p.setEnabled(false);
        RateLimitFilter filter = new RateLimitFilter(p, memoryLimiter(), null, new RateLimitMetrics(null), jwt());

        AtomicInteger chainCalls = new AtomicInteger();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                chainCalls.incrementAndGet();
            }
        };
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            req.setRemoteAddr("7.7.7.7");
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }
        assertEquals(10, chainCalls.get());
    }

    @Test
    void actuatorAndSwaggerAreSkipped() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                props("memory"), memoryLimiter(), null, new RateLimitMetrics(null), jwt());
        // 豁免路径反复请求超过容量也不得 429（经 doFilter 间接验证 shouldNotFilter）
        for (String path : new String[]{"/actuator/prometheus", "/v3/api-docs/x", "/swagger-ui/index.html"}) {
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req, res, new MockFilterChain());
                assertEquals(200, res.getStatus(), path + " 应被豁免");
            }
        }
    }

    @Test
    void redisFailureDegradesToMemory() throws Exception {
        // mode=redis 但 redisLimiter 抛 Unavailable → 降级内存继续限流（不抛 500）
        RateLimitFilter filter = new RateLimitFilter(
                props("redis"), memoryLimiter(),
                new RedisTokenBucketRateLimiter(null), new RateLimitMetrics(null), jwt());

        MockHttpServletResponse last = null;
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            req.setRemoteAddr("6.6.6.6");
            last = new MockHttpServletResponse();
            filter.doFilter(req, last, new MockFilterChain());
        }
        // Redis 不可用降级：内存桶容量 3 → 第 4 次起 429
        assertNotNull(last);
        assertEquals(429, last.getStatus());
    }

    @Test
    void redisModeWithoutBeanStillLimitsViaMemory() throws Exception {
        // mode=redis 但未装配 Redis 实现（异常配置）→ 仍走内存实现，不会失去保护
        RateLimitFilter filter = new RateLimitFilter(
                props("redis"), memoryLimiter(), null, new RateLimitMetrics(null), jwt());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
        req.setRemoteAddr("5.5.5.5");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(200, res.getStatus());
        assertEquals("2", res.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void authenticatedRequestsKeyByUserIdAcrossIps() throws Exception {
        // roadmap 1.2：已认证请求按 userId 分桶——同一 userId 换 IP 共享同一只桶（防绕过），
        // 且不受同 IP 其他用户干扰
        JwtService jwt = jwt();
        Claims claims = Mockito.mock(Claims.class);
        Mockito.when(claims.getSubject()).thenReturn("42");
        Mockito.when(jwt.validateToken("tok-42")).thenReturn(claims);
        RateLimitFilter filter = new RateLimitFilter(
                props("memory"), memoryLimiter(), null, new RateLimitMetrics(null), jwt);

        MockHttpServletResponse last = null;
        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            req.setRemoteAddr(i % 2 == 0 ? "10.0.0.1" : "10.0.0.2");
            req.addHeader("Authorization", "Bearer tok-42");
            last = new MockHttpServletResponse();
            filter.doFilter(req, last, new MockFilterChain());
        }
        assertNotNull(last);
        assertEquals(429, last.getStatus(), "同 userId 跨 IP 应共享限流桶");
    }

    @Test
    void invalidTokenFallsBackToIpKeying() throws Exception {
        JwtService jwt = jwt();
        Mockito.when(jwt.validateToken("bad")).thenThrow(new JwtException("token expired"));
        RateLimitFilter filter = new RateLimitFilter(
                props("memory"), memoryLimiter(), null, new RateLimitMetrics(null), jwt);

        // 无效 Token → IP 兜底：同 IP 容量 3，第 4 次起 429
        MockHttpServletResponse last = null;
        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            req.setRemoteAddr("10.1.1.1");
            req.addHeader("Authorization", "Bearer bad");
            last = new MockHttpServletResponse();
            filter.doFilter(req, last, new MockFilterChain());
        }
        assertEquals(429, last.getStatus());

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/agents");
        other.setRemoteAddr("10.1.1.2");
        other.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(other, res, new MockFilterChain());
        assertEquals(200, res.getStatus(), "不同 IP 应有独立桶");
    }
}

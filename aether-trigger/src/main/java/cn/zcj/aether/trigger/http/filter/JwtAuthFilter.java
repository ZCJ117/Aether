package cn.zcj.aether.trigger.http.filter;

import cn.zcj.aether.domain.agent.service.security.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * P0: JWT 认证过滤器（替换原有的 ApiTokenAuthFilter）。
 *
 * <p><b>【架构亮点 · 权限体系 fail-closed】</b><br>
 * 面试举证点：token 过期/校验异常仅告警（:91-100），不向 SecurityContext 注入任何身份；未认证请求继续走过滤器链后由 anyRequest().authenticated() 统一拒绝，确保异常路径绝不静默赋予权限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String[] SKIP_PREFIXES = {
        "/swagger-ui", "/v3/api-docs", "/actuator/health"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/auth")) {
            return true;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = extractToken(request);
        if (token == null) {
            // 无 Token 时记录请求路径，帮助排查前端是否未传 Token
            log.debug("请求无 JWT Token: method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        try {
            var claims = jwtService.validateToken(token);
            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            if (role == null || role.isBlank()) {
                log.warn("JWT 中 role 为空: userId={}, username={}", userId, username);
                role = "VIEWER";
            }

            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_" + role));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId, null, authorities);
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("JWT 认证成功: userId={}, username={}, role={}",
                    userId, username, role);

        // 【fail-closed】过期/校验异常仅告警，不向 SecurityContext 注入身份，交由后续 anyRequest().authenticated() 拒绝
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已过期: {}, uri={}", e.getMessage(), request.getRequestURI());
        } catch (JwtException e) {
            log.warn("JWT 验证失败 — {}: uri={}, token前16字符={}",
                    e.getMessage(), request.getRequestURI(),
                    token.length() > 16 ? token.substring(0, 16) + "..." : token);
        } catch (Exception e) {
            log.error("JWT 处理异常: {} — {}", e.getClass().getSimpleName(),
                    e.getMessage(), e);
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        // 优先从 Authorization: Bearer 头提取（新 JWT 方式）
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        // 兼容旧前端：X-API-Token 头也可能携带 JWT
        String legacyToken = request.getHeader("X-API-Token");
        if (StringUtils.hasText(legacyToken)) {
            // 如果旧 token 看起来像 JWT（含两个点），按 JWT 处理
            if (legacyToken.chars().filter(c -> c == '.').count() == 2) {
                return legacyToken.trim();
            }
            // 否则是旧的共享 Token，不支持（应走登录流程获取 JWT）
            log.debug("X-API-Token 不是 JWT 格式，忽略: uri={}", request.getRequestURI());
        }
        return null;
    }
}

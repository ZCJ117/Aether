package cn.zcj.aether.trigger.http.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * H4: API Token 鉴权过滤器。
 *
 * <p>校验请求头中的 {@code X-API-Token}，防止未授权访问 Agent API。
 * 无 token 或 token 不匹配时返回 401。
 *
 * <p>通过 {@code aether.api.token} 属性配置（默认空字符串 = 跳过校验，向后兼容）。
 * 生产环境必须配置为强随机字符串。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTokenAuthFilter implements Filter {

    @Value("${aether.api.token:}")
    private String apiToken;

    /** Swagger/OpenAPI 路径，跳过鉴权 */
    private static final String[] SKIP_PATHS = {
            "/swagger-ui", "/v3/api-docs", "/actuator"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // 未配置 token → 跳过校验（向后兼容）
        if (apiToken == null || apiToken.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Swagger/健康检查路径跳过鉴权
        String path = httpRequest.getRequestURI();
        for (String skip : SKIP_PATHS) {
            if (path.startsWith(skip)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // 校验 X-API-Token 请求头
        String token = httpRequest.getHeader("X-API-Token");
        if (apiToken.equals(token)) {
            chain.doFilter(request, response);
            return;
        }

        // 鉴权失败
        log.warn("API Token 鉴权失败: path={}, remoteAddr={}",
                path, httpRequest.getRemoteAddr());

        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write(
                "{\"code\":\"401\",\"info\":\"Unauthorized: invalid or missing API token\"}");
    }
}

package cn.zcj.aether.trigger.http.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 认证入口点 —— fail-closed：认证失败统一返回 401，绝不为未认证请求注入身份。
 *
 * <p><b>【架构亮点 · 权限体系 fail-closed】</b><br>
 * 面试举证点：commence 方法在 AuthenticationException 时强制 setStatus(SC_UNAUTHORIZED)（:27），返回 401 JSON 而非放行，确保未认证请求一律被拒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        log.warn("认证失败: uri={}, error={}",
                request.getRequestURI(), authException.getMessage());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 【fail-closed】认证失败一律 401，绝不放行
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "code", "401",
                "info", "Unauthorized"
        )));
    }
}

package cn.zcj.aether.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * O16: CORS 配置 —— 默认空白名单（无匹配即拒绝跨域）。
 *
 * <ul>
 *   <li>未配置 {@code aether.cors.allowed-origins} → 拒绝所有跨域</li>
 *   <li>{@code *} → 允许任意来源但关闭 credentials（* + credentials 非法/危险）</li>
 *   <li>具体白名单 → 允许并启用 credentials</li>
 * </ul>
 */
@Slf4j
@Configuration
public class CorsConfig {

    private final String allowedOrigins;

    public CorsConfig(@Value("${aether.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 构建 CORS 配置（独立方法便于单元测试）。
     */
    public CorsConfiguration buildConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        String origins = allowedOrigins == null ? "" : allowedOrigins.trim();

        if (origins.isEmpty()) {
            log.info("CORS: 未配置 aether.cors.allowed-origins，拒绝所有跨域请求");
            config.setAllowCredentials(false);
        } else if ("*".equals(origins)) {
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(List.of(origins.split(",")));
            config.setAllowCredentials(true);
        }

        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setMaxAge(3600L);
        return config;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfiguration());
        return new CorsFilter(source);
    }
}

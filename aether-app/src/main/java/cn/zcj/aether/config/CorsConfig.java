package cn.zcj.aether.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * H4: CORS 配置 —— 按 profile 配置白名单，替代硬编码的 {@code @CrossOrigin(origins = "*")}。
 *
 * <p>默认值 * 保证向后兼容；生产环境应配置为具体域名白名单。
 * 通过 {@code aether.cors.allowed-origins} 属性控制。
 */
@Configuration
public class CorsConfig {

    @Value("${aether.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        if ("*".equals(allowedOrigins.trim())) {
            config.addAllowedOriginPattern("*");
        } else {
            config.setAllowedOrigins(
                    List.of(allowedOrigins.split(",")));
        }

        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

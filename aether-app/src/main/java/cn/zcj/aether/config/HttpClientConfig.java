package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.security.SsrfSafeInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端配置。
 *
 * <p><b>重要：</b>所有出站 HTTP 调用必须使用此 {@code @Bean} 注入的
 * {@link RestTemplate}，以确保 SSRF 防护拦截器生效。
 * 直接 {@code new RestTemplate()} 会绕过 SSRF 保护。
 *
 * <p>配置 JDK HttpURLConnection 全局超时，应用于 Spring AI OpenAiApi 的底层请求。
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    @PostConstruct
    void configureTimeouts() {
        // JDK HttpURLConnection 全局超时（毫秒）
        System.setProperty("sun.net.client.defaultConnectTimeout", "30000");  // 30s
        System.setProperty("sun.net.client.defaultReadTimeout", "300000");    // 5min
        log.info("HTTP 全局超时已配置: connectTimeout=30s readTimeout=300s");
    }

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new SsrfSafeInterceptor(false));
        log.info("SSRF 防护已启用");
        return restTemplate;
    }
}

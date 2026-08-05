package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.security.SsrfSafeInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端配置。
 *
 * <p><b>重要：</b>所有出站 HTTP 调用必须使用此 {@code @Bean} 注入的
 * {@link RestTemplate}，以确保 SSRF 防护拦截器生效。
 * 直接 {@code new RestTemplate()} 会绕过 SSRF 保护。
 *
 * <p>配置 JDK HttpURLConnection 全局超时，应用于 Spring AI OpenAiApi 的底层请求。
 * <p>开发环境设置 {@code aether.ssrf.allow-private-urls=true} 允许访问本地 Python 服务。
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    @Value("${aether.ssrf.allow-private-urls:false}")
    private boolean ssrfAllowPrivateUrls;

    @PostConstruct
    void configureTimeouts() {
        // JDK HttpURLConnection 全局超时（毫秒）
        System.setProperty("sun.net.client.defaultConnectTimeout", "30000");   // 30s
        System.setProperty("sun.net.client.defaultReadTimeout", "120000");     // 2min (对齐 ReActAgent block 超时)
        log.info("HTTP 全局超时已配置: connectTimeout=30s readTimeout=120s");
    }

    @Bean
    public RestTemplate restTemplate() {
        // 该 RestTemplate 当前仅被 PythonServiceClient 使用（fs/sandbox/doc 微服务）。
        // 显式设置短超时：fs 服务挂起时工具调用快速失败，避免叠加为"思考中"卡死。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);   // 10s
        factory.setReadTimeout(90_000);      // 90s（覆盖 execute_code 默认 60s 上限 + 余量）
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add(new SsrfSafeInterceptor(ssrfAllowPrivateUrls));
        log.info("SSRF 防护已启用 (allowPrivateUrls={})", ssrfAllowPrivateUrls);
        return restTemplate;
    }
}

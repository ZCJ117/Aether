package cn.zcj.aether.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP 客户端全局超时配置
 *
 * 配置 JDK HttpURLConnection 全局超时，应用于 Spring AI OpenAiApi 的底层请求
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
}

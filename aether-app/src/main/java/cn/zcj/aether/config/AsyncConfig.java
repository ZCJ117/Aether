package cn.zcj.aether.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * P1: 启用异步执行（审计日志异步写入，不阻塞业务线程）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

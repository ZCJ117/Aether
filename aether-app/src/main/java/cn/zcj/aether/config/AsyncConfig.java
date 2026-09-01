package cn.zcj.aether.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * P1: 启用异步执行（审计日志异步写入，不阻塞业务线程）。
 *
 * <p>O17: auditExecutor 池迁入 {@link AetherExecutorRegistry}（统一池来源），
 * 经 @Async("auditExecutor") 引用；本配置仅保留 @EnableAsync 开关。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

package cn.zcj.aether.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * P1: 启用异步执行（审计日志异步写入，不阻塞业务线程）。
 *
 * <p>O17: auditExecutor 池迁入 {@link AetherExecutorRegistry}（统一池来源），
 * 由 AuditAspect 经 {@code @Qualifier("auditExecutor")} 显式提交
 * （O17/O18 起弃用 {@code @Async} 隐式调度，避免在复用线程中读取调用方身份）；
 * 本配置仅保留 @EnableAsync 开关。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

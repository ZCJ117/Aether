package cn.zcj.aether.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PgSessionRepository 条件注解测试 — P0-3 根因 1 修复
 *
 * 验证：零配置（无 aether.session.*）即有 Pg 仓储 Bean（matchIfMissing=true 生效）；
 * 显式 persistence=false / store=redis / store=none 时关闭（与 RedisSessionRepository 互斥）。
 */
class PgSessionRepositoryConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PgTestConfig.class);

    @Configuration
    @Import(PgSessionRepository.class)
    static class PgTestConfig {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean(name = "sessionPersistPool", destroyMethod = "shutdown")
        ExecutorService sessionPersistPool() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        SessionPersistenceMetrics sessionPersistenceMetrics() {
            return new SessionPersistenceMetrics(null);
        }
    }

    @Test
    void pgRepositoryActiveByDefaultWithoutAnySessionConfig() {
        runner.run(ctx ->
                assertThat(ctx).hasSingleBean(PgSessionRepository.class));
    }

    @Test
    void pgRepositoryDisabledWhenPersistenceFalse() {
        runner.withPropertyValues("aether.session.persistence=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(PgSessionRepository.class));
    }

    @Test
    void pgRepositoryDisabledWhenStoreRedis() {
        runner.withPropertyValues("aether.session.store=redis").run(ctx ->
                assertThat(ctx).doesNotHaveBean(PgSessionRepository.class));
    }

    @Test
    void pgRepositoryDisabledWhenStoreNone() {
        runner.withPropertyValues("aether.session.store=none").run(ctx ->
                assertThat(ctx).doesNotHaveBean(PgSessionRepository.class));
    }
}

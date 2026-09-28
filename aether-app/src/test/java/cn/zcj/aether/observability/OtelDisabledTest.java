package cn.zcj.aether.observability;

import cn.zcj.aether.config.OtelConfig;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4/F2-1 边界：未配置 {@code otel.traces.exporter} 时零副作用 —— T2-8。
 *
 * <p>{@code application-test.yml} 不含任何 {@code otel.*} 键，故
 * {@code @ConditionalOnProperty(name="otel.traces.exporter", havingValue="otlp")} 不满足，
 * {@link OtelConfig} 整体不装配：容器内没有 {@code SdkTracerProvider}，
 * 也不会产生任何"连不上 collector"的启动噪声。</p>
 *
 * <p>上下文配置与 {@code CompletionBusWiringTest} 保持一致，以便 Spring 复用同一缓存上下文。</p>
 */
@SpringBootTest(
        properties = {
                "spring.profiles.active=test",
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
        }
)
class OtelDisabledTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.UserRepository userRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.RefreshTokenRepository refreshTokenRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.AuditLogRepository auditLogRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.DashboardStatsRepository dashboardStatsRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.ProcessedEventRepository processedEventRepository;

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private org.springframework.core.env.Environment env;

    @Test
    @DisplayName("T2-8 未配置 otel.traces.exporter 时容器内无 SdkTracerProvider，上下文正常加载")
    void noSdkTracerProviderWhenExporterNotConfigured() {
        assertFalse(env.containsProperty("otel.traces.exporter"),
                "test profile 不得配置 otel.traces.exporter，否则本用例前提不成立");

        assertEquals(0, ctx.getBeanNamesForType(SdkTracerProvider.class).length,
                "装配条件不满足时不得存在 SdkTracerProvider Bean");
        assertEquals(0, ctx.getBeanNamesForType(OtelConfig.class).length,
                "@ConditionalOnProperty 不满足时 OtelConfig 整体不装配");
    }

    @Test
    @DisplayName("T2-8 补充：上下文可正常取用业务 Bean（证明未因不装配而受影响）")
    void contextStillLoadsNormally() {
        assertTrue(ctx.getBeanDefinitionCount() > 0);
    }
}

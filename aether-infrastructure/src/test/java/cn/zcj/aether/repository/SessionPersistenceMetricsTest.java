package cn.zcj.aether.repository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SessionPersistenceMetrics 单元测试 — P0-3 写失败指标
 *
 * 验证：有 MeterRegistry 时递增 aether.session.persist.failures counter；无注册表时安全 no-op。
 */
class SessionPersistenceMetricsTest {

    @Test
    void incrementsCounterWhenRegistryPresent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SessionPersistenceMetrics metrics = new SessionPersistenceMetrics(registry);

        metrics.incrementFailures();
        metrics.incrementFailures();

        double count = registry.counter(SessionPersistenceMetrics.METRIC_NAME).count();
        assertEquals(2.0, count, "counter 应按调用次数递增");
    }

    @Test
    void noOpWithoutRegistry() {
        SessionPersistenceMetrics metrics = new SessionPersistenceMetrics(null);
        assertDoesNotThrow(metrics::incrementFailures, "无 MeterRegistry 时应安全跳过");
    }
}

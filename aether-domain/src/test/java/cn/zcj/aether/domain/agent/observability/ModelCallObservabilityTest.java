package cn.zcj.aether.domain.agent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1(1.1): 模型调用等待可观测测试 —— gauge 随 beginWait/close 增减、超时计数器递增。
 */
class ModelCallObservabilityTest {

    @Test
    void beginWaitIncrementsGaugeAndCloseDecrements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelCallObservability obs = new ModelCallObservability(registry);

        assertEquals(0.0, registry.get("aether.model.waiting.threads").gauge().value());

        ModelCallObservability.WaitHandle h1 = obs.beginWait();
        assertEquals(1.0, registry.get("aether.model.waiting.threads").gauge().value());

        ModelCallObservability.WaitHandle h2 = obs.beginWait();
        assertEquals(2.0, registry.get("aether.model.waiting.threads").gauge().value());

        h1.close();
        assertEquals(1.0, registry.get("aether.model.waiting.threads").gauge().value());

        h2.close();
        assertEquals(0.0, registry.get("aether.model.waiting.threads").gauge().value());
    }

    @Test
    void closeIsIdempotentAndCountsTimeouts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelCallObservability obs = new ModelCallObservability(registry);

        ModelCallObservability.WaitHandle h = obs.beginWait();
        h.close();
        h.close();
        assertEquals(0.0, registry.get("aether.model.waiting.threads").gauge().value());

        obs.recordTimeout();
        obs.recordTimeout();
        assertEquals(2.0, registry.get("aether.model.call.timeouts.total").counter().count());
    }

    @Test
    void tryWithResourcesReleasesOnException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelCallObservability obs = new ModelCallObservability(registry);

        try {
            try (ModelCallObservability.WaitHandle h = obs.beginWait()) {
                assertEquals(1.0, registry.get("aether.model.waiting.threads").gauge().value());
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException ignored) {
            // 预期异常路径
        }
        assertEquals(0.0, registry.get("aether.model.waiting.threads").gauge().value());
    }
}

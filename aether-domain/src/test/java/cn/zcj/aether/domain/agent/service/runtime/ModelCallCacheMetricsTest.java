package cn.zcj.aether.domain.agent.service.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0(1.5-步骤4): ModelCallCache 命中率接入 Micrometer（aether.cache.llm.hitrate Gauge）。
 */
class ModelCallCacheMetricsTest {

    private static ModelInvoker.ModelCallResult okResult() {
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of()).fullText("ok").build();
    }

    @Test
    void hitrateGaugeReflectsCaffeineStats() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelCallCache cache = new ModelCallCache(registry);

        assertNull(cache.get("miss-key"));                        // miss
        cache.put("hit-key", okResult(), 60);
        assertNotNull(cache.get("hit-key"));                      // hit → hitRate = 1/2

        Gauge gauge = registry.get("aether.cache.llm.hitrate").gauge();
        assertEquals(0.5, gauge.value(), 1e-9);
    }

    @Test
    void nullRegistryDegradesGracefully() {
        ModelCallCache cache = new ModelCallCache();
        cache.put("k", okResult(), 60);
        assertNotNull(cache.get("k"));
        assertEquals(1.0, cache.hitRate(), 1e-9);
    }
}

package cn.zcj.aether.domain.agent.service.context.compaction;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P0(1.5-步骤2): 压缩 token 收益指标（aether.compaction.*）。
 */
class CompactionMetricsTest {

    @Test
    void recordsCumulativeTokensCountAndLastSavingRatio() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CompactionMetrics metrics = new CompactionMetrics(registry);

        metrics.recordCompaction(1000, 400);   // 节省率 0.6
        metrics.recordCompaction(500, 500);    // 节省率 0.0（无效压缩）

        assertEquals(2.0, registry.get("aether.compaction.count").counter().count());
        assertEquals(1500.0, registry.get("aether.compaction.tokens.pre").counter().count());
        assertEquals(900.0, registry.get("aether.compaction.tokens.post").counter().count());
        assertEquals(0.0, registry.get("aether.compaction.last.saving.ratio").gauge().value(), 1e-9);
    }

    @Test
    void nullRegistryIsNoOp() {
        CompactionMetrics metrics = new CompactionMetrics(null);
        assertDoesNotThrow(() -> metrics.recordCompaction(100, 50));
    }
}

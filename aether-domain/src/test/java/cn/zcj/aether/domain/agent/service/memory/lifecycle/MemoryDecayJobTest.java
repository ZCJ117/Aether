package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.core.MemoryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.3): 衰减任务测试 —— 分批归档、上限保护、瘦身率指标更新、异常不中断。
 */
class MemoryDecayJobTest {

    /** 内存版 decay store：预置 (id, retention) 候选，模拟 SQL 保留分筛选。 */
    private static class FakeDecayStore implements MemoryDecayStore {
        private final java.util.Map<String, Double> active = new java.util.LinkedHashMap<>();
        private int archivedTotal;

        FakeDecayStore(double... retentions) {
            int i = 0;
            for (double r : retentions) {
                active.put("mem-" + i++, r);
            }
        }

        @Override
        public int archiveStale(double halfLifeDays, double minRetentionScore, int batchSize) {
            int archived = 0;
            var it = active.entrySet().iterator();
            while (it.hasNext() && archived < batchSize) {
                if (it.next().getValue() < minRetentionScore) {
                    it.remove();
                    archived++;
                }
            }
            archivedTotal += archived;
            return archived;
        }

        @Override
        public long countActive() {
            return active.size();
        }

        @Override
        public long countArchived() {
            return archivedTotal;
        }
    }

    private static MemoryProperties.Decay config(int maxPerRun, int batchSize) {
        MemoryProperties.Decay d = new MemoryProperties.Decay();
        d.setMaxArchivePerRun(maxPerRun);
        d.setBatchSize(batchSize);
        d.setHalfLifeDays(30.0);
        d.setMinRetentionScore(0.2);
        return d;
    }

    @Test
    void archivesOnlyBelowThresholdInBatches() {
        // 3 条低于阈值 0.2，2 条高于 → 全部批处理后恰归档 3 条
        FakeDecayStore store = new FakeDecayStore(0.05, 0.1, 0.15, 0.5, 0.9);
        MemoryDecayJob job = new MemoryDecayJob(store, new MemoryDecayMetrics(null), config(100, 2));

        int archived = job.runOnce();
        assertEquals(3, archived);
        assertEquals(2, store.countActive());
    }

    @Test
    void respectsMaxArchivePerRun() {
        double[] retentions = new double[10];
        java.util.Arrays.fill(retentions, 0.1);
        FakeDecayStore store = new FakeDecayStore(retentions);
        MemoryDecayJob job = new MemoryDecayJob(store, new MemoryDecayMetrics(null), config(4, 2));

        int archived = job.runOnce();
        assertEquals(4, archived, "单轮归档上限保护：本轮至多 4 条");
        assertEquals(6, store.countActive());
    }

    @Test
    void updatesSlimRatioGauge() {
        FakeDecayStore store = new FakeDecayStore(0.1, 0.9, 0.9);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryDecayMetrics metrics = new MemoryDecayMetrics(registry);
        MemoryDecayJob job = new MemoryDecayJob(store, metrics, config(100, 10));

        job.runOnce();

        assertEquals(1.0, registry.get("aether.memory.decay.archived.total").counter().count());
        assertEquals(2, registry.get("aether.memory.decay.active.count").gauge().value(), 1e-9);
        assertEquals(1, registry.get("aether.memory.decay.archived.count").gauge().value(), 1e-9);
        assertEquals(1.0 / 3.0, registry.get("aether.memory.decay.slim.ratio").gauge().value(), 1e-9,
                "瘦身率 = archived / (archived + active)");
    }

    @Test
    void runOnceSafelySwallowsStoreFailure() {
        MemoryDecayStore failing = new MemoryDecayStore() {
            @Override public int archiveStale(double h, double m, int b) {
                throw new IllegalStateException("pg down");
            }
            @Override public long countActive() { return 0; }
            @Override public long countArchived() { return 0; }
        };
        MemoryDecayJob job = new MemoryDecayJob(failing, new MemoryDecayMetrics(null), config(10, 5));
        job.runOnceSafely(); // 不抛异常即通过（周期任务不得被单次失败终止）
        assertTrue(true);
    }

    @Test
    void noStoreIsSafeNoop() {
        MemoryDecayJob job = new MemoryDecayJob(null, new MemoryDecayMetrics(null), config(10, 5));
        assertEquals(0, job.runOnce());
    }

    @Test
    void disabledFlagDisablesDecay() {
        // aether.memory.decay.enabled=false 必须真实生效（回归：此前该开关无人读取，yml 配置是空操作）
        FakeDecayStore store = new FakeDecayStore(0.1);
        MemoryProperties.Decay d = config(10, 5);
        d.setEnabled(false);
        MemoryDecayJob job = new MemoryDecayJob(store, new MemoryDecayMetrics(null), d);

        assertEquals(0, job.runOnce(), "enabled=false 时 runOnce 必须为 no-op");
        assertEquals(1, store.countActive(), "enabled=false 时不得归档任何记忆");
    }
}

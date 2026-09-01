package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P1(4.3): 记忆生命周期指标 —— 瘦身率量化（路线图"记忆库瘦身率"）。
 *
 * <ul>
 *   <li>{@code aether.memory.decay.archived.total}：累计归档条数（Counter）；</li>
 *   <li>{@code aether.memory.decay.active.count} / {@code aether.memory.decay.archived.count}：Gauge；</li>
 *   <li>{@code aether.memory.decay.slim.ratio}：archived/(archived+active)，瘦身率。</li>
 * </ul>
 */
@Component
public class MemoryDecayMetrics {

    final AtomicLong activeCount = new AtomicLong();
    final AtomicLong archivedCount = new AtomicLong();
    /** Gauge 弱引用持有对象（防 MeterRegistry WeakReference 回收）。 */
    private final Object holder = new Object();

    private final Counter archiveCounter;

    @Autowired
    public MemoryDecayMetrics(@Autowired(required = false) MeterRegistry registry) {
        if (registry == null) {
            this.archiveCounter = null;
            return;
        }
        this.archiveCounter = Counter.builder("aether.memory.decay.archived.total")
                .description("Memories archived by the forgetting-curve decay job")
                .register(registry);
        Gauge.builder("aether.memory.decay.active.count", activeCount, AtomicLong::get)
                .description("Active (non-archived) memories")
                .register(registry);
        Gauge.builder("aether.memory.decay.archived.count", archivedCount, AtomicLong::get)
                .description("Archived (forgotten) memories")
                .register(registry);
        Gauge.builder("aether.memory.decay.slim.ratio", holder, r -> slimRatio())
                .description("Memory slimming ratio: archived / (archived + active)")
                .register(registry);
    }

    public void recordArchived(int n) {
        if (archiveCounter != null) {
            archiveCounter.increment(n);
        }
    }

    public void updateCounts(long active, long archived) {
        activeCount.set(active);
        archivedCount.set(archived);
    }

    public double slimRatio() {
        long total = activeCount.get() + archivedCount.get();
        return total == 0 ? 0.0 : archivedCount.get() / (double) total;
    }
}

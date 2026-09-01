package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.core.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P1(4.3): 遗忘曲线调度器 —— 周期性把保留分低于阈值的记忆软删归档。
 *
 * <p>调度模式对齐 {@code StaleDelegationScanner}：注入共享 scheduledPool
 * （代码库无 @EnableScheduling），单次失败降级不取消周期；配置
 * {@code aether.memory.decay.*}（enabled=false、interval-minutes <=0
 * 或 pgvector 后端未装配时禁用）。保留分公式见 {@link MemoryDecayStore#archiveStale}。</p>
 */
@Slf4j
@Component
public class MemoryDecayJob {

    private final MemoryDecayStore decayStore;
    private final MemoryDecayMetrics metrics;
    private final MemoryProperties.Decay config;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    @Autowired
    public MemoryDecayJob(ObjectProvider<MemoryDecayStore> decayStoreProvider,
                          MemoryDecayMetrics metrics,
                          MemoryProperties properties,
                          @org.springframework.beans.factory.annotation.Qualifier("scheduledPool")
                          ScheduledExecutorService scheduler) {
        this.decayStore = decayStoreProvider.getIfAvailable();
        this.metrics = metrics;
        this.config = properties != null ? properties.getDecay() : new MemoryProperties.Decay();
        this.scheduler = scheduler;
        this.ownsScheduler = false;
    }

    /** 测试构造：自建单线程调度器 + 显式配置。 */
    MemoryDecayJob(MemoryDecayStore decayStore, MemoryDecayMetrics metrics,
                   MemoryProperties.Decay config) {
        this.decayStore = decayStore;
        this.metrics = metrics;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-decay-job");
            t.setDaemon(true);
            return t;
        });
        this.ownsScheduler = true;
    }

    @PostConstruct
    public void start() {
        if (!config.isEnabled()) {
            log.info("MemoryDecayJob 已禁用（decay.enabled=false）");
            return;
        }
        if (config.getIntervalMinutes() <= 0) {
            log.info("MemoryDecayJob 已禁用（decay.interval-minutes<=0）");
            return;
        }
        if (decayStore == null) {
            log.info("MemoryDecayJob 已禁用（无 MemoryDecayStore——记忆后端为文件存储或未装配）");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::runOnceSafely,
                config.getIntervalMinutes(), config.getIntervalMinutes(), TimeUnit.MINUTES);
        log.info("MemoryDecayJob 已启动: interval={}min, halfLifeDays={}, minRetentionScore={}",
                config.getIntervalMinutes(), config.getHalfLifeDays(), config.getMinRetentionScore());
    }

    void runOnceSafely() {
        try {
            runOnce();
        } catch (Exception e) {
            // 单轮失败降级，绝不让异常取消周期调度
            log.warn("MemoryDecayJob 执行失败，降级（周期任务继续）: {}", e.getMessage());
        }
    }

    /** 执行一轮衰减（供测试直接调用）：分批归档直到无候选或达单轮上限。 */
    public int runOnce() {
        if (decayStore == null || !config.isEnabled()) {
            return 0;
        }
        int totalArchived = 0;
        while (totalArchived < config.getMaxArchivePerRun()) {
            int batch = Math.min(config.getBatchSize(),
                    config.getMaxArchivePerRun() - totalArchived);
            int archived = decayStore.archiveStale(
                    config.getHalfLifeDays(), config.getMinRetentionScore(), batch);
            totalArchived += archived;
            if (archived < batch) {
                break;
            }
        }
        if (totalArchived > 0) {
            metrics.recordArchived(totalArchived);
            log.info("MemoryDecayJob: 本轮归档 {} 条低保留分记忆（瘦身率见 aether.memory.decay.slim.ratio）",
                    totalArchived);
        }
        metrics.updateCounts(decayStore.countActive(), decayStore.countArchived());
        return totalArchived;
    }

    @PreDestroy
    public void shutdown() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}

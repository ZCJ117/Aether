package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 挂起子Agent检测调度器 — 承接 Batch 3 遗留：detectStale 无生产触发点（挂起子Agent占租约+池线程）。
 * <p>自建单线程 ScheduledExecutorService（代码库无 @EnableScheduling）；配置：
 * aether.delegation.stale-timeout（默认 PT10M）/ aether.delegation.stale-scan-interval-ms
 * （默认 60000；<=0 禁用扫描）。detectStale 完成后经 D1 finalizeDelegation 自动释放租约。</p>
 */
@Slf4j
@Component
public class StaleDelegationScanner {

    private final SubagentLifecycleService lifecycle;
    private final Duration staleTimeout;
    private final long scanIntervalMs;
    private final ScheduledExecutorService scheduler;

    /** Spring 构造。 */
    @Autowired
    public StaleDelegationScanner(SubagentLifecycleService lifecycle,
            @Value("${aether.delegation.stale-timeout:PT10M}") String staleTimeout,
            @Value("${aether.delegation.stale-scan-interval-ms:60000}") long scanIntervalMs) {
        this(lifecycle, Duration.parse(staleTimeout), scanIntervalMs);
    }

    /** 测试构造：不启动 Spring。 */
    StaleDelegationScanner(SubagentLifecycleService lifecycle, Duration staleTimeout, long scanIntervalMs) {
        this.lifecycle = lifecycle;
        this.staleTimeout = staleTimeout;
        this.scanIntervalMs = scanIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stale-delegation-scan");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (scanIntervalMs > 0) {
            scheduler.scheduleWithFixedDelay(this::scanOnce, scanIntervalMs, scanIntervalMs,
                    TimeUnit.MILLISECONDS);
            log.info("StaleDelegationScanner 已启动: timeout={} intervalMs={}", staleTimeout, scanIntervalMs);
        } else {
            log.info("StaleDelegationScanner 已禁用（interval<=0）");
        }
    }

    /** 执行一次 stale 扫描；返回受影响 id 列表（供测试直接调用）。 */
    public List<String> scanOnce() {
        List<String> stale = lifecycle.detectStale(staleTimeout);
        if (!stale.isEmpty()) {
            log.warn("StaleDelegationScanner: 检测到 {} 个挂起子Agent: {}", stale.size(), stale);
        }
        return stale;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}

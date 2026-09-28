package cn.zcj.aether.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程资源管理 —— 集中创建全部命名有界线程池（P0-1）。
 *
 * <p>消费方（aether-domain / aether-infrastructure）通过 {@code @Resource(name=...)} 或
 * {@code @Qualifier} 注入，编译期零依赖；所有池有界 + 命名 daemon 线程 + 优雅停机
 * （{@code destroyMethod="shutdown"}，由容器统一关闭，业务类不得 shutdown 共享池）。</p>
 *
 * <p>P1(1.1)：全部有界池自动绑定 Micrometer——
 * {@code ExecutorServiceMetrics}（executor.active/queued/pool.size 等）+
 * {@code aether.executor.queue.utilization}{pool}（queued/capacity，>0.8 触发容量告警，
 * 规则见 docker/prometheus/rules.yml）；无 MeterRegistry（单测）时跳过。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AetherThreadPoolProperties.class)
public class AetherExecutorRegistry {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Autowired
    public AetherExecutorRegistry(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Bean(name = "graphPool", destroyMethod = "shutdown")
    public ExecutorService graphPool(AetherThreadPoolProperties p) {
        return buildMonitored("graph", p.resolve("graph"));
    }

    @Bean(name = "toolPool", destroyMethod = "shutdown")
    public ExecutorService toolPool(AetherThreadPoolProperties p) {
        return buildMonitored("tool", p.resolve("tool"));
    }

    @Bean(name = "memoryIoPool", destroyMethod = "shutdown")
    public ExecutorService memoryIoPool(AetherThreadPoolProperties p) {
        return buildMonitored("memory-io", p.resolve("memory-io"));
    }

    @Bean(name = "sessionPersistPool", destroyMethod = "shutdown")
    public ExecutorService sessionPersistPool(AetherThreadPoolProperties p) {
        return buildMonitored("session-persist", p.resolve("session-persist"));
    }

    @Bean(name = "delegationPool", destroyMethod = "shutdown")
    public ExecutorService delegationPool(AetherThreadPoolProperties p) {
        return buildMonitored("delegation", p.resolve("delegation"));
    }

    @Bean(name = "backgroundReviewPool", destroyMethod = "shutdown")
    public ExecutorService backgroundReviewPool(AetherThreadPoolProperties p) {
        return buildMonitored("background-review", p.resolve("background-review"));
    }

    @Bean(name = "graphTracePool", destroyMethod = "shutdown")
    public ExecutorService graphTracePool(AetherThreadPoolProperties p) {
        return buildMonitored("graph-trace", p.resolve("graph-trace"));
    }

    /**
     * O17: 审计异步写入池（原 AsyncConfig.auditExecutor 迁入，统一由 Registry 管理）。
     * 由 AuditAspect 经 {@code @Qualifier("auditExecutor")} 显式提交（O17/O18 起弃用 @Async）。
     */
    @Bean(name = "auditExecutor", destroyMethod = "shutdown")
    public ExecutorService auditExecutor(AetherThreadPoolProperties p) {
        return buildMonitored("audit", p.resolve("audit"));
    }

    /**
     * 定时任务调度池（RateLimit 窗口清理、StaleDelegationScanner、MemoryDecayJob 等）。
     * 注意：ScheduledThreadPoolExecutor 语义下 core==max、内部为无界延迟队列，
     * 配置项 queueCapacity/keepAliveSeconds 对本池无效（仅 corePoolSize 与拒绝策略生效）。
     */
    @Bean(name = "scheduledPool", destroyMethod = "shutdown")
    public ScheduledExecutorService scheduledPool(AetherThreadPoolProperties p) {
        AetherThreadPoolProperties.PoolConfig c = p.resolve("scheduled");
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                c.getCorePoolSize(),
                namedFactory(c.getThreadNamePrefix()),
                handler(c.getRejectionPolicy()));
        log.info("线程池就绪: name={}, core={}, max={}, queue={}, policy={}",
                c.getThreadNamePrefix(), c.getCorePoolSize(), c.getMaxPoolSize(),
                c.getQueueCapacity(), c.getRejectionPolicy());
        return pool;
    }

    /** 构建有界池并绑定 Micrometer 指标（P1-1）。 */
    private ExecutorService buildMonitored(String name, AetherThreadPoolProperties.PoolConfig c) {
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(c.getQueueCapacity());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                c.getCorePoolSize(), c.getMaxPoolSize(),
                c.getKeepAliveSeconds(), TimeUnit.SECONDS,
                queue,
                namedFactory(c.getThreadNamePrefix()),
                handler(c.getRejectionPolicy()));
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            // binder 形式（不包装 executor）：executor.active/queued/pool.size/queue.remaining 等 gauge
            new ExecutorServiceMetrics(pool, "aether-" + name,
                    List.of(Tag.of("pool", name))).bindTo(registry);
            Gauge.builder("aether.executor.queue.utilization", queue,
                            q -> c.getQueueCapacity() == 0 ? 0.0 : q.size() / (double) c.getQueueCapacity())
                    .tag("pool", name)
                    .description("Bounded pool queue saturation: queued tasks / queue capacity")
                    .register(registry);
        }
        log.info("线程池就绪: name={}, core={}, max={}, queue={}, policy={}, metrics={}",
                c.getThreadNamePrefix(), c.getCorePoolSize(), c.getMaxPoolSize(),
                c.getQueueCapacity(), c.getRejectionPolicy(), registry != null);
        return pool;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static RejectedExecutionHandler handler(String policy) {
        return switch (policy) {
            case "DiscardPolicy" -> new ThreadPoolExecutor.DiscardPolicy();
            case "DiscardOldestPolicy" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            case "AbortPolicy" -> new ThreadPoolExecutor.AbortPolicy();
            default -> new ThreadPoolExecutor.CallerRunsPolicy();
        };
    }
}

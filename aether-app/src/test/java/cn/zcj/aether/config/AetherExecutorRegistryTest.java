package cn.zcj.aether.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AetherExecutorRegistry 单测 — P0-1 统一线程资源管理。
 * 直接调用 @Bean 方法（不启动 Spring 容器）断言：参数正确、全部有界、拒绝策略正确。
 */
class AetherExecutorRegistryTest {

    @SuppressWarnings("unchecked")
    private final AetherExecutorRegistry registry = new AetherExecutorRegistry(
            (org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>)
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    private final AetherThreadPoolProperties props = new AetherThreadPoolProperties();

    @Test
    void graphPoolDefaultsAreBoundedWithCallerRuns() {
        ExecutorService pool = registry.graphPool(props);
        ThreadPoolExecutor tpe = assertInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(4, tpe.getCorePoolSize());
        assertEquals(8, tpe.getMaximumPoolSize());
        assertEquals(200, queueCapacity(tpe));
        assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, tpe.getRejectedExecutionHandler());
        pool.shutdown();
    }

    @Test
    void customConfigOverridesDefaults() {
        AetherThreadPoolProperties custom = new AetherThreadPoolProperties();
        custom.getPools().put("graph",
                new AetherThreadPoolProperties.PoolConfig(2, 4, 50, 30, "my-graph", "DiscardPolicy"));
        ThreadPoolExecutor tpe = assertInstanceOf(ThreadPoolExecutor.class, registry.graphPool(custom));
        assertEquals(2, tpe.getCorePoolSize());
        assertEquals(4, tpe.getMaximumPoolSize());
        assertEquals(50, queueCapacity(tpe));
        assertInstanceOf(ThreadPoolExecutor.DiscardPolicy.class, tpe.getRejectedExecutionHandler());
        tpe.shutdown();
    }

    @Test
    void scheduledPoolIsScheduledThreadPool() {
        ScheduledExecutorService sched = registry.scheduledPool(props);
        assertInstanceOf(ScheduledThreadPoolExecutor.class, sched);
        sched.shutdown();
    }

    @Test
    void allPoolsAreBoundedAndNamed() {
        ExecutorService[] pools = {
                registry.graphPool(props),
                registry.toolPool(props),
                registry.memoryIoPool(props),
                registry.sessionPersistPool(props),
                registry.delegationPool(props),
                registry.backgroundReviewPool(props),
                registry.graphTracePool(props)
        };
        for (ExecutorService p : pools) {
            ThreadPoolExecutor tpe = assertInstanceOf(ThreadPoolExecutor.class, p);
            assertTrue(queueCapacity(tpe) > 0, "队列必须是有界的");
            assertTrue(tpe.getMaximumPoolSize() > 0, "最大线程数必须 > 0");
            p.shutdown();
        }
        registry.scheduledPool(props).shutdown();
    }

    /** 队列容量 = 已占用 + 剩余。 */
    private static int queueCapacity(ThreadPoolExecutor tpe) {
        return tpe.getQueue().remainingCapacity() + tpe.getQueue().size();
    }

    @Test
    void applicationYmlGraphPoolOverridesActuallyBind() throws Exception {
        // 绑定回归：application.yml 的 aether.thread-pools.pools.graph.* 必须绑定进 pools map。
        // 历史缺陷：yml 曾写成 aether.thread-pools.graph.*（缺 pools 层级），Spring 静默忽略未知属性，
        // 池参数永远走代码内 DEFAULT——调参形同虚设。
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var sources = loader.load("app-yml",
                new org.springframework.core.io.ClassPathResource("application.yml"));
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources));
        AetherThreadPoolProperties bound = binder.bind("aether.thread-pools",
                org.springframework.boot.context.properties.bind.Bindable.of(AetherThreadPoolProperties.class))
                .get();
        assertTrue(bound.getPools().containsKey("graph"),
                "application.yml 的 graph 池覆盖必须绑定进 pools map（检查 yml 键是否为 aether.thread-pools.pools.graph.*）");
        assertEquals(4, bound.resolve("graph").getCorePoolSize());
        assertEquals(200, bound.resolve("graph").getQueueCapacity());
        assertEquals("CallerRunsPolicy", bound.resolve("graph").getRejectionPolicy());
    }
}

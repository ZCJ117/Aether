package cn.zcj.aether.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一线程池配置 —— 前缀 {@code aether.thread-pools.*}。
 *
 * <p>P0-1 统一线程资源管理：全部业务线程池集中定义，每池可独立覆盖；
 * 未覆盖的池使用 {@link #DEFAULT} 参数。所有池均有界（核心线程 + 有界队列 + 拒绝策略）。</p>
 */
@Data
@ConfigurationProperties(prefix = "aether.thread-pools")
public class AetherThreadPoolProperties {

    /** 默认参数（core / max / queue / keepAliveSec / namePrefix / policy）。 */
    private static final Map<String, PoolConfig> DEFAULT = Map.of(
            "graph",             new PoolConfig(4, 8,   200, 60, "aether-graph",      "CallerRunsPolicy"),
            "tool",              new PoolConfig(4, 16,  200, 60, "aether-tool",       "CallerRunsPolicy"),
            "memory-io",         new PoolConfig(2, 4,   500, 60, "aether-memory",     "CallerRunsPolicy"),
            "session-persist",   new PoolConfig(2, 4,   1000, 60, "aether-session",   "CallerRunsPolicy"),
            "delegation",        new PoolConfig(2, 5,   500, 60, "aether-delegation", "CallerRunsPolicy"),
            "background-review", new PoolConfig(1, 1,   100, 60, "aether-bg-review",  "DiscardPolicy"),
            "graph-trace",       new PoolConfig(1, 1,   200, 60, "aether-trace",      "DiscardPolicy"),
            "scheduled",         new PoolConfig(2, 4,   100, 60, "aether-sched",      "CallerRunsPolicy"),
            "audit",             new PoolConfig(2, 5,   100, 60, "audit",             "CallerRunsPolicy")
    );

    /** 允许覆盖：key 为池名，值为该池参数；未覆盖的池使用默认。 */
    private Map<String, PoolConfig> pools = new HashMap<>();

    public PoolConfig resolve(String name) {
        PoolConfig c = pools.get(name);
        return c != null ? c : DEFAULT.get(name);
    }

    @Data
    public static class PoolConfig {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private long keepAliveSeconds;
        private String threadNamePrefix;
        /** AbortPolicy | DiscardPolicy | DiscardOldestPolicy | CallerRunsPolicy */
        private String rejectionPolicy;

        public PoolConfig() {
        }

        public PoolConfig(int core, int max, int queue, long keepAlive,
                          String prefix, String policy) {
            this.corePoolSize = core;
            this.maxPoolSize = max;
            this.queueCapacity = queue;
            this.keepAliveSeconds = keepAlive;
            this.threadNamePrefix = prefix;
            this.rejectionPolicy = policy;
        }
    }
}

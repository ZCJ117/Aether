package cn.zcj.aether.domain.agent.service.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 命名守护线程工厂 —— 后台单线程辅助器（RAG 查询改写/重排、事件桥发送）共用，
 * 统一「具名 + daemon」约定；业务线程池仍由 {@code AetherExecutorRegistry} 统一治理，
 * 不经过这里（那些池需要指标与有界队列）。
 */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    /** 具名守护 ThreadFactory（单线程 executor 下线程名即 {@code name}）。 */
    public static ThreadFactory factory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** 单线程守护 executor；关闭由持有方负责（@PreDestroy 等）。 */
    public static ExecutorService singleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(factory(name));
    }
}

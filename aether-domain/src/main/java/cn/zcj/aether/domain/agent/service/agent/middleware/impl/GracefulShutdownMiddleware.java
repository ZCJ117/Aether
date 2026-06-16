package cn.zcj.aether.domain.agent.service.agent.middleware.impl;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 优雅关闭中间件。
 * 关闭期间拒绝新请求，已在执行的请求不受影响。
 */
@Slf4j
public class GracefulShutdownMiddleware implements AgentMiddleware {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Override public String name() { return "graceful-shutdown"; }
    @Override public int priority() { return 5; }

    public void initiateShutdown() {
        shuttingDown.set(true);
        log.info("优雅关闭已启动");
    }

    public boolean isShuttingDown() { return shuttingDown.get(); }

    @Override
    public Flowable<RuntimeEvent> onAgent(Agent agent, RuntimeContext ctx,
            Supplier<Flowable<RuntimeEvent>> next) {
        if (shuttingDown.get()) {
            return Flowable.just(RuntimeEvent.error("服务正在关闭，请稍后重试"));
        }
        return next.get();
    }
}

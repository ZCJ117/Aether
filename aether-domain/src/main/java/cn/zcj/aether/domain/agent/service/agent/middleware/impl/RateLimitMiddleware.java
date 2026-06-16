package cn.zcj.aether.domain.agent.service.agent.middleware.impl;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 速率限制中间件。
 * 在 onAgent 拦截点检查调用频率，超过限制则拒绝执行。
 */
@Slf4j
public class RateLimitMiddleware implements AgentMiddleware {

    private final int maxCallsPerWindow;
    private final Duration windowDuration;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitMiddleware(int maxCallsPerWindow, Duration windowDuration) {
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.windowDuration = windowDuration;
    }

    @Override
    public String name() { return "rate-limit"; }

    @Override
    public int priority() { return 10; } // 最高优先级

    @Override
    public Flowable<RuntimeEvent> onAgent(Agent agent, RuntimeContext ctx,
            Supplier<Flowable<RuntimeEvent>> next) {
        String key = ctx.userId() + ":" + agent.getId();
        WindowCounter counter = counters.computeIfAbsent(key,
            k -> new WindowCounter(Instant.now()));

        synchronized (counter) {
            Instant now = Instant.now();
            if (Duration.between(counter.windowStart, now).compareTo(windowDuration) > 0) {
                counter.windowStart = now;
                counter.count.set(0);
            }
            if (counter.count.incrementAndGet() > maxCallsPerWindow) {
                log.warn("速率限制触发: user={}, agent={}, count={}",
                    ctx.userId(), agent.getId(), counter.count.get());
                return Flowable.just(
                    RuntimeEvent.error("请求过于频繁，请在 " + windowDuration.getSeconds() + " 秒后重试"));
            }
        }
        return next.get();
    }

    private static class WindowCounter {
        Instant windowStart;
        AtomicInteger count;
        WindowCounter(Instant start) { this.windowStart = start; this.count = new AtomicInteger(0); }
    }
}

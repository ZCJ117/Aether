package cn.zcj.aether.domain.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P1(1.1): 模型调用等待可观测 —— ReAct"轮间阻塞"线程占用的量化窗口。
 *
 * <p>{@code invokeModelStreaming} 每轮以 {@code CountDownLatch.await(callTimeoutMs)} 占用
 * 一个调用线程等待模型流；本组件暴露两个指标供容量规划与告警使用：</p>
 * <ul>
 *   <li>{@code aether.model.waiting.threads}（Gauge）：当前阻塞在 latch 等待上的调用线程数；
 *       与 graphPool 容量对比即可得出"模型等待占用比"（容量公式见 docs/capacity-planning.md）。</li>
 *   <li>{@code aether.model.call.timeouts.total}（Counter, tag outcome=timeout）：模型调用超时次数，
 *       超时率 = timeouts / turns（turns 来自 AgentMetrics）。</li>
 * </ul>
 *
 * <p>用法：{@code try (var h = observability.beginWait()) { latch.await(...); }}；
 * 未注册 MeterRegistry（单测直 new Agent）时所有方法为安全 no-op。</p>
 */
@Component
public class ModelCallObservability {

    private final AtomicInteger waitingThreads = new AtomicInteger();
    private final Counter timeoutCounter;
    /** registry 是否就绪（Gauge 注册后引用由 registry 持有，这里只需可用性标记）。 */
    private final boolean gaugeRegistered;

    /**
     * MeterRegistry 由 actuator 自动装配；无 registry（如测试直 new Agent、未引入 actuator）时全 no-op。
     */
    public ModelCallObservability(@Autowired(required = false) MeterRegistry registry) {
        if (registry == null) {
            this.timeoutCounter = null;
            this.gaugeRegistered = false;
            return;
        }
        Gauge
                .builder("aether.model.waiting.threads", waitingThreads, AtomicInteger::get)
                .description("Threads currently blocked waiting for a model streaming call")
                .register(registry);
        this.gaugeRegistered = true;
        this.timeoutCounter = Counter.builder("aether.model.call.timeouts.total")
                .tag("outcome", "timeout")
                .description("Model calls terminated by the per-call timeout budget")
                .register(registry);
    }

    /** 进入 latch 等待前调用；返回的句柄 close（或 try-with-resources 自动）后递减。 */
    public WaitHandle beginWait() {
        if (!gaugeRegistered) {
            return NoopHandle.INSTANCE;
        }
        waitingThreads.incrementAndGet();
        return new WaitHandle() {
            private boolean closed;
            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    waitingThreads.decrementAndGet();
                }
            }
        };
    }

    /** 模型调用超时（预算耗尽 dispose 流）时计数。 */
    public void recordTimeout() {
        if (timeoutCounter != null) {
            timeoutCounter.increment();
        }
    }

    /** 等待句柄：close 幂等。 */
    public interface WaitHandle extends AutoCloseable {
        @Override
        void close();
    }

    private enum NoopHandle implements WaitHandle {
        INSTANCE;
        @Override
        public void close() {
            // no-op：无 MeterRegistry 时零开销
        }
    }
}

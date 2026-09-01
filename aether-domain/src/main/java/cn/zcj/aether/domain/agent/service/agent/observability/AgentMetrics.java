package cn.zcj.aether.domain.agent.service.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * Agent Micrometer 指标采集。
 * 所有指标以 aether_agent 为前缀，自动暴露给 Prometheus。
 */
@Component
public class AgentMetrics {

    @Resource
    private MeterRegistry meterRegistry;

    // ====== 计数器 ======
    private Counter turnCounter;
    private Counter errorCounter;
    private Counter toolCallCounter;
    private Counter toolErrorCounter;
    private Counter planReplanCounter;
    private Counter planCompletionSuccessCounter;
    private Counter planCompletionFailureCounter;
    private Counter planReflectionCounter;

    // ====== 直方图/计时器 ======
    private Timer turnLatency;
    private Timer modelCallLatency;
    private Timer toolCallLatency;

    @PostConstruct
    public void init() {
        this.turnCounter = Counter.builder("aether.agent.turns.total")
            .description("Agent turn 总数")
            .tag("version", "1.0")
            .register(meterRegistry);

        this.errorCounter = Counter.builder("aether.agent.errors.total")
            .description("Agent 错误总数")
            .register(meterRegistry);

        this.toolCallCounter = Counter.builder("aether.agent.tool.calls.total")
            .description("工具调用总数")
            .register(meterRegistry);

        this.toolErrorCounter = Counter.builder("aether.agent.tool.errors.total")
            .description("工具调用失败总数")
            .register(meterRegistry);

        this.planReplanCounter = Counter.builder("aether.agent.plan.replans.total")
            .description("PlanActAgent 重规划次数")
            .register(meterRegistry);

        this.planCompletionSuccessCounter = Counter.builder("aether.agent.plan.completions.total")
            .description("PlanActAgent 任务完成/失败次数")
            .tag("result", "success")
            .register(meterRegistry);

        this.planCompletionFailureCounter = Counter.builder("aether.agent.plan.completions.total")
            .description("PlanActAgent 任务完成/失败次数")
            .tag("result", "failure")
            .register(meterRegistry);

        this.planReflectionCounter = Counter.builder("aether.agent.plan.reflections.total")
            .description("PlanActAgent 任务后自评次数")
            .register(meterRegistry);

        this.turnLatency = Timer.builder("aether.agent.turn.latency")
            .description("Agent turn 延迟")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        this.modelCallLatency = Timer.builder("aether.agent.model.call.latency")
            .description("模型调用延迟")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        this.toolCallLatency = Timer.builder("aether.agent.tool.call.latency")
            .description("工具调用延迟")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
    }

    // ====== 采集方法 ======

    public void recordTurn(long durationMs, String agentId) {
        turnCounter.increment();
        turnLatency.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordError(String agentId) {
        errorCounter.increment();
    }

    public void recordToolCall(String toolName, boolean success, long durationMs) {
        toolCallCounter.increment();
        toolCallLatency.record(durationMs, TimeUnit.MILLISECONDS);
        if (!success) toolErrorCounter.increment();
    }

    public void recordPlanReplan() {
        planReplanCounter.increment();
    }

    public void recordPlanCompletion(boolean success) {
        if (success) {
            planCompletionSuccessCounter.increment();
        } else {
            planCompletionFailureCounter.increment();
        }
    }

    public void recordPlanReflection() {
        planReflectionCounter.increment();
    }

    public void recordModelCall(String modelName, long durationMs) {
        modelCallLatency.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** O18: token 计数器缓存（key = name|model），避免每次调用动态注册导致 meter 表膨胀。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Counter> tokenCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void recordTokenUsage(String modelName, int inputTokens, int outputTokens, double costUsd) {
        // O18: 使用 Counter 缓存累加记录 token 总量（同名同 tag 只注册一次）
        String model = modelName != null ? modelName : "unknown";
        tokenCounters.computeIfAbsent("tokens.input|" + model,
                        k -> Counter.builder("aether.agent.tokens.input")
                                .tag("model", model)
                                .register(meterRegistry))
                .increment(inputTokens);
        tokenCounters.computeIfAbsent("tokens.output|" + model,
                        k -> Counter.builder("aether.agent.tokens.output")
                                .tag("model", model)
                                .register(meterRegistry))
                .increment(outputTokens);
    }
}

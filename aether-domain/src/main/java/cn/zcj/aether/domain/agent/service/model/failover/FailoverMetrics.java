package cn.zcj.aether.domain.agent.service.model.failover;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * P0(1.5-步骤3): 模型容错恢复指标。
 * <ul>
 *   <li>{@code aether.model.recovery.branch}{branch=...} —— 各恢复分支触发次数（Counter）</li>
 *   <li>{@code aether.model.fallback.switches} —— fallback 链切换次数（Counter）</li>
 *   <li>{@code aether.failover.compress}{result=...} —— 上下文压缩恢复分支执行结果（Counter）</li>
 * </ul>
 * 压测混沌验证（429×3 → 500×2 → 成功）通过这两个指标观测切换次数与恢复路径。
 */
@Slf4j
@Component
public class FailoverMetrics {

    public static final String BRANCH_METRIC = "aether.model.recovery.branch";
    public static final String FALLBACK_SWITCH_METRIC = "aether.model.fallback.switches";
    public static final String COMPRESS_METRIC = "aether.failover.compress";

    private final MeterRegistry meterRegistry;
    private final Counter fallbackSwitches;
    private final ConcurrentHashMap<RecoveryBranch, Counter> branchCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompressOutcome, Counter> compressCounters = new ConcurrentHashMap<>();

    public FailoverMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.fallbackSwitches = meterRegistry == null ? null : Counter.builder(FALLBACK_SWITCH_METRIC)
                .description("fallback 链模型切换次数")
                .register(meterRegistry);
    }

    /** 记录一次恢复分支触发（重试/退避/轮换/压缩/切换）。 */
    public void recordBranch(RecoveryBranch branch) {
        if (meterRegistry == null) {
            log.debug("无 MeterRegistry，跳过容错分支记录: {}", branch);
            return;
        }
        branchCounters.computeIfAbsent(branch, b -> Counter.builder(BRANCH_METRIC)
                        .description("模型容错恢复分支触发次数")
                        .tag("branch", b.name().toLowerCase())
                        .register(meterRegistry))
                .increment();
    }

    /** 记录一次 fallback 链切换（主模型 → 兜底模型）。 */
    public void recordFallbackSwitch() {
        if (meterRegistry == null) {
            log.debug("无 MeterRegistry，跳过 fallback 切换记录");
            return;
        }
        fallbackSwitches.increment();
    }

    /** 记录上下文压缩恢复分支的执行结果。 */
    public void recordCompressResult(CompressOutcome outcome) {
        if (meterRegistry == null) {
            log.debug("无 MeterRegistry，跳过上下文压缩结果记录: {}", outcome);
            return;
        }
        compressCounters.computeIfAbsent(outcome, o -> Counter.builder(COMPRESS_METRIC)
                        .description("上下文压缩恢复分支执行结果")
                        .tag("result", o.name().toLowerCase())
                        .register(meterRegistry))
                .increment();
    }

    /** 上下文压缩恢复分支的四种出口：成功 / 无变化 / 回调缺失 / 异常。 */
    public enum CompressOutcome { SUCCESS, INEFFECTIVE, NOOP, ERROR }
}

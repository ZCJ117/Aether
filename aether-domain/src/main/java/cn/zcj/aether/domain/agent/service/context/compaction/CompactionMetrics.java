package cn.zcj.aether.domain.agent.service.context.compaction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P0(1.5-步骤2): 上下文压缩收益指标。
 * <ul>
 *   <li>{@code aether.compaction.count} —— 压缩执行次数（Counter）</li>
 *   <li>{@code aether.compaction.tokens.pre} / {@code aether.compaction.tokens.post} —— 压缩前后累计 token（Counter）</li>
 *   <li>{@code aether.compaction.last.saving.ratio} —— 最近一次 token 节省率（Gauge，(pre-post)/pre）</li>
 * </ul>
 * 注册表缺省（无 actuator 环境）时全部操作为 no-op。
 */
@Slf4j
@Component
public class CompactionMetrics {

    public static final String COUNT_METRIC = "aether.compaction.count";
    public static final String PRE_TOKENS_METRIC = "aether.compaction.tokens.pre";
    public static final String POST_TOKENS_METRIC = "aether.compaction.tokens.post";
    public static final String LAST_SAVING_RATIO_METRIC = "aether.compaction.last.saving.ratio";

    private final MeterRegistry meterRegistry;
    private final Counter count;
    private final Counter preTokens;
    private final Counter postTokens;
    private final AtomicLong lastSavingRatio = new AtomicLong();

    public CompactionMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry == null) {
            this.count = null;
            this.preTokens = null;
            this.postTokens = null;
            return;
        }
        this.count = Counter.builder(COUNT_METRIC)
                .description("上下文压缩执行次数")
                .register(meterRegistry);
        this.preTokens = Counter.builder(PRE_TOKENS_METRIC)
                .description("压缩前累计 token 数")
                .register(meterRegistry);
        this.postTokens = Counter.builder(POST_TOKENS_METRIC)
                .description("压缩后累计 token 数")
                .register(meterRegistry);
        Gauge.builder(LAST_SAVING_RATIO_METRIC, lastSavingRatio, AtomicLong::doubleValue)
                .description("最近一次压缩的 token 节省率 (pre-post)/pre")
                .register(meterRegistry);
    }

    /** 上报一次压缩结果（仅在确实发生压缩时调用）。 */
    public void recordCompaction(int preCompactTokens, int postCompactTokens) {
        if (meterRegistry == null) {
            log.debug("无 MeterRegistry，跳过压缩指标记录: pre={}, post={}", preCompactTokens, postCompactTokens);
            return;
        }
        count.increment();
        preTokens.increment(preCompactTokens);
        postTokens.increment(postCompactTokens);
        lastSavingRatio.set(preCompactTokens > 0
                ? Math.round(1e4 * (preCompactTokens - postCompactTokens) / preCompactTokens) : 0);
    }
}

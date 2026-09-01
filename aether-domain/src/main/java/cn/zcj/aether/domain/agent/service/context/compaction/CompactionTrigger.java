package cn.zcj.aether.domain.agent.service.context.compaction;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 双阈值压缩触发器 — O8 参数化（对齐 hermes context_compressor）。
 *
 * <p>键前缀：{@code aether.context.compaction.*}，默认值保持改造前行为：</p>
 * <ul>
 *   <li>{@code trigger-messages}（150）/ {@code trigger-tokens}（80000）— CompactionPipeline 双阈值</li>
 *   <li>{@code keep-messages}（20）/ {@code keep-tokens}（10000）— SafeCutoffFinder 保留区</li>
 *   <li>{@code threshold-percent}（0.9）— autoCompact 触发阈值：currentTokens ≥ effectiveWindow × thresholdPercent
 *       （hermes threshold_percent=0.50，此处默认维持现状 0.9，可配置收紧）</li>
 *   <li>{@code min-messages-to-compact}（20）— 消息条数下限（替代原硬编码 {@code messages.size()<20}）</li>
 *   <li>{@code protect-first-n}（0）— 头部保护条数（hermes protect_first_n=3；默认 0 保持现状）</li>
 *   <li>{@code protect-last-n}（15）— 尾部保护条数（原 keepRecent=15）</li>
 *   <li>{@code tail-token-ratio}（0.0）— 尾部保护改按 token 预算：contextWindow × ratio，0=禁用（按条数）</li>
 *   <li>{@code tail-token-max}（10000）— 尾部 token 预算上限（hermes 10K cap）</li>
 *   <li>{@code ineffective-progress-ratio}（0.05）— 压缩有效进展判据：token 降幅低于该值视为无效（hermes 5%）</li>
 *   <li>{@code ineffective-suppress-rounds}（3）— 无效压缩后跳过压缩的轮数（防抖防循环）</li>
 * </ul>
 */
@Getter
@Component
public class CompactionTrigger {

    @Value("${aether.context.compaction.trigger-messages:150}")
    private int triggerMessages;

    @Value("${aether.context.compaction.trigger-tokens:80000}")
    private int triggerTokens;

    @Value("${aether.context.compaction.keep-messages:20}")
    private int keepMessages;

    @Value("${aether.context.compaction.keep-tokens:10000}")
    private int keepTokens;

    // ========== O8: autoCompact 触发与裁剪参数（默认值 = 改造前行为；内联初始化保证手工构造实例同语义） ==========

    @Value("${aether.context.compaction.threshold-percent:0.9}")
    private double thresholdPercent = 0.9;

    @Value("${aether.context.compaction.min-messages-to-compact:20}")
    private int minMessagesToCompact = 20;

    @Value("${aether.context.compaction.protect-first-n:0}")
    private int protectFirstN = 0;

    @Value("${aether.context.compaction.protect-last-n:15}")
    private int protectLastN = 15;

    @Value("${aether.context.compaction.tail-token-ratio:0.0}")
    private double tailTokenRatio = 0.0;

    @Value("${aether.context.compaction.tail-token-max:10000}")
    private int tailTokenMax = 10_000;

    @Value("${aether.context.compaction.ineffective-progress-ratio:0.05}")
    private double ineffectiveProgressRatio = 0.05;

    @Value("${aether.context.compaction.ineffective-suppress-rounds:3}")
    private int ineffectiveSuppressRounds = 3;

    /**
     * 判断是否应触发压缩。
     *
     * @param messageCount 当前消息总数
     * @param tokenCount   当前估算 token 总数
     * @return true 如果任一超过阈值
     */
    public boolean shouldCompact(int messageCount, int tokenCount) {
        return messageCount > triggerMessages || tokenCount > triggerTokens;
    }

    /**
     * O8: 计算尾部保护区的 token 预算。
     *
     * @param contextWindow 模型上下文窗口
     * @return 尾部 token 预算；tail-token-ratio ≤ 0 时返回 -1（表示按条数保护）
     */
    public int tailTokenBudget(int contextWindow) {
        if (tailTokenRatio <= 0) {
            return -1;
        }
        return Math.min((int) (contextWindow * tailTokenRatio), tailTokenMax);
    }
}

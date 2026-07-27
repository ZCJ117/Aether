package cn.zcj.aether.domain.agent.service.context;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 三层 Token 预算模型。
 *
 * 上下文窗口:
 * ├── 固定开销层 (~15%): 系统提示词 + 工具定义 + 记忆注入
 * ├── 弹性层 (~70%): 对话历史 + 工具参数 + 工具结果
 * └── 预留层 (~15%): 模型输出 + 安全缓冲
 */
@Slf4j
@Getter
public class TokenBudget {

    private final int contextWindow;
    private final int fixedOverhead;
    private final int outputReserve;
    private final int elasticBudget;
    private int currentElasticUsage;

    /** 告警阈值 */
    private static final double WARN_THRESHOLD = 0.80;
    private static final double ERROR_THRESHOLD = 0.95;

    /**
     * @param contextWindow 模型上下文窗口（如 200000）
     * @param fixedOverhead 固定开销 token 数（在编译阶段估算）
     */
    public TokenBudget(int contextWindow, int fixedOverhead) {
        this.contextWindow = contextWindow;
        this.fixedOverhead = fixedOverhead;
        this.outputReserve = (int) (contextWindow * 0.15);
        this.elasticBudget = contextWindow - fixedOverhead - outputReserve;
        this.currentElasticUsage = 0;
    }

    /**
     * 尝试消费弹性预算。返回 false 表示预算不足，需触发压缩。
     */
    public boolean tryConsume(int estimatedTokens) {
        if (currentElasticUsage + estimatedTokens > elasticBudget) {
            log.warn("Token预算拒绝消费: 需要={} 当前={} 上限={}",
                    estimatedTokens, currentElasticUsage, elasticBudget);
            return false;
        }
        currentElasticUsage += estimatedTokens;
        checkThresholds();
        return true;
    }

    /** 压缩后重置弹性层使用量 */
    public void reset(int newElasticUsage) {
        this.currentElasticUsage = Math.max(0, Math.min(newElasticUsage, elasticBudget));
        log.info("TokenBudget 重置: elasticUsage={}/{}", currentElasticUsage, elasticBudget);
    }

    /** 剩余弹性预算 */
    public int remainingElastic() {
        return Math.max(0, elasticBudget - currentElasticUsage);
    }

    /** 使用比例 0-1 */
    public double usageRatio() {
        return elasticBudget > 0 ? (double) currentElasticUsage / elasticBudget : 0;
    }

    private void checkThresholds() {
        double ratio = usageRatio();
        if (ratio >= ERROR_THRESHOLD) {
            log.error("Token预算耗尽: usage={}% used={}/{}",
                    String.format("%.1f", ratio * 100), currentElasticUsage, elasticBudget);
        } else if (ratio >= WARN_THRESHOLD) {
            log.warn("Token预算告警: usage={}% used={}/{}",
                    String.format("%.1f", ratio * 100), currentElasticUsage, elasticBudget);
        }
    }
}

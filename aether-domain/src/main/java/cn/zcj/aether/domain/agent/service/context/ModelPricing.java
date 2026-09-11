package cn.zcj.aether.domain.agent.service.context;

/**
 * 模型定价 — 记录模型的 USD 成本。
 *
 * <p>M7 成本熔断：TokenBudget 基于此信息计算每次调用的实际成本，
 * 当累计成本超过 maxCostUsd 时触发熔断。
 *
 * @param modelId             模型标识（支持通配符，如 "deepseek-*"）
 * @param inputCostPer1kTokens  每千输入 token 美元成本
 * @param outputCostPer1kTokens 每千输出 token 美元成本
 *
 * <p><b>【架构亮点 · 上下文工程与成本治理】</b><br>
 * 面试举证点：M7 成本熔断的计价原子单元（:19-22）以千 token 单价线性累加输入/输出成本，
 * 被 TokenBudget.accumulateCost 调用，配合 ModelPricingRegistry 三级定价，让每次模型调用成本可计量、可熔断。</p>
 */
public record ModelPricing(
        String modelId,
        double inputCostPer1kTokens,
        double outputCostPer1kTokens
) {
    /** 计算指定 token 数的成本 */
    // 【成本治理】计价核心：input/output 各按千 token 单价线性累加，输出通常更贵
    public double calculateCost(int inputTokens, int outputTokens) {
        return (inputTokens / 1000.0) * inputCostPer1kTokens
             + (outputTokens / 1000.0) * outputCostPer1kTokens;
    }
}

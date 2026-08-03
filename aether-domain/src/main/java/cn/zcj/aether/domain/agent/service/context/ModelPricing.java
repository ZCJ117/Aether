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
 */
public record ModelPricing(
        String modelId,
        double inputCostPer1kTokens,
        double outputCostPer1kTokens
) {
    /** 计算指定 token 数的成本 */
    public double calculateCost(int inputTokens, int outputTokens) {
        return (inputTokens / 1000.0) * inputCostPer1kTokens
             + (outputTokens / 1000.0) * outputCostPer1kTokens;
    }
}

package cn.zcj.aether.domain.agent.service.subagent;

/**
 * 委派预算配置 — 对齐 hermes delegate_tool.py DEFAULT_MAX_SUMMARY_CHARS（L716，24000）。
 * <p>maxSummaryChars=0 禁用截断；maxTokensPerChild 为每子Agent上下文预算
 * （本批仅记录，实际每子Agent TokenBudget 由 DefaultAgentFactory 创建，见设计 §6.3⑦）。</p>
 */
public record DelegationBudget(int maxTokensPerChild, int maxSummaryChars) {

    public static final int DEFAULT_MAX_TOKENS_PER_CHILD = 8000;
    /** 对齐 ResultRefiner.MAX_RESULT_LENGTH=2000（Aether 摘要既有上限）。 */
    public static final int DEFAULT_MAX_SUMMARY_CHARS = 2000;

    public DelegationBudget {
        maxTokensPerChild = maxTokensPerChild > 0 ? maxTokensPerChild : DEFAULT_MAX_TOKENS_PER_CHILD;
        maxSummaryChars = maxSummaryChars >= 0 ? maxSummaryChars : DEFAULT_MAX_SUMMARY_CHARS;
    }

    public static DelegationBudget defaults() {
        return new DelegationBudget(DEFAULT_MAX_TOKENS_PER_CHILD, DEFAULT_MAX_SUMMARY_CHARS);
    }
}

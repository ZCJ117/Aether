package cn.zcj.aether.domain.agent.service.subagent;

/**
 * 委派预算配置 — 对齐 hermes delegate_tool.py DEFAULT_MAX_SUMMARY_CHARS（L716，24000）。
 * <p>maxSummaryChars=0 禁用截断；maxTokensPerChild 为每子Agent上下文预算
 * （本批仅记录，实际每子Agent TokenBudget 由 DefaultAgentFactory 创建，见设计 §6.3⑦）。</p>
 *
 * <p><b>【架构亮点 · 上下文工程与成本治理】</b><br>
 * 面试举证点：①上下文隔离——每个子 Agent 独立 maxTokensPerChild=8000（:8-21）预算，避免委派任务挤占主会话上下文；<br>
 * ②与 TokenBudget 三层预算解耦：子 Agent 自带 TokenBudget，父 Agent 通过委派预算上限控制横向 fan-out 的总上下文占用，实现多 Agent 场景的成本与上下文阻燃。</p>
 */
public record DelegationBudget(int maxTokensPerChild, int maxSummaryChars) {

    // 【上下文工程】每子 Agent 默认上下文预算 8000 token，实现委派任务的上下文隔离与上限约束
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

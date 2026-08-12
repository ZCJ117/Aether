package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子结果合并 — 对齐 hermes delegate_tool.py _apply_summary_budget（L1897）/ _finalize_child_results（L2616）。
 * <p>将多个子结果合并为单个摘要：连接各 summary（去空）、按 maxSummaryChars 截断、合并 toolStats。
 * 注：hermes _finalize_child_results 实际逐 result 应用契约而非聚合；Aether 简化为一处合并（设计 §6.3⑦）。</p>
 */
@Slf4j
@Component
public class ChildResultAggregator {

    public ResultRefiner.SubAgentResult merge(List<ResultRefiner.SubAgentResult> children, DelegationBudget budget) {
        if (children == null || children.isEmpty()) {
            return new ResultRefiner.SubAgentResult("未完成", "[无子结果]", Map.of());
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> toolStats = new LinkedHashMap<>();
        for (ResultRefiner.SubAgentResult child : children) {
            if (child.summary() != null && !child.summary().isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n---\n");
                }
                sb.append(child.summary());
            }
            if (child.toolStats() != null) {
                child.toolStats().forEach((k, v) -> toolStats.merge(k, v, Integer::sum));
            }
        }
        String merged = sb.toString();
        if (budget.maxSummaryChars() > 0 && merged.length() > budget.maxSummaryChars()) {
            merged = merged.substring(0, budget.maxSummaryChars()) + "...";
        }
        return new ResultRefiner.SubAgentResult("成功", merged, toolStats);
    }
}

package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChildResultAggregatorTest {

    private final ChildResultAggregator aggregator = new ChildResultAggregator();

    private static ResultRefiner.SubAgentResult res(String summary, Map<String, Integer> stats) {
        return new ResultRefiner.SubAgentResult("成功", summary, stats);
    }

    @Test
    void mergesSummariesAndToolStats() {
        var merged = aggregator.merge(List.of(
                res("[子任务A] 结论A", Map.of("code", 2)),
                res("[子任务B] 结论B", Map.of("search", 1))),
                DelegationBudget.defaults());
        assertEquals("成功", merged.status());
        assertTrue(merged.summary().contains("结论A"));
        assertTrue(merged.summary().contains("结论B"));
        assertEquals(2, merged.toolStats().get("code"));
        assertEquals(1, merged.toolStats().get("search"));
    }

    @Test
    void truncatesToBudget() {
        var merged = aggregator.merge(List.of(
                res("A".repeat(50), Map.of())), new DelegationBudget(8000, 20));
        assertTrue(merged.summary().length() <= 23, "应截断到 maxSummaryChars(20)+省略号");
        assertTrue(merged.summary().endsWith("..."));
    }

    @Test
    void disabledBudgetSkipsTruncation() {
        var merged = aggregator.merge(List.of(
                res("A".repeat(50), Map.of())), new DelegationBudget(8000, 0));
        assertEquals(50, merged.summary().length());
    }

    @Test
    void emptyListReturnsUnfinished() {
        var merged = aggregator.merge(List.of(), DelegationBudget.defaults());
        assertEquals("未完成", merged.status());
    }

    @Test
    void skipsBlankSummaries() {
        var merged = aggregator.merge(List.of(
                res("   ", Map.of()), res("[结论]", Map.of())), DelegationBudget.defaults());
        assertFalse(merged.summary().contains("---"));
    }
}

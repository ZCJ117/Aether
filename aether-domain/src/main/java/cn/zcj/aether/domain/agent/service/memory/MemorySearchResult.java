package cn.zcj.aether.domain.agent.service.memory;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 记忆搜索结果。
 */
@Data
@AllArgsConstructor
public class MemorySearchResult {
    private MemoryRecord record;
    private double score;

    public static MemorySearchResult of(MemoryRecord record, double score) {
        return new MemorySearchResult(record, score);
    }
}

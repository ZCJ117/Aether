package cn.zcj.aether.domain.agent.service.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 统一记忆门面。
 * 灵感来源：CrewAI Memory 门面（LLM 增强编码 + 自适应召回）
 *          + MetaGPT Memory/WorkingMemory/LongTermMemory。
 */
public interface MemoryFacade {

    // =========================================================
    // 存储
    // =========================================================

    /**
     * 存储一条记忆。
     * 使用 LLM 推断 scope、categories、importance 等元数据。
     */
    CompletableFuture<MemoryRecord> remember(String content, MemoryScope scope, StoreOptions options);

    /** 批量存储记忆（后台非阻塞） */
    void rememberMany(List<MemoryEntry> entries, MemoryScope scope);

    // =========================================================
    // 检索
    // =========================================================

    /**
     * 自适应召回。
     */
    CompletableFuture<List<MemorySearchResult>> recall(String query, RecallOptions options);

    /**
     * 按作用域简单搜索（无 LLM 增强）。
     */
    List<MemorySearchResult> search(String query, MemoryScope scope, int maxResults);

    // =========================================================
    // 维护
    // =========================================================

    /** 等待所有后台写入完成 */
    void drain();

    /** 清除指定作用域的所有记忆 */
    CompletableFuture<Void> clear(MemoryScope scope);

    /** 获取统计信息 */
    MemoryStats stats();

    // =========================================================
    // 相关类型
    // =========================================================

    record MemoryEntry(String content, Map<String, Object> metadata) {}

    record StoreOptions(
        boolean useLLMEncoding,
        float consolidationThreshold
    ) {
        public StoreOptions() { this(true, 0.85f); }
    }

    record RecallOptions(
        RecallDepth depth,
        List<MemoryScope> scopes,
        int maxResults,
        float semanticWeight,
        float recencyWeight,
        float importanceWeight
    ) {
        public enum RecallDepth { SHALLOW, DEEP }
        public RecallOptions() {
            this(RecallDepth.SHALLOW, List.of(MemoryScope.global()), 5, 0.6f, 0.3f, 0.1f);
        }
    }

    record MemoryStats(long totalRecords, long totalTokens, double avgImportance) {}
}

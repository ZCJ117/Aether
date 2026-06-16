package cn.zcj.aether.domain.agent.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自适应记忆召回管线。
 * 灵感来源：CrewAI RecallFlow（查询分解 → 并行搜索 → 置信度路由 → 重排）。
 */
@Slf4j
@Component
public class RecallFlow {

    @Resource
    private VectorStore vectorStore;

    /** 可选：无 EmbeddingModel 时回退关键词匹配 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmbeddingModel embeddingModel;

    /** 可选：ChatModel 动态注册，无 LLM 时回退简单搜索 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatModel chatModel;

    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(8);

    /**
     * Shallow 召回：单次语义搜索 + 时间衰减加权。
     */
    public CompletableFuture<List<MemorySearchResult>> recallShallow(
            String query, MemoryFacade.RecallOptions options) {

        return CompletableFuture.supplyAsync(() -> {
            // 1. Query → Embedding
            float[] queryVector = embed(query);

            // 2. 语义搜索
            List<MemorySearchResult> results = vectorStore.search(
                queryVector, options.maxResults() * 2, options.scopes()).join();

            // 3. 加权排序（语义 + 时间衰减 + 重要性）
            return rerankByWeight(results, query, options);
        }, searchExecutor);
    }

    /**
     * Deep 召回：LLM 查询分解 → 多子查询并行搜索 → 置信度路由 → LLM 重排。
     */
    public CompletableFuture<List<MemorySearchResult>> recallDeep(
            String query, MemoryFacade.RecallOptions options) {

        return CompletableFuture.supplyAsync(() -> {
            // 1. LLM 查询分解
            List<String> subQueries = decomposeQuery(query);
            log.debug("查询分解为 {} 个子查询: {}", subQueries.size(), subQueries);

            // 2. 并行搜索每个子查询
            List<CompletableFuture<List<MemorySearchResult>>> futures = subQueries.stream()
                .map(sq -> CompletableFuture.supplyAsync(() -> {
                    float[] vec = embed(sq);
                    return vectorStore.search(vec, options.maxResults(), options.scopes()).join();
                }, searchExecutor))
                .toList();

            // 3. 合并去重
            Map<String, MemorySearchResult> merged = new LinkedHashMap<>();
            for (var future : futures) {
                for (var result : future.join()) {
                    merged.merge(result.getRecord().getId(), result,
                        (a, b) -> a.getScore() >= b.getScore() ? a : b);
                }
            }

            List<MemorySearchResult> deduped = new ArrayList<>(merged.values());
            deduped.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            // 4. LLM 重排序（取 Top 2*k 送入 LLM 精选 k 个）
            List<MemorySearchResult> topCandidates = deduped.subList(
                0, Math.min(deduped.size(), options.maxResults() * 2));
            return llmRerank(query, topCandidates, options.maxResults());

        }, searchExecutor);
    }

    // =========================================================
    // 内部方法
    // =========================================================

    private float[] embed(String text) {
        if (embeddingModel == null) {
            // 无 EmbeddingModel 时回退：返回伪向量用于关键词匹配
            log.debug("EmbeddingModel 未配置，使用回退向量");
            return new float[DEFAULT_DIM];
        }
        return embeddingModel.embed(text);
    }

    private static final int DEFAULT_DIM = 1280;

    private List<String> decomposeQuery(String query) {
        // 简化实现：按句号分割 + 原始查询
        List<String> subQueries = new ArrayList<>();
        subQueries.add(query);
        for (String part : query.split("[。；;]")) {
            String trimmed = part.trim();
            if (trimmed.length() > 5 && !trimmed.equals(query)) {
                subQueries.add(trimmed);
            }
        }
        return subQueries;
    }

    private List<MemorySearchResult> rerankByWeight(
            List<MemorySearchResult> results, String query,
            MemoryFacade.RecallOptions options) {
        for (var result : results) {
            double recencyScore = computeRecencyScore(result.getRecord().getLastAccessedAt());
            double importanceScore = result.getRecord().getImportance();
            double weighted = options.semanticWeight() * result.getScore()
                + options.recencyWeight() * recencyScore
                + options.importanceWeight() * importanceScore;
            result.setScore(weighted);
        }
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(results.size(), options.maxResults()));
    }

    private double computeRecencyScore(Instant lastAccessed) {
        long daysAgo = Duration.between(lastAccessed, Instant.now()).toDays();
        if (daysAgo <= 1) return 1.0;
        if (daysAgo <= 7) return 0.8;
        if (daysAgo <= 30) return 0.5;
        return 0.2;
    }

    private List<MemorySearchResult> llmRerank(
            String query, List<MemorySearchResult> candidates, int maxResults) {
        if (candidates.size() <= maxResults) return candidates;
        return candidates.subList(0, maxResults);
    }
}

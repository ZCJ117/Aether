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

        // C3: LLM 语义分解增强（仅在 chatModel 可用且标点分解产出不足时触发）
        if (chatModel != null && subQueries.size() <= 1) {
            try {
                String prompt = "将以下搜索查询拆分为2-3个更具体的子查询，用于记忆搜索。"
                        + "严格返回 JSON 数组格式，如[\"子查询1\", \"子查询2\"]。\n查询: " + query;
                var response = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                        new org.springframework.ai.chat.messages.UserMessage(prompt)));
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isBlank()) {
                    List<String> llmSubs = parseJsonArray(text);
                    if (!llmSubs.isEmpty()) {
                        subQueries.addAll(llmSubs);
                        log.debug("LLM 查询分解: {} → {} 个子查询", query, subQueries.size());
                    }
                }
            } catch (Exception e) {
                log.debug("LLM 查询分解失败，使用标点分解: {}", e.getMessage());
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

    /**
     * C3: LLM 重排序 —— 使用精简 prompt 对候选记忆做相关性排序。
     * 借鉴 CrewAI RecallFlow 的置信度路由设计。
     * chatModel 不可用时回退到截断。
     */
    private List<MemorySearchResult> llmRerank(
            String query, List<MemorySearchResult> candidates, int maxResults) {

        if (candidates.size() <= maxResults) return candidates;

        // 无 LLM 时回退截断
        if (chatModel == null) {
            log.debug("ChatModel 未就绪，llmRerank 回退为截断");
            return candidates.subList(0, maxResults);
        }

        try {
            // 构建精简 prompt（~3000 字符上限）
            StringBuilder sb = new StringBuilder();
            sb.append("用户查询: ").append(query).append("\n\n候选记忆列表:\n");
            for (int i = 0; i < candidates.size(); i++) {
                var c = candidates.get(i);
                String content = c.getRecord().getContent();
                String preview = content != null && content.length() > 150
                        ? content.substring(0, 150) + "..." : content;
                sb.append("[").append(i).append("] ").append(preview).append("\n");
            }
            sb.append("\n请选出与用户查询最相关的前 ").append(maxResults)
              .append(" 条记忆的编号，严格返回JSON数组: [0, 3, 5]。只返回编号数组，不要其他内容。");

            if (sb.length() > 3000) {
                sb.setLength(3000);
                sb.append("\n... (已截断)");
            }

            var response = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                    new org.springframework.ai.chat.messages.UserMessage(sb.toString())));
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) return candidates.subList(0, maxResults);

            List<Integer> rankedIndices = parseRerankIndices(text, candidates.size());
            if (rankedIndices.isEmpty()) return candidates.subList(0, maxResults);

            // 按 LLM 排序组装结果
            List<MemorySearchResult> reranked = new ArrayList<>();
            for (int idx : rankedIndices) {
                if (idx >= 0 && idx < candidates.size() && reranked.size() < maxResults) {
                    reranked.add(candidates.get(idx));
                }
            }
            // 补足未提及的候选
            for (int i = 0; i < candidates.size() && reranked.size() < maxResults; i++) {
                if (!rankedIndices.contains(i)) {
                    reranked.add(candidates.get(i));
                }
            }
            log.debug("llmRerank: {} 候选 → {} 结果", candidates.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.warn("LLM 重排序失败，回退为截断: {}", e.getMessage());
            return candidates.subList(0, maxResults);
        }
    }

    /**
     * 解析 LLM 返回的 JSON 编号数组
     */
    private List<Integer> parseRerankIndices(String text, int maxIndex) {
        try {
            String json = text.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf('\n') + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```")).trim();
                }
            }
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end < 0) return List.of();
            json = json.substring(start, end + 1);

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return List.of(mapper.readValue(json, Integer[].class));
        } catch (Exception e) {
            log.debug("解析 LLM 重排结果失败: text=[{}]", text);
            return List.of();
        }
    }

    private List<String> parseJsonArray(String text) {
        try {
            String json = text.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf('\n') + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```")).trim();
                }
            }
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end < 0) return List.of();
            json = json.substring(start, end + 1);
            return List.of(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, String[].class));
        } catch (Exception e) {
            return List.of();
        }
    }
}

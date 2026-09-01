package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalDocument;
import cn.zcj.aether.domain.agent.service.retrieval.rag.RerankPort;
import cn.zcj.aether.domain.agent.service.support.DaemonThreads;
import cn.zcj.aether.domain.agent.service.tool.python.PythonServicePort;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P1(4.2): 重排实现 —— 经既有 {@link PythonServicePort} 通道调用 document-service
 * {@code /rerank}（架构零改动，roadmap 4.2 第 3 步）。
 *
 * <p>超时（{@code aether.rag.rerank.timeout-ms}）或调用失败 → 抛出交由管道降级为 RRF 序；
 * 服务端返回 null/解析失败 → 保留 RRF 原序截断。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.rag.rerank.enabled", havingValue = "true")
public class PythonReranker implements RerankPort {

    private final PythonServicePort pythonServicePort;
    private final long timeoutMs;
    private final ExecutorService executor = DaemonThreads.singleThreadExecutor("rag-python-reranker");

    @Autowired
    public PythonReranker(PythonServicePort pythonServicePort,
            @Value("${aether.rag.rerank.timeout-ms:800}") long timeoutMs) {
        this.pythonServicePort = pythonServicePort;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public List<RetrievalDocument> rerank(String query, List<RetrievalDocument> candidates, int topN) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates == null ? List.of() : candidates;
        }
        List<String> docs = candidates.stream().map(RetrievalDocument::content).toList();
        try {
            CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(
                    () -> pythonServicePort.rerank(query, docs), executor);
            JsonNode response = future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (response == null || !response.has("results") || !response.get("results").isArray()) {
                return preserveOrder(candidates, topN);
            }
            List<RetrievalDocument> reranked = new ArrayList<>();
            for (JsonNode item : response.get("results")) {
                int index = item.has("index") ? item.get("index").asInt(-1) : -1;
                double score = item.has("score") ? item.get("score").asDouble(0) : 0;
                if (index >= 0 && index < candidates.size()) {
                    RetrievalDocument original = candidates.get(index);
                    reranked.add(new RetrievalDocument(
                            original.id(), original.content(), score, "rerank"));
                }
            }
            if (reranked.isEmpty()) {
                return preserveOrder(candidates, topN);
            }
            // 未被服务端返回的候选按原序补足（不丢候选）
            for (RetrievalDocument c : candidates) {
                if (reranked.size() >= topN) {
                    break;
                }
                if (reranked.stream().noneMatch(r -> r.id().equals(c.id()))) {
                    reranked.add(c);
                }
            }
            return reranked.subList(0, Math.min(reranked.size(), topN));
        } catch (Exception e) {
            throw new IllegalStateException("Python 重排超时/失败: " + e.getMessage(), e);
        }
    }

    private static List<RetrievalDocument> preserveOrder(List<RetrievalDocument> candidates, int topN) {
        return new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), topN)));
    }
}

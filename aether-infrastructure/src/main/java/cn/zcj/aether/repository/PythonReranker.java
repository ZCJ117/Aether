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
 *
 * <p><b>D4/F3-1 装配条件与运行开关解耦</b>：原条件挂在 {@code aether.rag.rerank.enabled} 上，
 * 使"是否装配 Bean"（容器启动期）与"是否调用重排"（运行期开关）耦合——开关默认 false，
 * 于是容器内根本不存在 {@code RerankPort}，{@code RetrievalPipeline.rerankPort} 恒为 null，
 * "三级检索"在容器层面只有两级；即便把开关改 true 也要等下次启动才生效。</p>
 *
 * <p>现改为只要求"Python 服务地址可用"，与二级端口 {@code PgHybridSearchRepository}
 * 挂在 {@code aether.memory.pgvector.enabled} 的既有正确形态对齐。是否<b>调用</b>仍由
 * {@code aether.rag.rerank.enabled} 在运行期控制（见 {@code RetrievalPipeline} 三级分支）——
 * 装配 ≠ 调用。</p>
 *
 * <p><b>为什么键名是 {@code aether.python.doc-url} 而不是 {@code aether.python.base-url}</b>：
 * 实际绑定方是 {@code PythonServiceClient}（{@code aether.python.doc-url} /
 * {@code sandbox-url} / {@code fs-url}，各带 localhost 默认值），库内根本不存在
 * {@code aether.python.base-url}。若照抄该键名，条件将恒为 false，Bean 永不装配——
 * 比改造前更糟。此处用 {@code doc-url}（重排实际走的就是 doc-url 的 {@code /rerank}）
 * 并配 {@code matchIfMissing = true}：该键缺省时 {@code @Value} 会落到默认地址
 * localhost:8001，故"地址缺省"等价于"地址可用"，Bean 照常装配。置为
 * {@code aether.python.doc-url=false} 可显式关闭该端口的装配。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.python.doc-url", matchIfMissing = true)
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

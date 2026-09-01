package cn.zcj.aether.domain.agent.service.retrieval.rag;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * P1(4.2): RAG 三级检索管道 —— 改写 → 混合召回（RRF）→ 重排，逐级降级。
 *
 * <p><b>数据流</b>：query ─(LLM 改写)→ 检索友好 query(+变体)
 * ─(pgvector 语义 + PG 全文词法 → RRF 单 SQL 融合)→ Top-50 候选
 * ─(Python 重排)→ Top-N。</p>
 *
 * <p><b>切换条件</b>（每一级独立开关 + 降级）：</p>
 * <ul>
 *   <li>一级：rewrite 未启用 / 无 ChatModel / 超时(rewrite.timeout-ms) / 异常 → 原 query；</li>
 *   <li>二级：hybrid 未启用 / 端口缺失 / 异常 → 纯向量召回（VectorStore.search）；
 *       无有效向量 → 纯词法；两者皆缺 → 返回空（调用方回退原检索路径）；</li>
 *   <li>三级：rerank 未启用 / 服务不可达 / 超时(rerank.timeout-ms) → 保留 RRF 序截断。</li>
 * </ul>
 *
 * <p>指标：{@code aether.rag.stage.duration}{stage}（Timer，含 rewrite/hybrid/rerank）
 * + {@code aether.rag.degrade.total}{stage}（降级计数）。</p>
 */
@Slf4j
@Component
public class RetrievalPipeline {

    private final QueryRewriter queryRewriter;
    private final RagProperties properties;

    /** 可选端口（包级私有便于单测注入 fake；Spring 经字段注入装配）。 */
    @Autowired(required = false)
    HybridSearchPort hybridSearchPort;

    @Autowired(required = false)
    RerankPort rerankPort;

    /** 纯向量兜底（hybrid 降级时使用；memory 模块的 pgvector/文件实现） */
    @Resource
    VectorStore vectorStore;

    /** 向量生成（与 aether_memories 同一 embedding 模型；缺失时向量路降级） */
    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("memoryEmbeddingModel")
    private EmbeddingModel embeddingModel;

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();

    public RetrievalPipeline(QueryRewriter queryRewriter,
                             RagProperties properties,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.queryRewriter = queryRewriter;
        this.properties = properties;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * 执行三级管道。
     *
     * @param query  用户原始查询
     * @param scopes 记忆作用域过滤
     * @param topK   最终返回条数
     */
    public RetrievalResult retrieve(String query, List<MemoryScope> scopes, int topK) {
        long start = System.nanoTime();

        // ── 一级：查询改写（失败/关闭 → 原 query）──
        String effectiveQuery = query;
        List<String> variants = List.of();
        if (properties.getRewrite().isEnabled()) {
            Timer.Sample sample = startStage("rewrite");
            try {
                QueryRewriter.RewriteResult r = queryRewriter.rewrite(
                        query, properties.getRewrite().getTimeoutMs(),
                        properties.getRewrite().getVariants());
                effectiveQuery = r.rewrittenQuery();
                variants = r.variants();
                if (!r.rewritten()) {
                    recordDegrade("rewrite");
                }
            } catch (Exception e) {
                recordDegrade("rewrite");
                log.debug("一级改写降级: {}", e.getMessage());
            } finally {
                endStage("rewrite", sample);
            }
        }

        // ── 二级：混合召回（RRF；降级 → 纯向量；再降级 → 空交调用方回退）──
        final List<RetrievalDocument> docs = new ArrayList<>();
        if (properties.getHybrid().isEnabled() && hybridSearchPort != null) {
            Timer.Sample sample = startStage("hybrid");
            try {
                float[] vector = embedOrNull(effectiveQuery);
                String lexicalQuery = CjkBigram.bigram(effectiveQuery + " " + String.join(" ", variants));
                docs.addAll(hybridSearchPort.search(vector, lexicalQuery,
                        Math.max(topK, properties.getHybrid().getCandidateLimit()), scopes));
            } catch (Exception e) {
                recordDegrade("hybrid");
                log.debug("二级混合召回降级为纯向量: {}", e.getMessage());
            } finally {
                endStage("hybrid", sample);
            }
        } else if (properties.getHybrid().isEnabled()) {
            recordDegrade("hybrid");
        }

        if (docs.isEmpty() && vectorStore != null) {
            // 纯向量兜底（hybrid 关闭或失败时）
            try {
                float[] vector = embedOrNull(effectiveQuery);
                vectorStore.search(vector, topK * 2, scopes).join().forEach(r ->
                        docs.add(new RetrievalDocument(r.getRecord().getId(),
                                r.getRecord().getContent(), r.getScore(), "pure-vector")));
            } catch (Exception e) {
                recordDegrade("hybrid");
                log.debug("纯向量兜底失败: {}", e.getMessage());
            }
        }

        // ── 三级：重排（降级 → RRF 序截断）──
        String finalSource = docs.stream().findFirst().map(RetrievalDocument::source).orElse("none");
        if (properties.getRerank().isEnabled() && rerankPort != null && docs.size() > 1) {
            Timer.Sample sample = startStage("rerank");
            try {
                List<RetrievalDocument> reranked = rerankPort.rerank(
                        effectiveQuery, docs, properties.getRerank().getTopN());
                docs.clear();
                docs.addAll(reranked);
                finalSource = "rerank";
            } catch (Exception e) {
                recordDegrade("rerank");
                log.debug("三级重排降级为 RRF 序: {}", e.getMessage());
            } finally {
                endStage("rerank", sample);
            }
        } else if (properties.getRerank().isEnabled()) {
            recordDegrade("rerank");
        }

        List<RetrievalDocument> top = docs.stream()
                .limit(properties.getRerank().isEnabled() && "rerank".equals(finalSource)
                        ? Math.min(topK, properties.getRerank().getTopN()) : topK)
                .toList();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new RetrievalResult(top, new StageTrace(effectiveQuery, variants, elapsedMs, finalSource));
    }

    /** @return 有效向量；无 EmbeddingModel / 全零失败返回 null（纯词法路）。 */
    private float[] embedOrNull(String text) {
        if (embeddingModel == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.debug("RAG 向量生成失败（纯词法路）: {}", e.getMessage());
            return null;
        }
    }

    private Timer.Sample startStage(String stage) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        return registry == null ? null : Timer.start(registry);
    }

    private void endStage(String stage, Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            sample.stop(stageTimers.computeIfAbsent(stage, s ->
                    Timer.builder("aether.rag.stage.duration")
                            .tag("stage", s)
                            .description("RAG pipeline per-stage latency")
                            .publishPercentileHistogram(false)
                            .register(registry)));
        }
    }

    private void recordDegrade(String stage) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("aether.rag.degrade.total", "stage", stage).increment();
        }
    }

    /** 管道结果 + 逐级轨迹（观测/评测用）。 */
    public record RetrievalResult(List<RetrievalDocument> documents, StageTrace trace) {
    }

    public record StageTrace(String effectiveQuery, List<String> variants, long elapsedMs,
                             String finalSource) {
    }
}

package cn.zcj.aether.domain.agent.service.retrieval.rag;

import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1(4.2): 管道编排测试 —— 三级全开 / 逐级降级切换条件 / 指标。
 */
class RetrievalPipelineTest {

    private static final List<MemoryScope> SCOPES = List.of(new MemoryScope("global", false));

    private static RetrievalDocument doc(String id, double score) {
        return new RetrievalDocument(id, "content-of-" + id, score, "rrf-hybrid");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static RagProperties props(boolean pipeline, boolean rewrite, boolean hybrid, boolean rerank) {
        RagProperties p = new RagProperties();
        p.setEnabled(pipeline);
        p.getRewrite().setEnabled(rewrite);
        p.getHybrid().setEnabled(hybrid);
        p.getRerank().setEnabled(rerank);
        return p;
    }

    @Test
    void allStagesActiveYieldsRerankedTopN() {
        HybridSearchPort hybrid = (vec, lex, k, s) -> List.of(doc("a", 0.9), doc("b", 0.7), doc("c", 0.5));
        RerankPort rerank = (q, cands, topN) -> List.of(
                new RetrievalDocument("c", "content-of-c", 0.99, "rerank"),
                new RetrievalDocument("a", "content-of-a", 0.95, "rerank"));
        QueryRewriter rewriter = new QueryRewriter(null);

        RetrievalPipeline pipeline = new RetrievalPipeline(
                rewriter, props(true, true, true, true), provider(null));
        pipeline.hybridSearchPort = hybrid;
        pipeline.rerankPort = rerank;

        var result = pipeline.retrieve("查询", SCOPES, 2);
        assertEquals("rerank", result.trace().finalSource());
        assertEquals(2, result.documents().size());
        assertEquals("c", result.documents().get(0).id(), "重排应把 c 提到首位");
    }

    @Test
    void hybridFailureFallsBackToPureVector() {
        HybridSearchPort failing = (vec, lex, k, s) -> { throw new IllegalStateException("pg down"); };
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.search(org.mockito.ArgumentMatchers.nullable(float[].class), anyInt(), any())).thenReturn(
                CompletableFuture.completedFuture(List.of(
                        MemorySearchResult.of(record("v1"), 0.8),
                        MemorySearchResult.of(record("v2"), 0.6))));
        QueryRewriter rewriter = new QueryRewriter(null);

        RetrievalPipeline pipeline = new RetrievalPipeline(
                rewriter, props(true, false, true, false), provider(null));
        pipeline.hybridSearchPort = failing;
        pipeline.vectorStore = vectorStore;

        var result = pipeline.retrieve("查询", SCOPES, 2);
        assertEquals("pure-vector", result.trace().finalSource(), "hybrid 失败应切纯向量");
        assertEquals(2, result.documents().size());
    }

    @Test
    void hybridDisabledUsesPureVectorDirectly() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.search(org.mockito.ArgumentMatchers.nullable(float[].class), anyInt(), any())).thenReturn(
                CompletableFuture.completedFuture(List.of(MemorySearchResult.of(record("v1"), 0.8))));
        QueryRewriter rewriter = new QueryRewriter(null);

        RetrievalPipeline pipeline = new RetrievalPipeline(
                rewriter, props(true, false, false, false), provider(null));
        pipeline.vectorStore = vectorStore;

        var result = pipeline.retrieve("查询", SCOPES, 5);
        assertEquals("pure-vector", result.trace().finalSource());
        assertEquals(1, result.documents().size());
    }

    @Test
    void rerankFailureKeepsRrfOrder() {
        HybridSearchPort hybrid = (vec, lex, k, s) -> List.of(doc("a", 0.9), doc("b", 0.7));
        RerankPort failing = (q, cands, topN) -> { throw new IllegalStateException("python down"); };
        QueryRewriter rewriter = new QueryRewriter(null);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalPipeline pipeline = new RetrievalPipeline(
                rewriter, props(true, false, true, true), provider(registry));
        pipeline.hybridSearchPort = hybrid;
        pipeline.rerankPort = failing;

        var result = pipeline.retrieve("查询", SCOPES, 2);
        assertEquals("rrf-hybrid", result.trace().finalSource(), "重排失败保留 RRF 序");
        assertEquals("a", result.documents().get(0).id());
        assertEquals(1.0, registry.get("aether.rag.degrade.total")
                .tag("stage", "rerank").counter().count());
        assertTrue(registry.get("aether.rag.stage.duration").tag("stage", "hybrid")
                .timer().count() >= 1);
    }

    @Test
    void noPortsAtAllReturnsEmptyForCallerFallback() {
        QueryRewriter rewriter = new QueryRewriter(null);
        RetrievalPipeline pipeline = new RetrievalPipeline(
                rewriter, props(true, false, true, false), provider(null));
        pipeline.hybridSearchPort = null;
        pipeline.vectorStore = null;

        var result = pipeline.retrieve("查询", SCOPES, 5);
        assertTrue(result.documents().isEmpty(), "无可用端口 → 空，调用方回退原检索路径");
    }

    private static cn.zcj.aether.domain.agent.service.memory.MemoryRecord record(String id) {
        return cn.zcj.aether.domain.agent.service.memory.MemoryRecord.builder()
                .id(id).content("content-of-" + id)
                .scope(new MemoryScope("global", false))
                .importance(0.5f)
                .createdAt(java.time.Instant.now())
                .lastAccessedAt(java.time.Instant.now())
                .build();
    }
}

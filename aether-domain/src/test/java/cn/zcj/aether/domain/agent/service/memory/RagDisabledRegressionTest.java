package cn.zcj.aether.domain.agent.service.memory;

import cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D4/F3-2 边界 + G-D4-6: 默认配置下（{@code aether.rag.enabled=false}）行为与改造前完全一致 —— T3-7。
 *
 * <p>本次改造只在 {@code application-dev.yml} 覆盖开启，全局默认仍为 false。
 * 本用例锁住"默认关闭时 {@code recallShallow} 完全不触碰 {@link RetrievalPipeline}"，
 * 防止后续误把默认值翻转成 true（那会让记忆召回强依赖
 * {@code HybridSearchPort} + {@code EmbeddingModel}，在未部署 pgvector 的环境改变行为）。</p>
 */
class RagDisabledRegressionTest {

    /** 反射注入 RecallFlow 的私有字段（与既有 ReActAgent 端到端测试同一手法）。 */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("T3-7 ragEnabled=false 时 recallShallow 不触发 RetrievalPipeline，走原检索路径")
    void disabledPipelineIsNeverTouched() throws Exception {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        VectorStore vectorStore = mock(VectorStore.class);
        // 注意：rerankByWeight 会原地 sort，故替身必须返回可变列表（真实 VectorStore 亦然）
        MemorySearchResult hit = MemorySearchResult.of(
                MemoryRecord.builder()
                        .id("m1").content("原始路径命中")
                        .scope(MemoryScope.global())
                        .importance(0.5f)
                        .createdAt(java.time.Instant.now())
                        .lastAccessedAt(java.time.Instant.now())
                        .build(), 0.8);
        when(vectorStore.search(any(), anyInt(), any()))
                .thenReturn(CompletableFuture.completedFuture(new java.util.ArrayList<>(List.of(hit))));

        RecallFlow flow = new RecallFlow();
        setField(flow, "retrievalPipeline", pipeline);
        setField(flow, "ragEnabled", false);
        setField(flow, "vectorStore", vectorStore);

        List<MemorySearchResult> results = flow
                .recallShallow("查询", new MemoryFacade.RecallOptions())
                .join();

        assertNotNull(results, "关闭管道时应正常返回原检索路径结果");
        org.junit.jupiter.api.Assertions.assertEquals(1, results.size(),
                "默认关闭时必须走原路径并拿到向量检索结果");
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("T3-7 对照：ragEnabled=true 且管道可用时才接管 shallow 召回")
    void enabledPipelineTakesOverShallowRecall() throws Exception {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        when(pipeline.retrieve(any(), any(), anyInt())).thenReturn(
                new RetrievalPipeline.RetrievalResult(
                        List.of(new cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalDocument(
                                "d1", "内容", 0.9, "rrf-hybrid")),
                        new RetrievalPipeline.StageTrace("查询", List.of(), 1L, "hybrid")));

        RecallFlow flow = new RecallFlow();
        setField(flow, "retrievalPipeline", pipeline);
        setField(flow, "ragEnabled", true);
        setField(flow, "vectorStore", mock(VectorStore.class));

        List<MemorySearchResult> results = flow
                .recallShallow("查询", new MemoryFacade.RecallOptions())
                .join();

        assertNotNull(results);
        org.junit.jupiter.api.Assertions.assertEquals(1, results.size(),
                "管道有结果时应直接返回管道结果，不再走原路径");
        org.mockito.Mockito.verify(pipeline).retrieve(any(), any(), anyInt());
    }
}

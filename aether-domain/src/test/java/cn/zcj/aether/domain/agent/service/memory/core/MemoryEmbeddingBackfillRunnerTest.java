package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryEmbeddingBackfillRunnerTest {

    /** 内存版 VectorStore 桩：逐个吐出 missing，记录 updateEmbedding */
    static class StubVectorStore implements VectorStore {
        final Deque<MemoryRecord> missing;
        final List<String> updatedIds = new ArrayList<>();

        StubVectorStore(List<MemoryRecord> missing) { this.missing = new ArrayDeque<>(missing); }

        @Override public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
            List<MemoryRecord> batch = new ArrayList<>();
            for (int i = 0; i < limit && !missing.isEmpty(); i++) batch.add(missing.poll());
            return CompletableFuture.completedFuture(batch);
        }
        @Override public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
            updatedIds.add(id);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<List<MemorySearchResult>> search(
                float[] q, int topK, List<MemoryScope> scopes) { return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletableFuture<Void> upsert(String id, float[] v, MemoryRecord r) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> upsertBatch(List<MemoryRecord> rs) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> delete(String id) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> deleteByScope(MemoryScope s) { return CompletableFuture.completedFuture(null); }
        @Override public int dimension() { return 1024; }
    }

    private MemoryRecord rec(String id) {
        return MemoryRecord.builder().id(id).content("内容-" + id).scope(MemoryScope.global()).build();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<EmbeddingModel> providerStub(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @Test
    void backfillsAllRecords() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setBatchSize(2);
        props.getBackfill().setMaxPerRun(5);
        StubVectorStore store = new StubVectorStore(List.of(rec("a"), rec("b"), rec("c")));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertEquals(List.of("a", "b", "c"), store.updatedIds);
    }

    @Test
    void stopsAtMaxPerRun() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setBatchSize(50);
        props.getBackfill().setMaxPerRun(2);
        StubVectorStore store = new StubVectorStore(List.of(rec("r0"), rec("r1"), rec("r2")));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertEquals(List.of("r0", "r1"), store.updatedIds);
    }

    @Test
    void skipsFailedEmbeddingAndContinues() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setMaxPerRun(10);
        StubVectorStore store = new StubVectorStore(List.of(rec("x"), rec("y")));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new RuntimeException("boom"));

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertTrue(store.updatedIds.isEmpty());
    }

    @Test
    @Timeout(5)
    void stopsWhenEntireBatchFailsToAvoidSpin() {
        MemoryProperties props = new MemoryProperties();
        props.getBackfill().setBatchSize(2);
        props.getBackfill().setMaxPerRun(100);
        // 模拟真实存储：findMissingEmbeddings 始终返回同一批未成功记录（永不清空）
        StubVectorStore store = new StubVectorStore(List.of(rec("x"), rec("y"))) {
            @Override
            public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
                return CompletableFuture.completedFuture(List.of(rec("x"), rec("y")));
            }
        };
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new RuntimeException("boom"));

        new MemoryEmbeddingBackfillRunner(store, providerStub(model), props).runBackfill(model);

        assertTrue(store.updatedIds.isEmpty());
    }
}

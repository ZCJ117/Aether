package cn.zcj.aether.domain.agent.service.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMemoryFacadeMergeTest {

    private static final String ENCODE_JSON =
        "{\"categories\":[\"测试\"],\"importance\":0.8,\"shouldConsolidate\":true,\"consolidationHint\":\"x\"}";

    /** 捕获 search 查询向量 + 记录 upsert 的 VectorStore 桩 */
    static class CapturingVectorStore implements VectorStore {
        float[] lastQueryVector;
        final MemorySearchResult seeded;
        final List<MemoryRecord> upserted = new ArrayList<>();

        CapturingVectorStore(MemorySearchResult seeded) { this.seeded = seeded; }

        @Override
        public CompletableFuture<List<MemorySearchResult>> search(
                float[] queryVector, int topK, List<MemoryScope> scopes) {
            this.lastQueryVector = queryVector;
            return CompletableFuture.completedFuture(seeded == null ? List.of() : List.of(seeded));
        }

        @Override
        public CompletableFuture<Void> upsert(String id, float[] vector, MemoryRecord record) {
            upserted.add(record);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> upsertBatch(List<MemoryRecord> records) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> delete(String id) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> deleteByScope(MemoryScope scope) { return CompletableFuture.completedFuture(null); }
        @Override public int dimension() { return 1024; }
        @Override public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void mergeDetectionUsesRealQueryVectorAndReembeds() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
            ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(ENCODE_JSON)))).build());

        EncodingFlow encodingFlow = new EncodingFlow(chatModel);
        MemoryRecord old = MemoryRecord.builder()
            .id("old-1").content("旧内容").importance(0.4f).scope(MemoryScope.global()).build();
        CapturingVectorStore store = new CapturingVectorStore(MemorySearchResult.of(old, 0.9f));
        DefaultMemoryFacade facade = new DefaultMemoryFacade(encodingFlow, new RecallFlow(), store);

        // 向量由输入字符串派生，用于区分“查询向量”与“合并后内容向量”
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return new float[]{s.length(), s.charAt(0), 0f};
        });
        ReflectionTestUtils.setField(facade, "embeddingModel", embeddingModel);

        MemoryRecord merged = facade.remember("新内容", MemoryScope.global(),
            new MemoryFacade.StoreOptions(true, 0.85f)).join();

        // 修复点：合并检测收到真实查询向量（非 null），且来自原始内容 "新内容"
        assertNotNull(store.lastQueryVector);
        assertEquals("新内容".length(), store.lastQueryVector[0], 0.001f);

        // 合并结果 upsert 一次，内容拼接、重要性加权平均、向量重嵌
        assertEquals(1, store.upserted.size());
        MemoryRecord saved = store.upserted.get(0);
        assertEquals("old-1", saved.getId());
        assertTrue(saved.getContent().contains("旧内容"));
        assertTrue(saved.getContent().contains("新内容"));
        assertEquals(0.6f, saved.getImportance(), 0.001f); // (0.4+0.8)/2
        assertNotNull(saved.getEmbedding());
        // 合并后记录向量来自合并内容（旧内容 + \n\n + 新内容）——证明合并后确实重嵌
        String mergedContent = "旧内容\n\n新内容";
        assertEquals(mergedContent.length(), saved.getEmbedding()[0], 0.001f);
        assertNotEquals(store.lastQueryVector[0], saved.getEmbedding()[0]);
    }
}

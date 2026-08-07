package cn.zcj.aether.domain.agent.service.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreSearchTest {

    private Path tempDir;
    private MemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("mem-search-test");
        store = new MemoryStore();
        ReflectionTestUtils.setField(store, "configuredMemoryDir", tempDir.toString());
    }

    private MemoryRecord record(String id, String content) {
        return MemoryRecord.builder()
            .id(id)
            .content(content)
            .scope(MemoryScope.global())
            .importance(0.5f)
            .source("test")
            .build();
    }

    @Test
    void searchRanksByCosineSimilarity() {
        store.upsert("match", new float[]{0.99f, 0.1f, 0f}, record("match", "最相关内容")).join();
        store.upsert("orthogonal", new float[]{0f, 1f, 0f}, record("orthogonal", "无关内容")).join();

        List<MemorySearchResult> results = store.search(new float[]{1f, 0f, 0f}, 2, List.of(MemoryScope.global())).join();

        assertEquals(2, results.size());
        assertEquals("match", results.get(0).getRecord().getId());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void searchSkipsNullEmbedding() {
        store.upsert("no-vec", null, record("no-vec", "无向量")).join();
        store.upsert("has-vec", new float[]{1f, 0f, 0f}, record("has-vec", "有向量")).join();

        List<MemorySearchResult> results = store.search(new float[]{1f, 0f, 0f}, 10, List.of(MemoryScope.global())).join();

        assertEquals(1, results.size());
        assertEquals("has-vec", results.get(0).getRecord().getId());
    }

    @Test
    void backfillFindsAndUpdatesMissingEmbeddings() {
        store.upsert("missing", null, record("missing", "无向量")).join();
        store.upsert("present", new float[]{1f, 0f, 0f}, record("present", "有向量")).join();

        List<MemoryRecord> missing = store.findMissingEmbeddings(10).join();
        assertEquals(1, missing.size());
        assertEquals("missing", missing.get(0).getId());

        store.updateEmbedding("missing", new float[]{1f, 0f, 0f}).join();
        List<MemoryRecord> after = store.findMissingEmbeddings(10).join();
        assertTrue(after.isEmpty());
    }
}

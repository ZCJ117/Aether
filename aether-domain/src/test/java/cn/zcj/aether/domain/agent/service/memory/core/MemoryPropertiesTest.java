package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPropertiesTest {

    @Test
    void defaultsMatchHermes() {
        MemoryProperties p = new MemoryProperties();
        assertTrue(p.isEnabled());
        assertTrue(p.isUserProfileEnabled());
        assertEquals(2200, p.getMemoryCharLimit());
        assertEquals(1375, p.getUserCharLimit());
        assertEquals(10, p.getNudgeInterval());
        assertEquals(6, p.getFlushMinTurns());
        assertEquals(10, p.getRecall().getMaxResults());
        assertEquals(0.6f, p.getRecall().getSemanticWeight());
        assertEquals(0.3f, p.getRecall().getRecencyWeight());
        assertEquals(0.1f, p.getRecall().getImportanceWeight());
        assertEquals(0.85f, p.getRecall().getConsolidationThreshold());
        assertEquals(1024, p.getRecall().getVectorDimension());          // 1280 → 1024
        // 新增：embedding 段
        assertEquals("", p.getEmbedding().getBaseUrl());
        assertEquals("v1/embeddings", p.getEmbedding().getPath());
        assertEquals(1024, p.getEmbedding().getDimension());
        // 新增：backfill 段
        assertTrue(p.getBackfill().isEnabled());
        assertEquals(50, p.getBackfill().getBatchSize());
        assertEquals(500, p.getBackfill().getMaxPerRun());
    }

    @Test
    void settersBind() {
        MemoryProperties p = new MemoryProperties();
        p.setEnabled(false);
        p.setNudgeInterval(0);
        p.setMemoryCharLimit(500);
        p.getRecall().setMaxResults(5);
        p.getRecall().setConsolidationThreshold(0.9f);
        assertFalse(p.isEnabled());
        assertEquals(0, p.getNudgeInterval());
        assertEquals(500, p.getMemoryCharLimit());
        assertEquals(5, p.getRecall().getMaxResults());
        assertEquals(0.9f, p.getRecall().getConsolidationThreshold());
        // 新增：embedding/backfill setter
        p.getEmbedding().setBaseUrl("http://localhost:11434");
        p.getBackfill().setEnabled(false);
        p.getBackfill().setBatchSize(10);
        assertEquals("http://localhost:11434", p.getEmbedding().getBaseUrl());
        assertFalse(p.getBackfill().isEnabled());
        assertEquals(10, p.getBackfill().getBatchSize());
    }
}

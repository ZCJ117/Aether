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
        assertEquals(1280, p.getRecall().getVectorDimension());
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
    }
}

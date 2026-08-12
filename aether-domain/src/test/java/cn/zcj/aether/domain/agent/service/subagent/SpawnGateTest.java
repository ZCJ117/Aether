package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnGateTest {

    @Test
    void enterAndExitTrackDepth() {
        SpawnGate gate = new SpawnGate(3);
        assertTrue(gate.enter());
        assertTrue(gate.enter());
        assertEquals(2, gate.currentDepth());
        gate.exit();
        assertEquals(1, gate.currentDepth());
    }

    @Test
    void pausedBlocksNewSpawns() {
        SpawnGate gate = new SpawnGate(3);
        gate.setSpawnPaused(true);
        assertTrue(gate.isSpawnPaused());
        assertFalse(gate.enter(), "暂停时应拒绝新委派");
    }

    @Test
    void maxDepthBlocksEnter() {
        SpawnGate gate = new SpawnGate(2);
        assertTrue(gate.enter());
        assertTrue(gate.enter());
        assertFalse(gate.enter(), "达到深度上限应拒绝");
        assertEquals(2, gate.currentDepth());
    }

    @Test
    void exitDoesNotGoBelowZero() {
        SpawnGate gate = new SpawnGate(3);
        gate.exit();
        assertEquals(0, gate.currentDepth());
    }
}

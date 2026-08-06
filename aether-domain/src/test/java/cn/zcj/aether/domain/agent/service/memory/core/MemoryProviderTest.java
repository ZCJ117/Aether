package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MemoryProviderTest {

    /** 仅实现必需抽象方法的最小桩 */
    static class StubProvider implements MemoryProvider {
        public String name() { return "stub"; }
        public boolean isAvailable() { return true; }
        public void initialize(String sessionId, MemoryInitContext ctx) {}
        public List<Map<String, Object>> getToolSchemas() { return List.of(); }
    }

    @Test
    void defaultHooksAreNoOp() {
        MemoryProvider p = new StubProvider();
        assertEquals("", p.systemPromptBlock());
        assertEquals("", p.prefetch("query", "s1"));
        assertDoesNotThrow(() -> p.queuePrefetch("query", "s1"));
        assertDoesNotThrow(() -> p.syncTurn("u", "a", "s1", null));
        assertDoesNotThrow(() -> p.shutdown());
        assertDoesNotThrow(() -> p.onTurnStart(1, "m", null));
        assertDoesNotThrow(() -> p.onSessionEnd(null));
        assertDoesNotThrow(() -> p.onSessionSwitch("new", "old", false, false, Map.of()));
        assertEquals("", p.onPreCompress(null));
        assertDoesNotThrow(() -> p.onMemoryWrite("add", "memory", "c", null));
    }

    @Test
    void handleToolCallThrowsByDefault() {
        MemoryProvider p = new StubProvider();
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class, () -> p.handleToolCall("x", Map.of()));
        assertTrue(ex.getMessage().contains("stub"));
    }
}

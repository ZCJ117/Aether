package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    /** 记录调用的桩 provider */
    static class RecordingProvider extends MemoryProviderTest.StubProvider {
        final List<String> calls = new ArrayList<>();
        private final String providerName;

        RecordingProvider(String name) {
            this.providerName = name;
        }
        @Override public String name() { return providerName; }
        @Override public String systemPromptBlock() { return "[block:" + providerName + "]"; }
        @Override public String prefetch(String query, String sessionId) {
            calls.add("prefetch:" + query);
            return "[recall:" + query + "]";
        }
        @Override public void syncTurn(String u, String a, String s, List<Map<String, Object>> m) {
            calls.add("sync:" + u);
        }
        @Override public void onSessionEnd(List<Map<String, Object>> messages) {
            calls.add("sessionEnd");
        }
    }

    private MemoryProperties props;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
    }

    @Test
    void builtinAlwaysAcceptedAndSecondExternalRejected() {
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(new RecordingProvider("builtin"));
        manager.addProvider(new RecordingProvider("external1"));
        manager.addProvider(new RecordingProvider("external2")); // 应被拒

        String ctx = manager.prefetchAll("q", "s");
        assertTrue(ctx.contains("[recall:q]"));
        String sp = manager.buildSystemPrompt();
        assertTrue(sp.contains("builtin"));
        assertTrue(sp.contains("external1"));
        assertFalse(sp.contains("external2"));
    }

    @Test
    void nudgeAppearsAtInterval() {
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(new RecordingProvider("builtin"));
        for (int i = 0; i < 10; i++) {
            manager.syncAll("u" + i, "a", "s", null);
        }
        manager.drain();
        String sp = manager.buildSystemPrompt();
        assertTrue(sp.contains("Memory nudge"));
        // 未到间隔不出现
        MemoryManager fresh = new MemoryManager(props);
        fresh.addProvider(new RecordingProvider("builtin"));
        fresh.syncAll("u", "a", "s", null);
        fresh.drain();
        assertFalse(fresh.buildSystemPrompt().contains("Memory nudge"));
    }

    @Test
    void syncAllDelegatesAsyncAndDrainWaits() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.syncAll("你好", "你好呀", "s", null);
        manager.drain();
        assertTrue(p.calls.contains("sync:你好"));
        assertEquals(1, manager.getUserTurnCount());
    }

    @Test
    void prefetchAllDelegatesAndBuildsPrompt() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        String ctx = manager.prefetchAll("q", "s");
        assertEquals("[recall:q]", ctx);
        assertTrue(p.calls.contains("prefetch:q"));
    }

    @Test
    void onSessionEndDelegates() {
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.onSessionEnd(null);
        assertTrue(p.calls.contains("sessionEnd"));
    }

    @Test
    void disabledManagerDoesNothing() {
        props.setEnabled(false);
        RecordingProvider p = new RecordingProvider("builtin");
        MemoryManager manager = new MemoryManager(props);
        manager.addProvider(p);
        manager.syncAll("u", "a", "s", null);
        manager.drain();
        assertFalse(p.calls.contains("sync:u"));
        assertEquals("", manager.prefetchAll("q", "s"));
    }
}

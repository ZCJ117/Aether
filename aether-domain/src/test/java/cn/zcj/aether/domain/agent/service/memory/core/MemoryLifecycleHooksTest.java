package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLifecycleHooksTest {

    private MemoryProperties props;
    private FakeMemoryFacade facade;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        facade = new FakeMemoryFacade();
    }

    @Test
    void initBuildsManagerAndPrefetchWrites() {
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, facade);
        hooks.init();

        assertTrue(hooks.isEnabled());
        hooks.syncTurn("用户问", "助手回答关于数据库连接池的内容", "s1", null);
        hooks.drain();
        assertEquals(1, facade.records.size());

        String block = hooks.prefetch("数据库连接池", "s1");
        assertTrue(block.startsWith("<memory-context>"));
    }

    @Test
    void disabledDoesNothing() {
        props.setEnabled(false);
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, facade);
        hooks.init();

        assertFalse(hooks.isEnabled());
        hooks.syncTurn("u", "a", "s1", null);
        hooks.drain();
        assertEquals(0, facade.records.size());
        assertEquals("", hooks.prefetch("q", "s1"));
    }

    @Test
    void nullFacadeDoesNotBreak() {
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, null);
        hooks.init();
        hooks.syncTurn("u", "a", "s1", null);
        hooks.drain();
        assertEquals("", hooks.prefetch("q", "s1"));
        assertDoesNotThrow(hooks::shutdown);
    }

    @Test
    void onSessionEndRespectsFlushMinTurns() {
        MemoryLifecycleHooks hooks = new MemoryLifecycleHooks(props, facade);
        hooks.init();
        props.setFlushMinTurns(6);

        // 轮次不足 → 不 flush（无写入动作发生，仅验证不抛错）
        hooks.onSessionEnd("s1", 3);
        // 轮次足够 → 正常触发
        assertDoesNotThrow(() -> hooks.onSessionEnd("s1", 6));
    }
}

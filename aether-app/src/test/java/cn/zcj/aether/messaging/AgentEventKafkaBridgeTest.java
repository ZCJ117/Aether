package cn.zcj.aether.messaging;

import cn.zcj.aether.domain.agent.service.event.AgentEvent;
import cn.zcj.aether.types.messaging.AgentEventMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1(1.3): 事件桥映射测试 —— 三类事件投影字段正确、无关事件忽略。
 */
class AgentEventKafkaBridgeTest {

    @Test
    void turnCompletedMaps() {
        AgentEvent.TurnCompleted e = new AgentEvent.TurnCompleted(
                "id-1", Instant.now(), "agent-1", "sess-1", "corr-1", 2, true, 3, 500L);
        AgentEventMessage m = AgentEventKafkaBridge.map(e);
        assertEquals("turn.completed", m.eventType());
        assertEquals("id-1", m.eventId());
        assertEquals("sess-1", m.sessionId());
        assertEquals(3, m.toolCallCount());
        assertEquals(500L, m.durationMs());
    }

    @Test
    void toolCallCompletedMaps() {
        AgentEvent.ToolCallCompleted e = new AgentEvent.ToolCallCompleted(
                "id-2", Instant.now(), "agent-1", "sess-1", "corr-1",
                "baidu-search", "call-1", false, 120L);
        AgentEventMessage m = AgentEventKafkaBridge.map(e);
        assertEquals("tool.call.completed", m.eventType());
        assertEquals("baidu-search", m.toolName());
        assertEquals(false, m.toolSuccess());
    }

    @Test
    void agentCompletedMaps() {
        AgentEvent.AgentCompleted e = new AgentEvent.AgentCompleted(
                "id-3", Instant.now(), "agent-1", "sess-1", "corr-1", 5, 9999L, "success");
        AgentEventMessage m = AgentEventKafkaBridge.map(e);
        assertEquals("agent.completed", m.eventType());
        assertEquals("success", m.status());
        assertEquals(5, m.turnNumber());
    }

    @Test
    void unrelatedEventsAreIgnored() {
        assertNull(AgentEventKafkaBridge.map(new AgentEvent.AgentStarted(
                "agent-1", "sess-1", "corr-1", "react", "deepseek-chat")));
        assertNull(AgentEventKafkaBridge.map(new AgentEvent.TurnStarted(
                "id-4", Instant.now(), "agent-1", "sess-1", "corr-1", 1)));
    }
}

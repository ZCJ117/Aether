package cn.zcj.aether.messaging;

import cn.zcj.aether.types.messaging.AgentEventMessage;
import cn.zcj.aether.types.messaging.AuditEventMessage;
import cn.zcj.aether.types.messaging.KafkaMessageCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(1.3): 消息编解码契约测试 —— 两端唯一序列化通道的往返一致性与容错。
 */
class KafkaMessageCodecTest {

    @Test
    void auditEventRoundtrip() {
        AuditEventMessage msg = new AuditEventMessage(
                "evt-1", "AGENT_CHAT", "agent/1", "{arg} correlationId=c-1",
                42L, "alice", "1.2.3.4", true, null, "c-1", Instant.parse("2026-08-31T10:00:00Z"));
        String json = KafkaMessageCodec.toJson(msg);
        assertTrue(json.contains("\"eventId\":\"evt-1\""));
        assertTrue(json.contains("2026-08-31T10:00:00Z"), "Instant 应为 ISO-8601");

        AuditEventMessage parsed = KafkaMessageCodec.fromJson(json, AuditEventMessage.class);
        assertEquals(msg, parsed);
    }

    @Test
    void agentEventRoundtrip() {
        AgentEventMessage msg = new AgentEventMessage(
                "evt-2", "turn.completed", "agent-1", "sess-9", "corr-9",
                3, true, 2, 1234, 100, 200, 0.5, null, false, null,
                Instant.parse("2026-08-31T10:00:01Z"));
        AgentEventMessage parsed = KafkaMessageCodec.fromJson(
                KafkaMessageCodec.toJson(msg), AgentEventMessage.class);
        assertEquals(msg, parsed);
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        String json = """
            {"eventId":"evt-3","eventType":"turn.completed","agentId":"a","sessionId":"s",
             "correlationId":null,"turnNumber":1,"hasToolCalls":false,"toolCallCount":0,
             "durationMs":10,"inputTokens":0,"outputTokens":0,"costUsd":0,"toolName":null,
             "toolSuccess":false,"status":null,"occurredAt":"2026-08-31T10:00:00Z",
             "futureField":"whatever"}
            """;
        AgentEventMessage parsed = KafkaMessageCodec.fromJson(json, AgentEventMessage.class);
        assertEquals("evt-3", parsed.eventId());
    }

    @Test
    void malformedJsonReturnsNull() {
        assertNull(KafkaMessageCodec.fromJson("not-json{", AuditEventMessage.class));
        assertNull(KafkaMessageCodec.fromJson(null, AuditEventMessage.class));
        assertNull(KafkaMessageCodec.fromJson("  ", AuditEventMessage.class));
    }
}

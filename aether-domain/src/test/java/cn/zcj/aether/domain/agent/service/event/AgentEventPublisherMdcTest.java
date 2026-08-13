package cn.zcj.aether.domain.agent.service.event;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventPublisherMdcTest {

    @Test
    void mdcFieldsReturnContextWhenPresent() {
        MDC.put("graphExecutionId", "gx-1");
        MDC.put("sessionId", "s1");
        MDC.put("subagentId", "ad-1");
        try {
            Map<String, String> mdc = AgentEventPublisher.mdcFields();
            assertEquals("gx-1", mdc.get("graphExecutionId"));
            assertEquals("s1", mdc.get("sessionId"));
            assertEquals("ad-1", mdc.get("subagentId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void mdcFieldsReturnEmptyWhenAbsent() {
        MDC.clear();
        assertTrue(AgentEventPublisher.mdcFields().isEmpty());
    }

    @Test
    void toJsonWithMdcMergesContextIntoEventMap() {
        MDC.put("graphExecutionId", "gx-1");
        MDC.put("sessionId", "s1");
        MDC.put("subagentId", "ad-1");
        try {
            Object merged = AgentEventPublisher.toJsonWithMdc(
                    new AgentEvent.AgentStarted("agent-a", "s1", "corr-1", "researcher", "gpt-4o"));
            assertTrue(merged instanceof Map, "MDC 非空时应返回合并后的 Map");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) merged;
            assertEquals("gx-1", map.get("graphExecutionId"));
            assertEquals("ad-1", map.get("subagentId"));
            assertEquals("agent-a", map.get("agentId"), "事件自身字段应保留");
        } finally {
            MDC.clear();
        }
    }
}

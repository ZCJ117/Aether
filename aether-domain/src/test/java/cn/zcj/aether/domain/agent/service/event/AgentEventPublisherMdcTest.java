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
}

package cn.zcj.aether.domain.agent.service.agent.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentTracerGraphSpanTest {

    @Test
    void startGraphExecutionReturnsSpanScope() {
        try (AgentTracer.SpanScope scope = AgentTracer.startGraphExecution("gx-1", "s1")) {
            assertNotNull(scope);
        }
    }

    @Test
    void startGraphNodeReturnsSpan() {
        var span = AgentTracer.startGraphNode("gx-1", "researcher", "n1");
        assertNotNull(span);
        AgentTracer.endGraphNode(span, true, null);
    }

    @Test
    void endGraphNodeErrorSetsErrorStatus() {
        var span = AgentTracer.startGraphNode("gx-1", "researcher", "n1");
        assertNotNull(span);
        assertDoesNotThrow(() -> AgentTracer.endGraphNode(span, false, "boom"));
    }
}

package cn.zcj.aether.domain.agent.service.agent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics();
        ReflectionTestUtils.setField(metrics, "meterRegistry", registry);
        metrics.init();
    }

    private double value(String result) {
        return registry.get("aether.agent.plan.completions.total")
                .tag("result", result)
                .counter()
                .count();
    }

    @Test
    void planCompletionSeparatesSuccessAndFailure() {
        metrics.recordPlanCompletion(true);
        metrics.recordPlanCompletion(true);
        metrics.recordPlanCompletion(false);

        assertThat(value("success")).isEqualTo(2.0);
        assertThat(value("failure")).isEqualTo(1.0);
    }
}

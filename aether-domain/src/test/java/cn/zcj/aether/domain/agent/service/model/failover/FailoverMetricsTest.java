package cn.zcj.aether.domain.agent.service.model.failover;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P0(1.5-步骤3): 容错恢复分支指标（aether.model.recovery.branch / aether.model.fallback.switches）。
 */
class FailoverMetricsTest {

    @Test
    void recordsBranchCountersAndFallbackSwitches() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FailoverMetrics metrics = new FailoverMetrics(registry);

        metrics.recordBranch(RecoveryBranch.JITTERED_BACKOFF);
        metrics.recordBranch(RecoveryBranch.JITTERED_BACKOFF);
        metrics.recordBranch(RecoveryBranch.PROVIDER_FALLBACK);
        metrics.recordFallbackSwitch();

        assertEquals(2.0, registry.get("aether.model.recovery.branch")
                .tag("branch", "jittered_backoff").counter().count());
        assertEquals(1.0, registry.get("aether.model.recovery.branch")
                .tag("branch", "provider_fallback").counter().count());
        assertEquals(1.0, registry.get("aether.model.fallback.switches").counter().count());
    }

    @Test
    void nullRegistryIsNoOp() {
        FailoverMetrics metrics = new FailoverMetrics(null);
        assertDoesNotThrow(() -> metrics.recordBranch(RecoveryBranch.TERMINATE));
        assertDoesNotThrow(metrics::recordFallbackSwitch);
    }
}

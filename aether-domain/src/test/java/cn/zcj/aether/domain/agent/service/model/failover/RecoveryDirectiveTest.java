package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryDirectiveTest {

    @Test
    void terminateDirective() {
        RecoveryDirective d = RecoveryDirective.terminate("exhausted");
        assertEquals(RecoveryBranch.TERMINATE, d.branch());
        assertFalse(d.shouldRetry());
        assertFalse(d.rotateCredential());
        assertEquals("exhausted", d.reason());
    }

    @Test
    void retryDirectiveCarriesBackoff() {
        RecoveryDirective d = RecoveryDirective.retry(RecoveryBranch.JITTERED_BACKOFF, 2.5, "retry");
        assertEquals(RecoveryBranch.JITTERED_BACKOFF, d.branch());
        assertTrue(d.shouldRetry());
        assertEquals(2.5, d.backoffSec(), 0.001);
    }

    @Test
    void rotateCredentialDirective() {
        RecoveryDirective d = RecoveryDirective.rotateCredential("auth");
        assertEquals(RecoveryBranch.CREDENTIAL_ROTATION, d.branch());
        assertTrue(d.rotateCredential());
        assertFalse(d.compress());
        assertFalse(d.fallback());
    }

    @Test
    void compressDirective() {
        RecoveryDirective d = RecoveryDirective.compress("overflow");
        assertEquals(RecoveryBranch.CONTEXT_COMPRESSION, d.branch());
        assertTrue(d.compress());
    }

    @Test
    void fallbackDirective() {
        RecoveryDirective d = RecoveryDirective.fallback("upstream");
        assertEquals(RecoveryBranch.PROVIDER_FALLBACK, d.branch());
        assertTrue(d.fallback());
    }
}

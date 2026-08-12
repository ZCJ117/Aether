package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassifiedErrorTest {

    @Test
    void authTransientRotatesCredential() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.AUTH_TRANSIENT, 401, "openai", "gpt-4o", "401 unauthorized");
        assertTrue(e.retryable());
        assertTrue(e.shouldRotateCredential());
    }

    @Test
    void billingRotatesCredentialAndFallsBack() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.BILLING, 402, "openai", "gpt-4o", "quota exceeded");
        assertTrue(e.shouldRotateCredential());
        assertTrue(e.shouldFallback());
    }

    @Test
    void contentPolicyNotRetryableNoRotation() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.CONTENT_POLICY_BLOCKED, 400, "anthropic", "claude-sonnet", "blocked");
        assertFalse(e.retryable());
        assertFalse(e.shouldRotateCredential());
    }

    @Test
    void contextOverflowCompressesNoRotation() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.CONTEXT_OVERFLOW, null, "anthropic", "claude-sonnet", "context too long");
        assertTrue(e.shouldCompress());
        assertFalse(e.shouldRotateCredential());
        assertFalse(e.shouldStripThinking());
    }

    @Test
    void unknownIsConservative() {
        ClassifiedError e = ClassifiedError.unknown("openai", "gpt-4o", "weird error");
        assertTrue(e.retryable());
        assertFalse(e.shouldCompress());
        assertFalse(e.shouldRotateCredential());
        assertFalse(e.shouldStripThinking());
    }
}

package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnRetryStateTest {

    private static final ClassifiedError AUTH_401 = ClassifiedError.of(
            FailoverReason.AUTH_TRANSIENT, 401, "openai", "gpt-4o", "401 unauthorized");
    private static final ClassifiedError RATE_429 = ClassifiedError.of(
            FailoverReason.RATE_LIMIT, 429, "openai", "gpt-4o", "rate limited");
    private static final ClassifiedError OVERFLOW = ClassifiedError.of(
            FailoverReason.CONTEXT_OVERFLOW, null, "anthropic", "claude-sonnet", "context too long");

    @Test
    void credentialRotationAttemptedOnceThenFallback() {
        TurnRetryState st = new TurnRetryState(3, 2);
        RecoveryDirective d1 = st.nextDirective(AUTH_401);
        assertEquals(RecoveryBranch.CREDENTIAL_ROTATION, d1.branch());
        assertTrue(d1.rotateCredential());
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);

        RecoveryDirective d2 = st.nextDirective(AUTH_401);
        assertEquals(RecoveryBranch.PROVIDER_FALLBACK, d2.branch());
        assertTrue(d2.fallback());
    }

    @Test
    void compressionLimitedToTwo() {
        TurnRetryState st = new TurnRetryState(3, 2);
        assertEquals(RecoveryBranch.CONTEXT_COMPRESSION, st.nextDirective(OVERFLOW).branch());
        st.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);
        assertEquals(RecoveryBranch.CONTEXT_COMPRESSION, st.nextDirective(OVERFLOW).branch());
        st.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);

        RecoveryDirective d3 = st.nextDirective(OVERFLOW);
        assertNotEquals(RecoveryBranch.CONTEXT_COMPRESSION, d3.branch());
    }

    @Test
    void adaptiveBackoffUsesTable() {
        TurnRetryState st = new TurnRetryState(3, 1);
        RecoveryDirective d1 = st.nextDirective(RATE_429);
        assertEquals(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF, d1.branch());
        assertEquals(30.0, d1.backoffSec(), 0.001);
        st.markAttempted(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF);

        RecoveryDirective d2 = st.nextDirective(RATE_429);
        assertEquals(60.0, d2.backoffSec(), 0.001);
    }

    @Test
    void nonRecoverableTerminates() {
        TurnRetryState st = new TurnRetryState(3, 2);
        ClassifiedError cpb = ClassifiedError.of(
                FailoverReason.CONTENT_POLICY_BLOCKED, 400, "anthropic", "claude-sonnet", "blocked");
        assertEquals(RecoveryBranch.TERMINATE, st.nextDirective(cpb).branch());
    }

    @Test
    void exhaustedBranchesTerminate() {
        TurnRetryState st = new TurnRetryState(1, 0); // fallback 链为空
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION); // 轮换已用
        assertEquals(RecoveryBranch.TERMINATE, st.nextDirective(AUTH_401).branch());
    }

    @Test
    void resetClearsLedger() {
        TurnRetryState st = new TurnRetryState(3, 2);
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
        st.reset();
        assertEquals(RecoveryBranch.CREDENTIAL_ROTATION, st.nextDirective(AUTH_401).branch());
    }

    @Test
    void resetPerModelKeepsFallbackChainPosition() {
        TurnRetryState st = new TurnRetryState(3, 2);
        st.markAttempted(RecoveryBranch.PROVIDER_FALLBACK);
        st.markAttempted(RecoveryBranch.JITTERED_BACKOFF);
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
        st.resetPerModel();
        // per-model 分支计数被清，但 PROVIDER_FALLBACK 链位置保留
        assertEquals(0, st.count(RecoveryBranch.JITTERED_BACKOFF));
        assertEquals(0, st.count(RecoveryBranch.CREDENTIAL_ROTATION));
        assertEquals(1, st.count(RecoveryBranch.PROVIDER_FALLBACK));
    }
}

package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LeaseManagerTest {

    @Test
    void acquiresUpToMaxPerSession() {
        LeaseManager mgr = new LeaseManager(3);
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertEquals(3, mgr.activeLeases("s1"));
    }

    @Test
    void rejectsBeyondMaxPerSession() {
        LeaseManager mgr = new LeaseManager(2);
        assertTrue(mgr.acquireLease("s1"));
        assertTrue(mgr.acquireLease("s1"));
        assertFalse(mgr.acquireLease("s1"), "超出每 session 上限应拒绝（不排队，对齐 hermes L678）");
    }

    @Test
    void releaseFreesSlot() {
        LeaseManager mgr = new LeaseManager(2);
        mgr.acquireLease("s1");
        mgr.acquireLease("s1");
        mgr.releaseLease("s1");
        assertTrue(mgr.acquireLease("s1"), "释放后应重新获得槽位");
        assertEquals(2, mgr.activeLeases("s1"));
    }

    @Test
    void sessionsAreIndependent() {
        LeaseManager mgr = new LeaseManager(1);
        mgr.acquireLease("s1");
        assertTrue(mgr.acquireLease("s2"), "不同 session 互不影响");
        assertFalse(mgr.acquireLease("s1"));
    }
}

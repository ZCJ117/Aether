package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionControlServiceTest {

    private final SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
    private final SpawnGate spawnGate = mock(SpawnGate.class);
    private final AsyncDelegationService asyncDelegation = mock(AsyncDelegationService.class);

    private ExecutionControlService service() {
        return new ExecutionControlService(lifecycle, spawnGate, asyncDelegation);
    }

    @Test
    void interruptSubAgentDelegatesToLifecycleCancel() {
        when(lifecycle.cancel("ad-1")).thenReturn(true);
        assertTrue(service().interruptSubAgent("ad-1"));
        verify(lifecycle).cancel("ad-1");
    }

    @Test
    void interruptUnknownSubAgentReturnsFalse() {
        when(lifecycle.cancel("nope")).thenReturn(false);
        assertFalse(service().interruptSubAgent("nope"));
    }

    @Test
    void listActiveSubAgentsComposesLifecycleViews() {
        when(lifecycle.activeIds()).thenReturn(List.of("ad-1", "ad-2"));
        when(lifecycle.status("ad-1")).thenReturn(Optional.of(SubagentState.RUNNING));
        when(lifecycle.taskOf("ad-1")).thenReturn(Optional.of(
                new DelegationTask("t1", List.of(), null, "u1", "s1", null)));
        when(lifecycle.status("ad-2")).thenReturn(Optional.empty());
        when(lifecycle.taskOf("ad-2")).thenReturn(Optional.empty());

        List<ExecutionControlService.ActiveSubAgentView> views = service().listActiveSubAgents();
        assertEquals(2, views.size());
        assertEquals("ad-1", views.get(0).id());
        assertEquals("RUNNING", views.get(0).status());
        assertEquals("s1", views.get(0).sessionId());
        assertEquals("t1", views.get(0).goal());
    }

    @Test
    void setSpawnPausedDelegatesToSpawnGate() {
        when(spawnGate.isSpawnPaused()).thenReturn(true);
        service().setSpawnPaused(true);
        verify(spawnGate).setSpawnPaused(true);
        assertTrue(spawnGate.isSpawnPaused());
    }

    @Test
    void listDelegationsWithSessionDelegatesToAsync() {
        DelegationRecord rec = new DelegationRecord();
        rec.setId("ad-1");
        when(asyncDelegation.listBySession("s1")).thenReturn(List.of(rec));
        assertEquals(1, service().listDelegations("s1").size());
        verify(asyncDelegation).listBySession("s1");
    }
}

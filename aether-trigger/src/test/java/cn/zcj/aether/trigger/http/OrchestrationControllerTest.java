package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.ExecutionControlService;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrchestrationControllerTest {

    private ExecutionControlService control;
    private OrchestrationController controller;

    @BeforeEach
    void setUp() {
        control = mock(ExecutionControlService.class);
        controller = new OrchestrationController();
        ReflectionTestUtils.setField(controller, "executionControlService", control);
    }

    @Test
    void activeSubAgentsReturnsWrappedList() {
        when(control.listActiveSubAgents()).thenReturn(List.of(
                new ExecutionControlService.ActiveSubAgentView("ad-1", "RUNNING", "s1", "t1", null)));
        Response<List<ExecutionControlService.ActiveSubAgentView>> resp = controller.activeSubAgents();
        assertNotNull(resp.getData());
        assertEquals(1, resp.getData().size());
        assertEquals("ad-1", resp.getData().get(0).id());
    }

    @Test
    void interruptReturnsHitResult() {
        when(control.interruptSubAgent("ad-1")).thenReturn(true);
        Response<Boolean> resp = controller.interrupt("ad-1");
        assertEquals(Boolean.TRUE, resp.getData());
        verify(control).interruptSubAgent("ad-1");
    }

    @Test
    void spawnPauseParsesBodyAndDelegates() {
        when(control.setSpawnPaused(true)).thenReturn(true);
        Response<Boolean> resp = controller.setSpawnPaused(Map.of("paused", true));
        assertEquals(Boolean.TRUE, resp.getData());
        verify(control).setSpawnPaused(true);
    }

    @Test
    void delegationsWithSessionDelegates() {
        DelegationRecord rec = new DelegationRecord();
        rec.setId("ad-1");
        rec.setState(SubagentState.RUNNING);
        when(control.listDelegations("s1")).thenReturn(List.of(rec));
        Response<List<DelegationRecord>> resp = controller.delegations("s1");
        assertEquals(1, resp.getData().size());
        verify(control).listDelegations("s1");
    }
}

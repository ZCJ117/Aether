package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubagentStateTest {

    @Test
    void hasAllDesignStates() {
        assertArrayEquals(new SubagentState[]{
                SubagentState.QUEUED, SubagentState.PENDING, SubagentState.RUNNING,
                SubagentState.COMPLETED, SubagentState.FAILED, SubagentState.CANCELLED,
                SubagentState.TIMED_OUT, SubagentState.INTERRUPTED
        }, SubagentState.values());
    }

    @Test
    void delegationTaskRejectsNullTaskAndSession() {
        assertThrows(NullPointerException.class,
                () -> new DelegationTask(null, List.of(), null, "u1", "s1", "a1"));
        assertThrows(NullPointerException.class,
                () -> new DelegationTask("task", List.of(), null, "u1", null, "a1"));
    }

    @Test
    void delegationTaskCopiesToolNames() {
        DelegationTask task = new DelegationTask("t", List.of("codexplorer"), "m", "u1", "s1", "a1");
        assertTrue(task.toolNames().contains("codexplorer"));
        DelegationTask noTools = new DelegationTask("t", null, "m", "u1", "s1", "a1");
        assertTrue(noTools.toolNames().isEmpty(), "null toolNames 应归一为空列表");
    }

    @Test
    void delegationRecordBuilderDefaultsStateToQueued() {
        DelegationRecord rec = DelegationRecord.builder()
                .id("ad-1").parentSessionId("s1").taskPayload("t").build();
        assertEquals("ad-1", rec.getId());
        assertEquals("s1", rec.getParentSessionId());
        assertFalse(rec.isCompletionDelivered(), "completionDelivered 默认 false");
    }
}

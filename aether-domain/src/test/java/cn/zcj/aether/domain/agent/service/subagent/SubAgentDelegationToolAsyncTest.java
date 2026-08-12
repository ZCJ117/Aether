package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubAgentDelegationToolAsyncTest {

    private final SubAgentOrchestrator orchestrator = mock(SubAgentOrchestrator.class);
    private final AsyncDelegationService asyncService = mock(AsyncDelegationService.class);
    private final SpawnGate spawnGate = mock(SpawnGate.class);
    private final ToolContext ctx = new ToolContext("u1", "s1", "call-1");

    private Map<String, Object> input(boolean async) {
        return Map.of("task", "分析", "async", async);
    }

    @Test
    void asyncModeReturnsDelegationIdImmediately() {
        when(spawnGate.enter()).thenReturn(true);
        when(asyncService.dispatch(any())).thenReturn("ad-abc123");
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, asyncService);

        ToolResult result = tool.call(input(true), ctx);

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("ad-abc123"));
        verify(asyncService).dispatch(any(DelegationTask.class));
        verify(spawnGate).exit();
        verify(orchestrator, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void asyncModeRejectedByLeaseReturnsError() {
        when(spawnGate.enter()).thenReturn(true);
        when(asyncService.dispatch(any())).thenReturn(null);
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, asyncService);

        ToolResult result = tool.call(input(true), ctx);

        assertTrue(result.isError());
        verify(spawnGate).exit();
    }

    @Test
    void spawnGatePausedBlocksDelegation() {
        when(spawnGate.enter()).thenReturn(false);
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, asyncService);

        ToolResult result = tool.call(input(true), ctx);

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("拒绝"));
        verify(asyncService, never()).dispatch(any());
        verify(orchestrator, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void syncPathUnchangedWhenAsyncServiceAbsent() {
        when(spawnGate.enter()).thenReturn(true);
        when(orchestrator.dispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ResultRefiner.SubAgentResult("成功", "[结论]", Map.of()));
        // 构造器兼容：仅 orchestrator（模拟未接线异步能力）
        SubAgentDelegationTool tool = new SubAgentDelegationTool(orchestrator, spawnGate, null);

        ToolResult result = tool.call(input(true), ctx);

        assertFalse(result.isError());
        verify(orchestrator).dispatch("分析", List.of(), null, null, "u1", "s1");
    }
}

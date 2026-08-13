package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry.McpToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpRefreshControllerTest {

    private McpToolRegistry registry;
    private McpRefreshController controller;

    @BeforeEach
    void setUp() {
        registry = mock(McpToolRegistry.class);
        controller = new McpRefreshController();
        ReflectionTestUtils.setField(controller, "mcpToolRegistry", registry);
    }

    @Test
    void refreshWithServerIdDelegates() {
        when(registry.refresh("srv-a")).thenReturn(new McpToolRegistry.RefreshResult(List.of("t2"), List.of()));

        Response<Map<String, McpToolRegistry.RefreshResult>> resp = controller.refresh(Map.of("serverId", "srv-a"));

        assertNotNull(resp.getData());
        assertTrue(resp.getData().containsKey("srv-a"));
        verify(registry).refresh("srv-a");
    }

    @Test
    void refreshWithNullBodyRefreshesAll() {
        when(registry.serverIds()).thenReturn(List.of("a", "b"));
        when(registry.refresh(anyString())).thenReturn(new McpToolRegistry.RefreshResult(List.of(), List.of()));

        Response<Map<String, McpToolRegistry.RefreshResult>> resp = controller.refresh(null);

        assertEquals(2, resp.getData().size());
        verify(registry, times(2)).refresh(anyString());
    }

    @Test
    void refreshSwallowsExceptionReturnsError() {
        when(registry.refresh(anyString())).thenThrow(new RuntimeException("boom"));

        Response<Map<String, McpToolRegistry.RefreshResult>> resp = controller.refresh(Map.of("serverId", "srv-a"));

        assertNull(resp.getData());
    }
}

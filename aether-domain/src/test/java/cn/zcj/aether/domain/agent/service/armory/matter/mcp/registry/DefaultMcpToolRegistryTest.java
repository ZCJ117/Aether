package cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DefaultMcpToolRegistryTest {

    private final DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry();

    @Test
    void registerAndGetTools() {
        registry.register("server-a", List.of(new ToolSpec("tool1", "desc1", true)));
        assertEquals(1, registry.getTools("server-a").size());
        assertEquals("tool1", registry.getTools("server-a").get(0).name());
        assertEquals(0, registry.getTools("server-missing").size());
    }

    @Test
    void parallelSafeUsesExactProvenanceNotPrefix() {
        registry.register("server_a", List.of(
                new ToolSpec("mcp__server_a__read", "r", true),
                new ToolSpec("mcp__server_a__write", "w", false)));
        assertFalse(registry.isToolParallelSafe("mcp__server_a__safe"));
        assertTrue(registry.isToolParallelSafe("mcp__server_a__read"));
        assertFalse(registry.isToolParallelSafe("mcp__server_a__write"));
        assertFalse(registry.isToolParallelSafe("unknown"));
    }

    @Test
    void refreshDiffAddsAndRemoves() {
        registry.register("server-a", List.of(new ToolSpec("tool1", "d", false)));
        McpToolRegistry.RefreshResult result = registry.refreshTools("server-a",
                () -> List.of(new ToolSpec("tool1", "d", true), new ToolSpec("tool2", "d2", false)));

        assertEquals(List.of("tool2"), result.added());
        assertEquals(List.of(), result.removed());
        assertEquals(2, registry.getTools("server-a").size());
        assertTrue(registry.isToolParallelSafe("tool1"));
        assertFalse(registry.isToolParallelSafe("tool2"));
    }

    @Test
    void refreshRemovedToolIsForgotten() {
        registry.register("server-a", List.of(new ToolSpec("old", "d", false)));
        McpToolRegistry.RefreshResult result = registry.refreshTools("server-a",
                () -> List.of(new ToolSpec("new", "d2", false)));

        assertEquals(List.of("new"), result.added());
        assertEquals(List.of("old"), result.removed());
        assertFalse(registry.isToolParallelSafe("old"));
    }

    @Test
    void refreshRebuilderFailureIsSwallowed() {
        registry.register("server-a", List.of(new ToolSpec("tool1", "d", false)));
        McpToolRegistry.RefreshResult result = registry.refreshTools("server-a",
                () -> { throw new RuntimeException("rebuild failed"); });

        assertTrue(result.added().isEmpty());
        assertEquals(1, registry.getTools("server-a").size());
    }

    @Test
    void registerWithRebuilderAndRefreshDiff() {
        registry.register("server-a", List.of(new ToolSpec("tool1", "d", false)),
                () -> List.of(new ToolSpec("tool1", "d", true), new ToolSpec("tool2", "d2", false)));

        McpToolRegistry.RefreshResult result = registry.refresh("server-a");

        assertEquals(List.of("tool2"), result.added());
        assertEquals(2, registry.getTools("server-a").size());
        assertTrue(registry.isToolParallelSafe("tool1"));
        assertFalse(registry.isToolParallelSafe("tool2"));
    }

    @Test
    void refreshUnknownServerReturnsEmpty() {
        McpToolRegistry.RefreshResult result = registry.refresh("missing");
        assertTrue(result.added().isEmpty());
        assertTrue(result.removed().isEmpty());
    }

    @Test
    void serverIdsListsRegisteredServers() {
        registry.register("a", List.of(new ToolSpec("t", "d", false)));
        registry.register("b", List.of(new ToolSpec("t2", "d", false)));
        assertEquals(2, registry.serverIds().size());
        assertTrue(registry.serverIds().contains("a"));
        assertTrue(registry.serverIds().contains("b"));
    }
}

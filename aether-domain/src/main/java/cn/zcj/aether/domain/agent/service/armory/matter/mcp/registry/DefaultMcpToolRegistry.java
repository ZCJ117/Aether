package cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 内存版 MCP 工具注册表（对齐 hermes mcp_tool.py _parallel_safe_servers / _mcp_tool_server_names）。
 */
@Slf4j
@Component
public class DefaultMcpToolRegistry implements McpToolRegistry {

    /** serverId → 工具快照 */
    private final Map<String, List<ToolSpec>> toolsByServer = new ConcurrentHashMap<>();
    /** toolName → serverId（注册时精确捕获的 provenance） */
    private final Map<String, String> serverByToolName = new ConcurrentHashMap<>();

    @Override
    public void register(String serverId, List<ToolSpec> tools) {
        for (ToolSpec t : tools) {
            serverByToolName.put(t.name(), serverId);
        }
        toolsByServer.put(serverId, List.copyOf(tools));
        log.info("MCP 工具已登记: server={} tools={}", serverId, tools.size());
    }

    @Override
    public List<ToolSpec> getTools(String serverId) {
        return toolsByServer.getOrDefault(serverId, List.of());
    }

    @Override
    public boolean isToolParallelSafe(String toolName) {
        String serverId = serverByToolName.get(toolName);
        if (serverId == null) {
            return false;
        }
        return toolsByServer.getOrDefault(serverId, List.of()).stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .map(ToolSpec::parallelSafe)
                .orElse(false);
    }

    @Override
    public RefreshResult refreshTools(String serverId, Supplier<List<ToolSpec>> rebuilder) {
        List<ToolSpec> oldTools = toolsByServer.getOrDefault(serverId, List.of());
        Set<String> oldNames = oldTools.stream().map(ToolSpec::name).collect(Collectors.toSet());

        List<ToolSpec> newTools;
        try {
            newTools = rebuilder.get();
        } catch (Exception e) {
            log.warn("MCP 刷新失败（保留旧快照）: server={} err={}", serverId, e.getMessage());
            return new RefreshResult(List.of(), List.of());
        }
        if (newTools == null) {
            log.warn("MCP 刷新返回空，保留旧快照: server={}", serverId);
            return new RefreshResult(List.of(), List.of());
        }

        Set<String> newNames = newTools.stream().map(ToolSpec::name).collect(Collectors.toSet());
        List<String> added = new ArrayList<>(newNames);
        added.removeAll(oldNames);
        List<String> removed = new ArrayList<>(oldNames);
        removed.removeAll(newNames);

        toolsByServer.put(serverId, List.copyOf(newTools));
        for (String name : removed) {
            serverByToolName.remove(name);
        }
        for (ToolSpec t : newTools) {
            serverByToolName.put(t.name(), serverId);
        }

        if (!added.isEmpty() || !removed.isEmpty()) {
            log.warn("MCP 工具动态变更（需人工确认）: server={} +{} -{}", serverId, added, removed);
        } else {
            log.info("MCP 工具无变更: server={}", serverId);
        }
        return new RefreshResult(added, removed);
    }
}

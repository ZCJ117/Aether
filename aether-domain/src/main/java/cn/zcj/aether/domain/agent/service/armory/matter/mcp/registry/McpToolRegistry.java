package cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry;

import java.util.List;
import java.util.function.Supplier;

/**
 * MCP 工具运行时注册表（domain 端口）— 对齐 hermes mcp_tool.py。
 * 跟踪每 server 的工具快照 + 工具→server 精确溯源（_mcp_tool_server_names）。
 */
public interface McpToolRegistry {

    /** 登记某 MCP server 的工具快照 */
    void register(String serverId, List<ToolSpec> tools);

    /**
     * 登记某 MCP server 的工具快照，并附带运行时刷新 rebuilder（重新拉取 tools/list）。
     * 供手动 POST /api/mcp/refresh 触发 {@link #refresh(String)}。
     */
    void register(String serverId, List<ToolSpec> tools, Supplier<List<ToolSpec>> rebuilder);

    /** 查询某 MCP server 的工具快照 */
    List<ToolSpec> getTools(String serverId);

    /**
     * 并行安全判定（对齐 hermes is_mcp_tool_parallel_safe L5816）。
     * 用注册时捕获的精确溯源查集合，绝不按工具名前缀拆分 server 名。
     */
    boolean isToolParallelSafe(String toolName);

    /**
     * 刷新某 MCP server 工具集（对齐 hermes _refresh_tools L2075）。
     * 拉全量 → diff 增量更新（避免 nuke-and-repave 的 stale-handler 竞态），
     * 快照 in-place 替换，返回新增/移除的工具名。
     */
    RefreshResult refreshTools(String serverId, Supplier<List<ToolSpec>> rebuilder);

    /** 用已登记的 rebuilder 刷新某 MCP server 工具集（手动触发）。未登记 rebuilder 时返回空结果。 */
    RefreshResult refresh(String serverId);

    /** 已登记的所有 serverId（供 refresh-all） */
    List<String> serverIds();

    /** 刷新结果：新增/移除的工具名 */
    record RefreshResult(List<String> added, List<String> removed) {
    }
}

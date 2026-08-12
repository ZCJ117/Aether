package cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry;

/**
 * MCP 工具元数据 — 对齐 hermes mcp_tool.py 工具注册信息（name/description/并行安全标记）。
 */
public record ToolSpec(String name, String description, boolean parallelSafe) {
}

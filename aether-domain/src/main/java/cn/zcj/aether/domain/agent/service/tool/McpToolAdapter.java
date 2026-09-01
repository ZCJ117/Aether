package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * MCP工具适配器 — 将现有MCP客户端实现适配为Tool接口
 *
 * 在 adapt() 时构建 ToolCallback 并缓存，避免每次 call() 时重建 MCP 连接。
 * 适配后的 Tool 注册到 ToolRegistry。
 */
@Slf4j
@Service
public class McpToolAdapter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    public Tool adapt(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        String name = extractName(toolMcp);
        TooMcpCreateService createService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);

        // 在 adapt 时构建 ToolCallback 并缓存（复用，不在每次 call 时重建连接）
        ToolCallback[] toolCallbacks;
        try {
            toolCallbacks = createService.buildToolCallback(toolMcp);
        } catch (Exception e) {
            log.error("Failed to build ToolCallback for MCP tool: {}", name, e);
            return createErrorTool(name, e.getMessage());
        }

        if (toolCallbacks == null || toolCallbacks.length == 0) {
            return createErrorTool(name, "No ToolCallback available");
        }

        ToolCallback cachedCallback = toolCallbacks[0];

        // O9: adapt 时从 MCP 工具定义提取真实 inputSchema 并缓存（替代硬编码空 schema），
        // 使 ToolExecutor 的 JSON Schema 校验与 SchemaHintBuilder 回喂对 MCP 工具真实生效
        Map<String, Object> cachedInputSchema = extractInputSchema(cachedCallback, name);
        String cachedDescription = extractDescription(cachedCallback, name);

        return new Tool() {
            @Override
            public String name() { return name; }

            @Override
            public String description() { return cachedDescription; }

            @Override
            public Map<String, Object> inputSchema() { return cachedInputSchema; }

            @Override
            public ToolResult call(Map<String, Object> input, ToolContext context) {
                try {
                    String jsonInput = objectMapper.writeValueAsString(input);
                    String result = cachedCallback.call(jsonInput);
                    return ToolResult.success(context.toolCallId(), name, result);
                } catch (Exception e) {
                    log.error("MCP tool call failed: {}", name, e);
                    return ToolResult.error(context.toolCallId(), name, e.getMessage());
                }
            }

            @Override
            public boolean isConcurrencySafe() {
                // MCP 远程工具默认非并发安全（共享连接）
                return false;
            }
        };
    }

    private String extractName(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp.getSse() != null) return toolMcp.getSse().getName();
        if (toolMcp.getStdio() != null) return toolMcp.getStdio().getName();
        if (toolMcp.getLocal() != null) return toolMcp.getLocal().getName();
        return "unknown_mcp";
    }

    /**
     * O9: 从 MCP ToolCallback 的 ToolDefinition 提取真实 inputSchema。
     * 来源缺失 / 解析失败时降级为空 schema 并告警（不阻断，保留空 schema 分支可回滚）。
     */
    private Map<String, Object> extractInputSchema(ToolCallback callback, String name) {
        try {
            var def = callback.getToolDefinition();
            if (def == null || def.inputSchema() == null || def.inputSchema().isBlank()) {
                log.warn("MCP 工具 [{}] 的 ToolDefinition 未提供 inputSchema，降级为空 schema"
                        + "（JSON Schema 校验与 Schema 提示对该工具不生效）", name);
                return Map.of("type", "object", "properties", Map.of());
            }
            return objectMapper.readValue(def.inputSchema(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("MCP 工具 [{}] inputSchema 解析失败，降级为空 schema: {}", name, e.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    /** O9: 优先使用 MCP 工具定义的真实 description，缺失时回退占位描述。 */
    private String extractDescription(ToolCallback callback, String name) {
        try {
            var def = callback.getToolDefinition();
            if (def != null && def.description() != null && !def.description().isBlank()) {
                return def.description();
            }
        } catch (Exception ignored) {
            // 定义读取失败时回退占位描述
        }
        return "MCP tool: " + name;
    }

    private Tool createErrorTool(String name, String errorMsg) {
        return new Tool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return "Error: " + errorMsg; }
            @Override
            public Map<String, Object> inputSchema() { return Map.of(); }
            @Override
            public ToolResult call(Map<String, Object> input, ToolContext context) {
                return ToolResult.error(context.toolCallId(), name, errorMsg);
            }
        };
    }
}

package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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

        return new Tool() {
            @Override
            public String name() { return name; }

            @Override
            public String description() { return "MCP tool: " + name; }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

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

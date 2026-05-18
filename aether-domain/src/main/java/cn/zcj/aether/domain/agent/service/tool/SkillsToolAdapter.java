package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

/**
 * Skills工具适配器 — 将 Skills ToolCallback 适配为 Tool 接口，注册到 ToolRegistry
 *
 * Skills 工具不同于 MCP 工具：Skills 由 spring-ai-community 的 SkillsTool 实现，
 * 通过 ToolSkillsCreateService.buildToolCallback() 获取 ToolCallback。
 *
 * 适配后的 Tool 注册到 ToolRegistry，供 AgentRuntime → ToolExecutor 调用。
 */
@Slf4j
@Service
public class SkillsToolAdapter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    public Tool adapt(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) {
        String toolName = deriveName(toolSkills);

        // 在 adapt 时构建 ToolCallback（复用，不在每次 call 时重建）
        ToolCallback[] toolCallbacks;
        try {
            toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
        } catch (Exception e) {
            log.error("Failed to build ToolCallback for skill: {}", toolName, e);
            return createErrorTool(toolName, e.getMessage());
        }

        if (toolCallbacks == null || toolCallbacks.length == 0) {
            return createErrorTool(toolName, "No ToolCallback available");
        }

        ToolCallback callback = toolCallbacks[0];

        return new Tool() {
            @Override
            public String name() { return toolName; }

            @Override
            public String description() {
                return "Skills tool: " + toolSkills.getType() + " @ " + toolSkills.getPath();
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string")));
            }

            @Override
            public ToolResult call(Map<String, Object> input, ToolContext context) {
                try {
                    String jsonInput = objectMapper.writeValueAsString(input);
                    String result = callback.call(jsonInput);
                    return ToolResult.success(context.toolCallId(), toolName, result);
                } catch (Exception e) {
                    log.error("Skills tool call failed: {}", toolName, e);
                    return ToolResult.error(context.toolCallId(), toolName, e.getMessage());
                }
            }

            @Override
            public boolean isConcurrencySafe() {
                return true; // Skills 工具通常是只读搜索，并发安全
            }
        };
    }

    private String deriveName(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) {
        String type = toolSkills.getType() != null ? toolSkills.getType() : "unknown";
        String path = toolSkills.getPath() != null ? toolSkills.getPath() : "";
        // 从路径中提取最后一段作为简短名称
        if (!path.isEmpty()) {
            String[] parts = path.replace('\\', '/').split("/");
            return type + ":" + parts[parts.length - 1];
        }
        return type;
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

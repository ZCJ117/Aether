package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
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

        // O9: adapt 时从 skill ToolCallback 的 ToolDefinition 提取真实 inputSchema 并缓存
        //（替代硬编码 {"query":string} 固定 schema），使校验与 Schema 提示对 Skills 工具真实生效
        Map<String, Object> cachedInputSchema = extractInputSchema(callback, toolName);
        String cachedDescription = extractDescription(callback, toolName, toolSkills);

        return new Tool() {
            @Override
            public String name() { return toolName; }

            @Override
            public String description() { return cachedDescription; }

            @Override
            public Map<String, Object> inputSchema() { return cachedInputSchema; }

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

    /**
     * O9: 从 Skills ToolCallback 的 ToolDefinition 提取真实 inputSchema。
     * 来源缺失 / 解析失败时降级为空 schema 并告警（不阻断，保留空 schema 分支可回滚）。
     */
    private Map<String, Object> extractInputSchema(ToolCallback callback, String toolName) {
        try {
            var def = callback.getToolDefinition();
            if (def == null || def.inputSchema() == null || def.inputSchema().isBlank()) {
                log.warn("Skills 工具 [{}] 的 ToolDefinition 未提供 inputSchema，降级为空 schema"
                        + "（JSON Schema 校验与 Schema 提示对该工具不生效）", toolName);
                return Map.of("type", "object", "properties", Map.of());
            }
            return objectMapper.readValue(def.inputSchema(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Skills 工具 [{}] inputSchema 解析失败，降级为空 schema: {}", toolName, e.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    /** O9: 优先使用 ToolDefinition 的真实 description，缺失时回退 "type @ path" 描述。 */
    private String extractDescription(ToolCallback callback, String toolName,
            AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) {
        try {
            var def = callback.getToolDefinition();
            if (def != null && def.description() != null && !def.description().isBlank()) {
                return def.description();
            }
        } catch (Exception ignored) {
            // 定义读取失败时回退占位描述
        }
        return "Skills tool: " + toolSkills.getType() + " @ " + toolSkills.getPath();
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

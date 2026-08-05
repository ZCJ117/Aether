package cn.zcj.aether.domain.agent.service.tool.python;

import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.function.Function;

/**
 * 将 Aether 内部 {@link Tool} 接口适配为 Spring AI {@link ToolCallback}，
 * 使 Python 微服务工具（read_file / write_file / list_directory / search_files /
 * execute_code / read_document / create_document）对 LLM 可见。
 *
 * <p>背景：{@link PythonTools} 只把工具注册到 {@link cn.zcj.aether.domain.agent.service.tool.ToolRegistry}
 * 供 {@link cn.zcj.aether.domain.agent.service.tool.ToolExecutor} 调用，但从未转换成
 * {@link ToolCallback} 注入 {@link org.springframework.ai.chat.model.ChatModel}，
 * 因此 LLM 根本不知道这些工具存在，无法主动调用它们去查看工作区文件。
 *
 * <p>本适配器将 Tool 包装成 Spring AI ToolCallback，加入 ChatModel 的
 * toolCallbacks 后，LLM 即可看到并调用这些工具。
 */
public class PythonToolCallbackAdapter implements ToolCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tool tool;
    private final ToolDefinition toolDefinition;
    private final Function<Map<String, Object>, ToolResult> invoker;

    public PythonToolCallbackAdapter(Tool tool) {
        this.tool = tool;
        this.toolDefinition = buildToolDefinition(tool);
        this.invoker = input -> {
            try {
                return tool.call(input, new ToolContext("", "", ""));
            } catch (Exception e) {
                return ToolResult.error("", tool.name(), "工具执行异常: " + e.getMessage());
            }
        };
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> input;
        try {
            input = MAPPER.readValue(toolInput == null ? "{}" : toolInput,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "[工具调用参数解析失败] 请提供合法的 JSON 参数: " + e.getMessage();
        }

        ToolResult result = invoker.apply(input);
        if (result.isError()) {
            return "[工具执行失败] " + (result.getContent() != null ? result.getContent() : "");
        }
        return result.getContent() != null ? result.getContent() : "";
    }

    /**
     * 将 Tool 的 inputSchema()（Map 形式的 JSON Schema）序列化为字符串。
     */
    private ToolDefinition buildToolDefinition(Tool tool) {
        String name = tool.name();
        String description = tool.description();
        String inputSchema = "{}";
        try {
            inputSchema = MAPPER.writeValueAsString(tool.inputSchema() != null
                    ? tool.inputSchema() : Map.of());
        } catch (Exception e) {
            inputSchema = "{}";
        }
        return new DefaultToolDefinition(name, description, inputSchema);
    }
}

package cn.zcj.aether.domain.agent.service.tool.validation;

import java.util.*;

/**
 * 面向 LLM 的 Schema 提示构建器。
 *
 * <p>移植自 crewAI {@code structured_tool.py} 的 {@code build_schema_hint()} 函数（L43-61）：
 * 从 {@code inputSchema()} JSON Schema Map 中提取 {@code properties} 和 {@code required}，
 * 格式化为 LLM 可读的文本提示，引导其修正参数后重新调用工具。
 *
 * <p>输出示例：
 * <pre>{@code
 * Expected arguments: {"query": {"type": "string", "description": "搜索关键词"}}
 * Required: ["query"]
 * 请修正参数后重新调用本工具。
 * }</pre>
 */
public final class SchemaHintBuilder {

    private SchemaHintBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 从 inputSchema Map 构建面向 LLM 的 Schema 提示文本。
     *
     * @param inputSchema 工具的 JSON Schema 定义（Tool.inputSchema() 返回的 Map）
     * @return 格式化后的 Schema 提示字符串；inputSchema 为空时返回空字符串
     */
    public static String buildHint(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return "";
        }

        Object properties = inputSchema.getOrDefault("properties", Collections.emptyMap());
        Object required = inputSchema.getOrDefault("required", Collections.emptyList());

        StringBuilder sb = new StringBuilder();
        sb.append("\nExpected arguments: ").append(toJsonString(properties));
        sb.append("\nRequired: ").append(toJsonString(required));
        sb.append("\n请修正参数后重新调用本工具。");
        return sb.toString();
    }

    /**
     * 将对象序列化为紧凑的 JSON 字符串。
     * 对齐 crewAI {@code json.dumps(schema.get('properties', {}))} 的行为。
     */
    private static String toJsonString(Object obj) {
        if (obj == null) {
            return "{}";
        }
        if (obj instanceof String s) {
            return s;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}

package cn.zcj.aether.domain.agent.service.tool.validation;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 工具入参 JSON Schema 校验器。
 *
 * <p>移植自 crewAI {@code _validate_kwargs()} 的 {@code model_validate} 语义（base_tool.py L248-269）：
 * 读取 {@link cn.zcj.aether.domain.agent.service.tool.Tool#inputSchema()} 返回的 JSON Schema Map，
 * 逐字段校验 {@code required} 存在性、{@code type} 匹配、{@code enum} 约束，
 * 对嵌套 object 递归一层。
 *
 * <p>校验失败时产出"哪个字段、期望什么、实际什么"的逐条错误详情——
 * 对应 crewAI 的 ValidationError 文本化，随后由 {@link SchemaHintBuilder} 追加 Schema 提示回喂 LLM。
 *
 * <p>线程安全：无状态，可作为 Spring singleton 注入。
 */
@Slf4j
public class ToolInputValidator {

    private static final Set<String> VALID_TYPES = Set.of(
            "string", "number", "integer", "boolean", "object", "array", "null"
    );

    /**
     * 按 JSON Schema 校验工具入参。
     *
     * @param inputSchema 工具的输入 Schema（Tool.inputSchema() 的返回值）
     * @param input       实际入参
     * @return 校验结果：通过返回 {@link ValidationResult#ok()}，失败返回带详情的原因
     */
    public ValidationResult validate(Map<String, Object> inputSchema, Map<String, Object> input) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            // 无 Schema 声明则跳过校验（宽松策略，对齐 crewAI args_schema=None 的情况）
            return ValidationResult.ok();
        }

        List<String> errors = new ArrayList<>();

        // 1. 校验 required 字段存在性
        Object requiredRaw = inputSchema.get("required");
        if (requiredRaw instanceof List<?> requiredList) {
            for (Object field : requiredList) {
                String fieldName = String.valueOf(field);
                if (input == null || !input.containsKey(fieldName)) {
                    errors.add("缺少必填字段 '" + fieldName + "'");
                }
            }
        }

        // 2. 校验 properties 中各字段的 type 与 enum 约束
        Object propertiesRaw = inputSchema.get("properties");
        if (propertiesRaw instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String fieldName = String.valueOf(entry.getKey());
                @SuppressWarnings("unchecked")
                Map<String, Object> fieldSchema = (entry.getValue() instanceof Map)
                        ? (Map<String, Object>) entry.getValue()
                        : Collections.emptyMap();

                Object actualValue = (input != null) ? input.get(fieldName) : null;
                if (actualValue == null) {
                    continue; // required 校验已覆盖必填项，非必填项允许缺失
                }

                // type 校验
                String expectedType = fieldSchema.get("type") instanceof String
                        ? (String) fieldSchema.get("type")
                        : null;
                if (expectedType != null && VALID_TYPES.contains(expectedType)) {
                    String actualType = inferJsonType(actualValue);
                    if (!expectedType.equals(actualType) && !("integer".equals(expectedType) && "number".equals(actualType))) {
                        // integer 兼容 number（JSON 无 integer 类型，Java 的 Integer 在 Jackson 中可能转为 number）
                        if (!("number".equals(expectedType) && "integer".equals(actualType))) {
                            errors.add("字段 '" + fieldName + "' 类型不匹配: 期望 " + expectedType
                                    + ", 实际 " + actualType + " (值: " + truncate(actualValue) + ")");
                        }
                    }
                }

                // enum 约束校验
                Object enumRaw = fieldSchema.get("enum");
                if (enumRaw instanceof List<?> enumList && !enumList.isEmpty()) {
                    boolean matched = enumList.stream().anyMatch(e -> Objects.equals(String.valueOf(e), String.valueOf(actualValue)));
                    if (!matched) {
                        errors.add("字段 '" + fieldName + "' 值 '" + truncate(actualValue)
                                + "' 不在允许的枚举中: " + enumList);
                    }
                }

                // 嵌套 object 递归校验一层
                if ("object".equals(expectedType) && actualValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedSchema = new HashMap<>();
                    if (fieldSchema.get("properties") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> np = (Map<String, Object>) fieldSchema.get("properties");
                        nestedSchema.put("properties", np);
                    }
                    if (fieldSchema.get("required") instanceof List) {
                        nestedSchema.put("required", fieldSchema.get("required"));
                    }
                    if (!nestedSchema.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nestedInput = (Map<String, Object>) actualValue;
                        ValidationResult nestedResult = validate(nestedSchema, nestedInput);
                        if (!nestedResult.valid()) {
                            errors.add("嵌套字段 '" + fieldName + "': " + nestedResult.errorMessage());
                        }
                    }
                }
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }

        String errorDetail = String.join("; ", errors);
        log.debug("Schema 校验失败: {}", errorDetail);
        return ValidationResult.fail(errorDetail);
    }

    /**
     * 推断 Java 对象对应的 JSON Schema type。
     */
    private String inferJsonType(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) return "integer";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List || value.getClass().isArray()) return "array";
        if (value instanceof Map) return "object";
        return "string"; // fallback
    }

    private String truncate(Object value) {
        if (value == null) return "null";
        String s = String.valueOf(value);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}

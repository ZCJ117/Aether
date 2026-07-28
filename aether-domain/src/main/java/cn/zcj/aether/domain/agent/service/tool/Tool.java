package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.service.tool.validation.ValidationResult;

import java.util.Map;

/**
 * 统一工具接口
 * 吸取项目B src/Tool.ts:362-695 的设计理念
 *
 * <p>P0-1 升级：{@link #validate(Map)} 替代旧 {@link #validateInput(Map)}，
 * 返回结构化 {@link ValidationResult} 以携带失败原因，
 * 对齐 crewAI {@code _validate_kwargs} 的 ValidationError 文本化模式。
 */
public interface Tool {

    /** 工具名称 */
    String name();

    /** 工具描述 (给LLM看) */
    String description();

    /** JSON Schema 输入定义 */
    Map<String, Object> inputSchema();

    /** 执行工具 */
    ToolResult call(Map<String, Object> input, ToolContext context);

    /** 是否并发安全 — 默认false (吸取项目B TOOL_DEFAULTS, Tool.ts:759) */
    default boolean isConcurrencySafe() {
        return false;
    }

    /** 是否只读操作 */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * 输入校验（结构化结果）。
     *
     * <p>P0-1 新增：替代旧 {@link #validateInput(Map)}。
     * 默认桥接旧方法以保证向后兼容，新工具应直接覆盖本方法。
     *
     * @param input 工具入参
     * @return 校验通过返回 {@link ValidationResult#ok()}，失败返回带原因的结果
     */
    default ValidationResult validate(Map<String, Object> input) {
        return validateInput(input) ? ValidationResult.ok()
                                    : ValidationResult.fail("input rejected by tool");
    }

    /**
     * 输入校验（布尔返回值）。
     *
     * @deprecated 请使用 {@link #validate(Map)} 以携带失败原因。
     *             保留一个版本周期后移除。
     */
    @Deprecated
    default boolean validateInput(Map<String, Object> input) {
        return true;
    }

    /** 权限检查 */
    default boolean checkPermissions(Map<String, Object> input) {
        return true;
    }
}


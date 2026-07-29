package cn.zcj.aether.domain.agent.service.tool.validation;

/**
 * 结构化校验结果。
 *
 * <p>替代 {@code boolean validateInput()} 的布尔返回值，
 * 提供失败原因文本，可直接回喂 LLM。
 *
 * <p>设计参考 crewAI 的 Pydantic ValidationError 文本化模式：
 * 校验失败时不抛异常，而是产出带错误详情的 record，
 * 由 {@link cn.zcj.aether.domain.agent.service.tool.ToolExecutor} 封装为 ToolResult 回传。
 *
 * @param valid        是否通过校验
 * @param errorMessage 失败原因（valid=true 时为 null）
 */
public record ValidationResult(boolean valid, String errorMessage) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult fail(String reason) {
        return new ValidationResult(false, reason);
    }
}

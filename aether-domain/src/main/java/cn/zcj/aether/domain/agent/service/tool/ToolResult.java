package cn.zcj.aether.domain.agent.service.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果。
 *
 * <p>P0-1 升级：新增 {@link ErrorType} 枚举，
 * 使上游可区分 VALIDATION / PERMISSION / EXECUTION / TIMEOUT 四类失败，
 * 其中 VALIDATION 错误会被 ReActAgent 的防死循环计数器追踪。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolResult {

    private String toolCallId;

    private String toolName;

    private String content;

    private boolean error;

    /**
     * P0-1 新增：错误分类。
     * 为 null 表示成功（error=false），为 VALIDATION 时触发重试计数。
     */
    private ErrorType errorType;

    /**
     * 工具执行错误类型。
     *
     * <p>设计目的：
     * <ul>
     *   <li>VALIDATION — 参数校验失败（含 Schema 不匹配），可回喂 LLM 自我修正</li>
     *   <li>PERMISSION — 权限不足，可能需要人工审批</li>
     *   <li>EXECUTION — 工具运行时异常</li>
     *   <li>TIMEOUT — 执行超时</li>
     * </ul>
     */
    public enum ErrorType {
        VALIDATION,
        PERMISSION,
        EXECUTION,
        TIMEOUT
    }

    public static ToolResult success(String toolCallId, String toolName, String content) {
        return ToolResult.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .content(content)
                .error(false)
                .build();
    }

    public static ToolResult error(String toolCallId, String toolName, String errorMsg) {
        return ToolResult.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .content(errorMsg)
                .error(true)
                .errorType(ErrorType.EXECUTION)
                .build();
    }

    /**
     * P0-1 新增：创建指定错误类型的 ToolResult。
     */
    public static ToolResult error(String toolCallId, String toolName, String errorMsg, ErrorType errorType) {
        return ToolResult.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .content(errorMsg)
                .error(true)
                .errorType(errorType)
                .build();
    }
}

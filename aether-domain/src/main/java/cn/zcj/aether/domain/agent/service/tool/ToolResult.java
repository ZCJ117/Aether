package cn.zcj.aether.domain.agent.service.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果
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
                .build();
    }
}

package cn.zcj.aether.domain.agent.service.runtime;

import java.util.List;
import java.util.Map;

/**
 * 回合消息 — AgentRuntime和ContextManager共享的消息类型
 *
 * 对于 assistant 消息，如果包含 tool_use，toolCalls 字段存储工具调用元数据
 */
public record TurnMessage(
        String role,
        String content,
        String toolCallId,
        String toolName,
        List<Map<String, Object>> toolCalls  // assistant 消息附带的 tool_use 块
) {
    public static TurnMessage user(String content) {
        return new TurnMessage("user", content, null, null, null);
    }

    public static TurnMessage assistant(String content) {
        return new TurnMessage("assistant", content, null, null, null);
    }

    public static TurnMessage assistantWithToolCalls(String text, List<Map<String, Object>> toolCalls) {
        return new TurnMessage("assistant", text, null, null,
                toolCalls != null ? List.copyOf(toolCalls) : null);
    }

    public static TurnMessage toolResult(String toolCallId, String toolName, String content) {
        return new TurnMessage("tool_result", content, toolCallId, toolName, null);
    }

    public boolean isToolUse() {
        return "tool_use".equals(role);
    }

    public boolean isToolResult() {
        return "tool_result".equals(role);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}

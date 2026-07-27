package cn.zcj.aether.domain.agent.service.tool;

/**
 * 工具执行上下文。
 *
 * @param userId     用户标识
 * @param sessionId  会话标识
 * @param toolCallId 工具调用标识
 */
public record ToolContext(String userId, String sessionId, String toolCallId) {}

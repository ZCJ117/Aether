package cn.zcj.aether.domain.agent.service.agent.permission;

import java.util.Map;

/**
 * 挂起的工具调用 —— 人机审批闭环中的一等公民状态。
 * 灵感来源：AgentScope ToolSuspendException（挂起协议）+ RequireUserConfirmEvent（ASK 事件）。
 *
 * <p>当权限引擎返回 ASK_USER 时，工具调用不会被立即执行也不会被丢弃，
 * 而是登记为 SuspendedToolCall 写入 AgentState.permissionContext，
 * 等待用户确认后恢复执行。
 */
public record SuspendedToolCall(
        String toolCallId,
        String toolName,
        Map<String, Object> input,
        String reason,
        SuspendedState state
) {
    /** 返回一个状态变更后的新实例（不可变语义） */
    public SuspendedToolCall withState(SuspendedState newState) {
        return new SuspendedToolCall(toolCallId, toolName, input, reason, newState);
    }

    /**
     * 挂起状态枚举。
     */
    public enum SuspendedState {
        /** 等待用户确认（初始状态） */
        ASKING,
        /** 用户已批准 */
        ALLOWED,
        /** 用户已拒绝 */
        DENIED
    }
}

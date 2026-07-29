package cn.zcj.aether.domain.agent.service.agent.intervention;

import java.util.Map;

/**
 * 拦截上下文 —— 描述被拦截消息的通道信息和执行环境。
 * 灵感来源：AutoGen MessageContext（消息元数据 + 路由信息）。
 *
 * @param channelType  通道类型（DIRECT / BROADCAST）
 * @param agentId      当前 Agent ID
 * @param sessionId    会话 ID
 * @param userId       用户 ID
 * @param edgeType     边类型（SEQUENTIAL / PARALLEL / LOOP / GRAPHFLOW / SUBAGENT）
 * @param workflowName 工作流名称
 * @param metadata     扩展元数据
 */
public record InterventionContext(
        ChannelType channelType,
        String agentId,
        String sessionId,
        String userId,
        String edgeType,
        String workflowName,
        Map<String, Object> metadata
) {
    /** 直连通道：消息发送给特定 Agent，存在 Future 等待方 */
    public boolean isDirect() {
        return channelType == ChannelType.DIRECT;
    }

    /** 广播通道：消息发布给多个 Agent，无单一等待方 */
    public boolean isBroadcast() {
        return channelType == ChannelType.BROADCAST;
    }

    /**
     * 通道类型枚举。
     */
    public enum ChannelType {
        /** 直连通道：有明确的调用方等待结果，拦截异常应回传调用方 */
        DIRECT,
        /** 广播通道：多个订阅者，拦截异常只记日志，不波及其他订阅者 */
        BROADCAST
    }
}

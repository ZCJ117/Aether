package cn.zcj.aether.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 仪表盘统计数据 DTO
 */
@Data
@Builder
public class DashboardStatsDTO {

    /** 活跃智能体数 */
    private int totalAgents;

    /** 活跃会话数 */
    private int activeSessions;

    /** 累计 Token 消耗（暂无追踪表，固定为 0） */
    private long totalTokens;

    /** 预估费用（暂无追踪表，固定为 0） */
    private double totalCost;

    /** 各智能体会话统计 */
    private List<AgentSessionStatDTO> agentStats;

    @Data
    @Builder
    public static class AgentSessionStatDTO {
        private String agentId;
        private String agentName;
        private int sessionCount;
    }
}

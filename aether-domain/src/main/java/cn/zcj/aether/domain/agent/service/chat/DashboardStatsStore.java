package cn.zcj.aether.domain.agent.service.chat;

import java.util.List;

/**
 * P1(1.3): 会话统计快照端口 —— Kafka 消费端聚合的 dashboard_stats 读模型。
 *
 * <p>{@code aether.dashboard.stats-source=kafka} 时 DashboardService 用它替代实时
 * GROUP BY 查询（削峰：统计由消费端异步维护）；端口在 domain，PG 实现在 infrastructure。</p>
 */
public interface DashboardStatsStore {

    /** 全量快照（agentId → 聚合计数），表空返回空列表。 */
    List<AgentStatsSnapshot> loadAll();

    record AgentStatsSnapshot(
            String agentId,
            long sessionsTotal,
            long turnsTotal,
            long toolCallsTotal,
            long toolErrorsTotal,
            long tokensTotal,
            double costUsd) {
    }
}

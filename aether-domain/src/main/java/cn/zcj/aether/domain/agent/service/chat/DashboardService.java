package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘服务 — O2 从 ChatService 拆出。
 *
 * <p>单一职责：仪表盘统计（活跃智能体数 + 活跃会话数 + 各智能体会话分布）。
 * 返回原始数据，由 Controller 层组装 DTO。
 */
@Slf4j
@Service
public class DashboardService {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * 会话持久化仓储。required=false：未配置数据源时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SessionRepository sessionRepository;

    /**
     * P1(1.3): Kafka 聚合读模型。stats-source=kafka 时装配，替代实时 GROUP BY。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DashboardStatsStore dashboardStatsStore;

    /**
     * P1(1.3): 统计来源 live=实时查询（默认）| kafka=读消费端聚合表 dashboard_stats。
     */
    @org.springframework.beans.factory.annotation.Value("${aether.dashboard.stats-source:live}")
    private String statsSource;

    /**
     * 仪表盘统计：活跃智能体数 + 活跃会话数 + 各智能体会话分布。
     */
    public Map<String, Object> getDashboardRawStats() {
        var tables = aiAgentAutoConfigProperties.getTables();
        List<AiAgentConfigTableVO> agentList = new ArrayList<>();
        if (tables != null) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo);
                }
            }
        }
        int totalAgents = agentList.size();
        int activeSessions = sessionRepository != null ? sessionRepository.countActiveSessions() : 0;

        // P1(1.3): kafka 模式读聚合表（削峰）；聚合表缺该 agent 时回退实时计数
        Map<String, Integer> sessionsByAgent;
        if ("kafka".equals(statsSource) && dashboardStatsStore != null) {
            sessionsByAgent = new java.util.HashMap<>();
            for (var s : dashboardStatsStore.loadAll()) {
                sessionsByAgent.put(s.agentId(), (int) s.sessionsTotal());
            }
        } else {
            sessionsByAgent = sessionRepository != null
                    ? sessionRepository.countSessionsByAgent() : Map.of();
        }

        // 以配置中的智能体为准：有会话则显示计数，无会话则显示 0
        List<Map<String, Object>> agentStats = new ArrayList<>();
        for (var vo : agentList) {
            var agent = vo.getAgent();
            int count = sessionsByAgent.getOrDefault(agent.getAgentId(), 0);
            agentStats.add(Map.of(
                    "agentId", agent.getAgentId(),
                    "agentName", agent.getAgentName(),
                    "sessionCount", count));
        }

        return Map.of(
                "totalAgents", totalAgents,
                "activeSessions", activeSessions,
                "agentStats", agentStats);
    }
}

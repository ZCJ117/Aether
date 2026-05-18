package cn.zcj.aether.domain.agent.service.armory;

import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent注册中心
 * 替代原 Spring Bean 动态注册方式 (AbstractArmorySupport.registerBean)
 * 使用 ConcurrentHashMap 持有所有已编译的 AgentGraph
 */
@Slf4j
@Service
public class AgentRegistry {

    private final Map<String, AgentGraph> graphs = new ConcurrentHashMap<>();

    public void register(String agentId, AgentGraph graph) {
        graphs.put(agentId, graph);
        log.info("Agent registered: {}", agentId);
    }

    public AgentGraph get(String agentId) {
        return graphs.get(agentId);
    }

    public Map<String, AgentGraph> getAll() {
        return Map.copyOf(graphs);
    }
}

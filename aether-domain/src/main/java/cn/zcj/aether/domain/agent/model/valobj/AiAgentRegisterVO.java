package cn.zcj.aether.domain.agent.model.valobj;

import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Ai Agent 智能体注册值对象
 * 包含智能体基本信息和编译后的 AgentGraph
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    private String appName;
    private String agentId;
    private String agentName;
    private String agentDesc;

    /**
     * 编译后的 Agent 图 (替代原 Google ADK InMemoryRunner)
     */
    private AgentGraph agentGraph;

}

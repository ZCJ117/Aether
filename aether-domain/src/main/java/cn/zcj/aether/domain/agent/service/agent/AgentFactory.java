package cn.zcj.aether.domain.agent.service.agent;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;

/**
 * Agent 工厂 —— 从 AgentConfig 创建 Agent 实例。
 * 灵感来源：AutoGen register_factory()。
 */
public interface AgentFactory {
    /** 该工厂支持的 Agent 类型 */
    String supportedType();

    /** 创建 Agent 实例 */
    Agent create(AgentConfig config);
}

package cn.zcj.aether.domain.agent.service.armory.node;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.service.armory.AbstractArmorySupport;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.zcj.aether.domain.agent.service.compiler.AgentGraphCompiler;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 编译节点 — 策略树终点 (替代 RunnerNode)
 *
 * 职责:
 *   1. 调用 AgentGraphCompiler 将 YAML 配置编译为 AgentGraph
 *   2. 注册 AgentGraph 到 AgentRegistry
 *
 * 不再依赖 Google ADK 的 InMemoryRunner
 */
@Slf4j
@Service("compilerNode")
public class CompilerNode extends AbstractArmorySupport {

    @Resource
    private AgentGraphCompiler graphCompiler;

    @Resource
    private AgentRegistry agentRegistry;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - CompilerNode");

        AiAgentConfigTableVO config = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Agent agent = config.getAgent();

        String appName = config.getAppName();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();

        AgentGraph graph = graphCompiler.compile(config);
        agentRegistry.register(agentId, graph);

        AiAgentRegisterVO registerVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .agentGraph(graph)
                .build();

        return registerVO;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}

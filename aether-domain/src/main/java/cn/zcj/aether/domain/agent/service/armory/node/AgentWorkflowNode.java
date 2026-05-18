package cn.zcj.aether.domain.agent.service.armory.node;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.zcj.aether.domain.agent.service.armory.AbstractArmorySupport;
import cn.zcj.aether.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.zcj.aether.domain.agent.service.armory.node.workflow.LoopAgentNode;
import cn.zcj.aether.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import cn.zcj.aether.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

//NOTE 15,最后路由到RunnerNode

@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {

    // NOTE AgentWorkflowNode 来路由到不同的工作流节点的，所以这里注入了三个工作流节点，流转下面三个节点
    //  分别是LoopAgentNode、ParallelAgentNode和SequentialAgentNode，后续会根据配置来路由到不同的工作流节点
    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private SequentialAgentNode sequentialAgentNode;

    @Resource
    private CompilerNode compilerNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // NOTE 如果没有配置工作流，或者工作流已经执行完了，就不路由了，直接返回结果，结果值可以放在上下文对象中，供外部调用方获取
            // 设置结果值
            dynamicContext.setCurrentAgentWorkflow(null);
            // 路由下节点
            return router(requestParameter, dynamicContext);
        }

        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));

        // 步骤值增加,每次加1，直到执行完所有的工作流
        dynamicContext.addCurrentStepIndex();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow){
            return compilerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.formType(type);

        if (null == agentTypeEnum){
            throw new RuntimeException("agentWorkflow type is error!");
        }

        String node = agentTypeEnum.getNode();

        // note 根据配置的工作流类型来路由到不同的工作流节点，默认是runnerNode节点，runnerNode节点的职责是执行智能体的输出结果，
        //  执行完后继续路由回AgentWorkflowNode节点，继续执行下一个工作流
        return switch (node){
            case "loopAgentNode" -> loopAgentNode;
            case "parallelAgentNode" -> parallelAgentNode;
            case "sequentialAgentNode" -> sequentialAgentNode;
            default -> compilerNode;
        };
    }

}

package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

// NOTE 14，根据配置创建多个LlmAgent智能体，并注册到DynamicContext 和 agentGroup中。之后路由到AgentWorkflowNode节点
//  为每个 agent 构建 LlmAgent: name, description, model(MySpringAI), instruction, outputKey  存入 dynamicContext.agentGroup[name]
@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        //从DynamicContext中获取ChatModel
        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        //遍历AiAgentConfigTableVO.Module.agents配置
        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription()) // 这个是"描述"，主要是给人看的，告诉人这个智能体是干什么的
                    .model(new MySpringAI(chatModel))
                    .instruction(agentConfig.getInstruction()) // 这个是"介绍", "指令"的意思，主要是给智能体看的，告诉智能体这个智能体的职责是什么，应该怎么做
                    .outputKey(agentConfig.getOutputKey())  //这是对外输出，让下一个智能体能拿到这个智能体的输出结果
                    .build();

            //NOTE 将构建好的智能体注册到上下文对象中，供后续节点使用，注册的key是智能体的名字，这样后续节点就可以通过名字来获取这个智能体
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}

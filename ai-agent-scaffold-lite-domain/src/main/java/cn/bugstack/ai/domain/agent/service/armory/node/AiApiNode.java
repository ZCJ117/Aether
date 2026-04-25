package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

// NOTE 12, AiApiNode节点的作用是根据配置构建OpenAiApi实例，并放入上下文对象中，供后续节点使用，最后路由到ChatModelNode节点

@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        // NOTE 从ArmoryCommandEntity中获取AiAgentConfigTableVO对象，再从AiAgentConfigTableVO调用AiApi
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                // 如果配置了completionsPath和embeddingsPath就用配置的，否则用默认的
                .completionsPath(StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions")
                .embeddingsPath(StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings")
                .build();

        // NOTE 将构建好的OpenAiApi实例放入上下文对象中，供后续节点使用
        dynamicContext.setOpenAiApi(openAiApi);

        // 路由到下一个节点
        // NOTE 这里没有直接调用下一个节点，因为router方法中会调用get方法
        return router(requestParameter, dynamicContext);
        //下面是router源代码，可以看到router方法会调用get方法来获取下一个节点的策略处理器，然后执行策略处理器，如果没有获取到下一个节点的策略处理器，就执行默认的策略处理器
//        public R router(T requestParameter, D dynamicContext) throws Exception {
//            StrategyHandler<T, D, R> strategyHandler = this.get(requestParameter, dynamicContext);
//            return (R)(null != strategyHandler ? strategyHandler.apply(requestParameter, dynamicContext) : this.defaultStrategyHandler.apply(requestParameter, dynamicContext));
//        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}

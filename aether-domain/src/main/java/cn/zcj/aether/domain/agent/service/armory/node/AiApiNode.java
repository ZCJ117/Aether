package cn.zcj.aether.domain.agent.service.armory.node;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.service.armory.AbstractArmorySupport;
import cn.zcj.aether.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * AiApiNode — 解析模型配置并存入 DynamicContext。
 *
 * P0-2 改造：不再硬编码 new OpenAiApi()，而是通过 ModelProviderRegistry 解析
 * 对应的 ModelProvider，并将 ModelConfig + ModelProvider 存入 DynamicContext，
 * 由下游 ChatModelNode 调用 Provider 创建 ChatModel。
 */
@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Resource
    private ModelProviderRegistry modelProviderRegistry;  // P0-2 新增

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        // P0-2 改造：构建 ModelConfig，存入 DynamicContext 供下游 ChatModelNode 使用
        ModelConfig.ModelConfigBuilder builder = ModelConfig.builder()
            .modelId(chatModelConfig.getModel())
            .baseUrl(aiApiConfig.getBaseUrl())
            .apiKey(aiApiConfig.getApiKey())
            .completionsPath(aiApiConfig.getCompletionsPath())
            .embeddingsPath(aiApiConfig.getEmbeddingsPath());

        // P1 容错：从 YAML 读取重试/退避配置（覆盖默认值）
        if (chatModelConfig.getMaxAttempts() != null) {
            builder.maxAttempts(chatModelConfig.getMaxAttempts());
        }
        if (chatModelConfig.getInitialBackoffSeconds() != null) {
            builder.initialBackoff(java.time.Duration.ofSeconds(chatModelConfig.getInitialBackoffSeconds()));
        }
        if (chatModelConfig.getMaxBackoffSeconds() != null) {
            builder.maxBackoff(java.time.Duration.ofSeconds(chatModelConfig.getMaxBackoffSeconds()));
        }
        if (chatModelConfig.getFallbackModels() != null) {
            builder.fallbackModels(chatModelConfig.getFallbackModels());
        }

        ModelConfig modelConfig = builder.build();

        ModelProvider provider = modelProviderRegistry.resolve(chatModelConfig.getModel());
        dynamicContext.setModelProvider(provider);
        dynamicContext.setModelConfig(modelConfig);
        // 保存 aiApiConfig 供 P0-3 Per-Agent 模型覆盖使用
        dynamicContext.setValue("aiApiConfig", aiApiConfig);

        log.info("模型配置已解析: provider={}, model={}, baseUrl={}",
            provider.providerName(), chatModelConfig.getModel(), aiApiConfig.getBaseUrl());

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}

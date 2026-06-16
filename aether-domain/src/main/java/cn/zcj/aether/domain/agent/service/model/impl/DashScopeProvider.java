package cn.zcj.aether.domain.agent.service.model.impl;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DashScope Provider。
 * DashScope 提供 OpenAI 兼容接口。
 */
@Slf4j
@Component
public class DashScopeProvider implements ModelProvider {

    @Override
    public String providerName() { return "dashscope"; }

    @Override
    public boolean supports(String modelId) {
        String lower = modelId != null ? modelId.toLowerCase() : "";
        return lower.startsWith("qwen-")
            || lower.contains("dashscope")
            || lower.startsWith("dashscope.");
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .completionsPath(StringUtils.isNotBlank(config.getCompletionsPath())
                ? config.getCompletionsPath() : "compatible-mode/v1/chat/completions")
            .build();

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("DashScopeProvider 创建 ChatModel: model={}, baseUrl={}", config.getModelId(), config.getBaseUrl());
        return chatModel;
    }

    @Override
    public String defaultCompletionsPath() { return "compatible-mode/v1/chat/completions"; }
}

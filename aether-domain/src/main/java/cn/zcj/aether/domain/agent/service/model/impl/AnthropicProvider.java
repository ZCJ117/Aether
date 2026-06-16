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
 * Anthropic Provider。
 * 通过 Anthropic 的 OpenAI 兼容端点接入。
 * 注意：若 Spring AI 后续提供原生 Anthropic 支持，可替换此实现。
 */
@Slf4j
@Component
public class AnthropicProvider implements ModelProvider {

    @Override
    public String providerName() { return "anthropic"; }

    @Override
    public boolean supports(String modelId) {
        // 匹配 Anthropic 模型前缀
        String lower = modelId != null ? modelId.toLowerCase() : "";
        return lower.startsWith("claude-")
            || lower.startsWith("anthropic.")
            || lower.contains("anthropic");
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .completionsPath(StringUtils.isNotBlank(config.getCompletionsPath())
                ? config.getCompletionsPath() : "v1/messages")
            .build();

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("AnthropicProvider 创建 ChatModel: model={}, baseUrl={}", config.getModelId(), config.getBaseUrl());
        return chatModel;
    }
}

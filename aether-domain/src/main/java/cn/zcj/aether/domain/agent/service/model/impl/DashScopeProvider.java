package cn.zcj.aether.domain.agent.service.model.impl;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import lombok.extern.slf4j.Slf4j;
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

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public DashScopeProvider(
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.connect-timeout-ms:30000}") int connectTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.read-timeout-ms:120000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public int connectTimeoutMs() { return connectTimeoutMs; }
    @Override
    public int readTimeoutMs() { return readTimeoutMs; }

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
        // O12: 统一走 buildOpenAiApi
        OpenAiApi openAiApi = buildOpenAiApi(config);

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("DashScopeProvider 创建 ChatModel: model={}, baseUrl={} (connect={}ms, read={}ms)",
                config.getModelId(), config.getBaseUrl(), connectTimeoutMs, readTimeoutMs);
        return chatModel;
    }

    @Override
    public String defaultCompletionsPath() { return "compatible-mode/v1/chat/completions"; }
}

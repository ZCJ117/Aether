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
 * Anthropic Provider。
 * 通过 Anthropic 的 OpenAI 兼容端点接入。
 * 注意：若 Spring AI 后续提供原生 Anthropic 支持，可替换此实现。
 */
@Slf4j
@Component
public class AnthropicProvider implements ModelProvider {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public AnthropicProvider(
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
    public String defaultCompletionsPath() { return "v1/messages"; }

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
        // O12: 统一走 buildOpenAiApi（RestClient/WebClient 双通道显式超时）
        OpenAiApi openAiApi = buildOpenAiApi(config);

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("AnthropicProvider 创建 ChatModel: model={}, baseUrl={} (connect={}ms, read={}ms)",
                config.getModelId(), config.getBaseUrl(), connectTimeoutMs, readTimeoutMs);
        return chatModel;
    }
}

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
 * OpenAI 及兼容协议 Provider。
 * 支持：OpenAI 官方 API、DeepSeek、通义千问 DashScope（OpenAI 兼容模式）、
 *       以及其他任何兼容 OpenAI Chat Completions 协议的端点。
 *
 * <p>HTTP 超时统一由 {@link ModelProvider#buildOpenAiApi} 提供（连接 30s / 读取 2min），
 * 防止模型服务端不响应时"思考中"无限卡死。</p>
 */
@Slf4j
@Component
public class OpenAIProvider implements ModelProvider {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public OpenAIProvider(
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
    public String providerName() { return "openai"; }

    @Override
    public boolean supports(String modelId) {
        return true; // 兜底 Provider
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        OpenAiApi openAiApi = buildOpenAiApi(config);   // 既有调用不变，现经超时钩子

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("OpenAIProvider 创建 ChatModel: model={}, baseUrl={} (connect={}ms, read={}ms)",
                config.getModelId(), config.getBaseUrl(), connectTimeoutMs, readTimeoutMs);
        return chatModel;
    }
}

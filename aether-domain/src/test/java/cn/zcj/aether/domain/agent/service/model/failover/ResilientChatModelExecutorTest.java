package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientChatModelExecutorTest {

    private ChatModel chatModel;
    private ModelProvider provider;
    private ModelProviderRegistry registry;
    private ModelErrorClassifier classifier;

    private static ChatResponse ok() {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
    }

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        provider = mock(ModelProvider.class);
        registry = mock(ModelProviderRegistry.class);
        classifier = mock(ModelErrorClassifier.class);
        when(provider.providerName()).thenReturn("openai");
        when(provider.createChatModel(any())).thenReturn(chatModel);
    }

    private ResilientChatModelExecutor build(ModelConfig cfg, List<ModelRoute> chain) {
        return new ResilientChatModelExecutor(chatModel, cfg, provider, registry, classifier, chain);
    }

    @Test
    void successNoRetry() {
        when(chatModel.call(any(Prompt.class))).thenReturn(ok());
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();

        ChatResponse resp = build(cfg, List.of()).call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getText());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void rateLimitAdaptiveBackoffThenSuccess() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("rate limited"))
                .thenReturn(ok());
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.RATE_LIMIT, 429, "openai", "gpt-4o", "rate"));
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();

        ResilientChatModelExecutor executor = build(cfg, List.of());
        executor.setBackoffWaiter(sec -> { }); // 测试中跳过真实退避等待
        ChatResponse resp = executor.call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getText());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void authTransientRotatesCredentialThenSuccess() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("401 unauthorized"))
                .thenReturn(ok());
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.AUTH_TRANSIENT, 401, "openai", "gpt-4o", "401"));

        // 领域层不依赖 aether-infrastructure，用 CredentialPool 双实现验证轮换流程。
        // RotatingCredentialPool 的轮换正确性由 aether-infrastructure 模块单测覆盖。
        CredentialPool pool = (current, providerName) -> Optional.of(
                ModelConfig.builder().modelId("gpt-4o").apiKey("key2").baseUrl("https://b").build());
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").baseUrl("https://a").build();

        ResilientChatModelExecutor executor = build(cfg, List.of());
        executor.setCredentialPool(pool);
        ChatResponse resp = executor.call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getText());
        ArgumentCaptor<ModelConfig> cap = ArgumentCaptor.forClass(ModelConfig.class);
        // 凭据轮换会重建 ChatModel：当前模型配置在构造时经 chatModel 参数注入，
        // 仅 tryRotateCredential 内部以轮换后配置调用一次 createChatModel。
        verify(provider, times(1)).createChatModel(cap.capture());
        assertEquals("key2", cap.getValue().getApiKey());
    }

    @Test
    void exhaustedBackoffThenFallbackThenTerminate() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("server down"));
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.SERVER_ERROR, 500, "openai", "gpt-4o", "server"));
        // fallback 路由指向另一个 provider，其 ChatModel 也抛错
        ModelProvider fbProvider = mock(ModelProvider.class);
        when(fbProvider.providerName()).thenReturn("anthropic");
        ChatModel fbModel = mock(ChatModel.class);
        when(fbModel.call(any(Prompt.class))).thenThrow(new RuntimeException("also down"));
        when(fbProvider.createChatModel(any())).thenReturn(fbModel);
        when(registry.resolve("claude-sonnet")).thenReturn(fbProvider);

        ModelRoute fb = ModelRoute.builder().modelId("claude-sonnet").provider("anthropic").build();
        // maxAttempts=1：抖动退避 1 次 → fallback 1 次 → 终止
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").maxAttempts(1).build();

        ResilientChatModelExecutor executor = build(cfg, List.of(fb));
        executor.setBackoffWaiter(sec -> { }); // 测试中跳过真实退避等待

        assertThrows(ResilientChatModelExecutor.ResilientCallException.class,
                () -> executor.call(new Prompt("hi")));
    }

    @Test
    void contextOverflowCompressesThenSuccess() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ok());
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW, null, "anthropic", "claude-sonnet", "context"));
        ModelConfig cfg = ModelConfig.builder().modelId("claude-sonnet").apiKey("key1").build();

        ResilientChatModelExecutor executor = build(cfg, List.of());
        // compressCallback 返回 true：压缩后重试
        executor.setCompressCallback((state, reason) -> true);
        executor.setBackoffWaiter(sec -> { });

        ChatResponse resp = executor.call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getText());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }
}

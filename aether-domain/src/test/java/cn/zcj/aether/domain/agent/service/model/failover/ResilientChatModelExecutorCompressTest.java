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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VUL-04 / T4-4 + T4-6: CONTEXT_COMPRESSION 恢复分支的同步路径行为。
 *
 * <ol>
 *   <li>上下文溢出后压缩回调真的被调用，且 reason 正确下发（T4-4）；</li>
 *   <li>压缩配额上界恒为 {@code MAX_COMPRESSION_ATTEMPTS=2}，之后退化 fallback/终止（T4-6）。</li>
 * </ol>
 *
 * <p>流式路径（T4-8）见 {@link ResilientChatModelExecutorStreamCompressTest}。</p>
 */
class ResilientChatModelExecutorCompressTest {

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
        when(provider.providerName()).thenReturn("anthropic");
        when(provider.createChatModel(any())).thenReturn(chatModel);
    }

    private ResilientChatModelExecutor build(ModelConfig cfg, List<ModelRoute> chain) {
        ResilientChatModelExecutor executor =
                new ResilientChatModelExecutor(chatModel, cfg, provider, registry, classifier, chain);
        executor.setBackoffWaiter(sec -> { }); // 测试中跳过真实退避等待
        return executor;
    }

    private void overflow() {
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW, null,
                        "anthropic", "claude-sonnet", "context too long"));
    }

    private static ModelConfig cfg() {
        return ModelConfig.builder().modelId("claude-sonnet").apiKey("key1").build();
    }

    @Test
    void syncOverflowCompressesWithReasonThenSucceeds() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ok());
        overflow();
        ResilientChatModelExecutor executor = build(cfg(), List.of());
        AtomicInteger compressCalls = new AtomicInteger();
        AtomicReference<FailoverReason> seenReason = new AtomicReference<>();
        executor.setCompressCallback((state, reason) -> {
            compressCalls.incrementAndGet();
            seenReason.set(reason);
            return true;
        });

        ChatResponse resp = executor.call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getText());
        verify(chatModel, times(2)).call(any(Prompt.class));
        assertEquals(1, compressCalls.get(), "上下文溢出必须真正触发压缩回调");
        assertEquals(FailoverReason.CONTEXT_OVERFLOW, seenReason.get(),
                "压缩回调应收到分类后的失败原因");
    }

    @Test
    void compressionQuotaBoundedAtTwoThenTerminates() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("context too long"));
        overflow();
        // 无 fallback 链：压缩配额耗尽后只能终止
        ResilientChatModelExecutor executor = build(cfg(), List.of());
        AtomicInteger compressCalls = new AtomicInteger();
        executor.setCompressCallback((state, reason) -> {
            compressCalls.incrementAndGet();
            return true;
        });

        assertThrows(ResilientChatModelExecutor.ResilientCallException.class,
                () -> executor.call(new Prompt("hi")));

        assertEquals(2, compressCalls.get(),
                "MAX_COMPRESSION_ATTEMPTS=2：压缩尝试次数必须仍然有界");
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    // ── 压缩后重建待重试请求 ──

    @Test
    void successfulCompressionRebuildsRetriedPrompt() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ok());
        overflow();
        ResilientChatModelExecutor executor = build(cfg(), List.of());
        executor.setCompressCallback((state, reason) -> true);
        Prompt rebuilt = new Prompt("压缩后的请求");
        executor.setPromptRebuilder(() -> rebuilt);

        executor.call(new Prompt("原始超长请求"));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());
        assertSame(rebuilt, captor.getAllValues().get(1),
                "压缩真实改变上下文后，重试必须发出重建的新请求（否则请求一字未短，必然再次溢出）");
    }

    @Test
    void ineffectiveCompressionDoesNotRebuildPrompt() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ok());
        overflow();
        ResilientChatModelExecutor executor = build(cfg(), List.of());
        executor.setCompressCallback((state, reason) -> false);   // 什么也没改
        AtomicInteger rebuilds = new AtomicInteger();
        executor.setPromptRebuilder(() -> {
            rebuilds.incrementAndGet();
            return new Prompt("不该被用到的请求");
        });
        Prompt original = new Prompt("原始请求");

        executor.call(original);

        assertEquals(0, rebuilds.get(), "上下文未改变时不应重建请求");
        verify(chatModel, times(2)).call(same(original));
    }

    @Test
    void rebuilderFailureFallsBackToOriginalPromptWithoutThrowing() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ok());
        overflow();
        ResilientChatModelExecutor executor = build(cfg(), List.of());
        executor.setCompressCallback((state, reason) -> true);
        executor.setPromptRebuilder(() -> {
            throw new IllegalStateException("重建炸了");
        });
        Prompt original = new Prompt("原始请求");

        ChatResponse resp = executor.call(original);   // 热路径：不得抛出

        assertEquals("ok", resp.getResult().getOutput().getText());
        verify(chatModel, times(2)).call(same(original));
    }

    @Test
    void missingRebuilderReusesOriginalPrompt() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ok());
        overflow();
        ResilientChatModelExecutor executor = build(cfg(), List.of());
        executor.setCompressCallback((state, reason) -> true);
        // 未注入 rebuilder（未装配宿主时的既有行为）：重试复用原请求
        Prompt original = new Prompt("原始请求");

        executor.call(original);

        verify(chatModel, times(2)).call(same(original));
    }
}

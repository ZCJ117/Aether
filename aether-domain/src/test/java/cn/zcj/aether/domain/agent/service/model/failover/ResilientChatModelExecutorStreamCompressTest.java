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
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * VUL-04 / T4-8: 流式路径的 CONTEXT_COMPRESSION 恢复分支。
 *
 * <p>流式路径与同步路径共用同一份分支结构（{@code handleStreamError}），
 * 本类验证压缩回调在两个方向上都正确：
 * <ol>
 *   <li>首帧前溢出 → 压缩后重建流并最终成功；</li>
 *   <li>首帧后溢出 → 不进入恢复分支、不压缩、不重放（首帧不变量的回归锁定）。</li>
 * </ol>
 */
class ResilientChatModelExecutorStreamCompressTest {

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
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW, null,
                        "anthropic", "claude-sonnet", "context too long"));
    }

    private ResilientChatModelExecutor build() {
        ResilientChatModelExecutor executor = new ResilientChatModelExecutor(
                chatModel,
                ModelConfig.builder().modelId("claude-sonnet").apiKey("key1").build(),
                provider, registry, classifier, List.of());
        executor.setBackoffWaiter(sec -> { });
        return executor;
    }

    @Test
    void streamOverflowBeforeFirstFrameCompressesThenRebuilds() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("context too long")))
                .thenReturn(Flux.just(ok(), ok()));
        ResilientChatModelExecutor executor = build();
        AtomicInteger compressCalls = new AtomicInteger();
        executor.setCompressCallback((state, reason) -> {
            compressCalls.incrementAndGet();
            return true;
        });

        List<ChatResponse> frames = executor.stream(new Prompt("hi"))
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(2, frames.size(), "首帧前溢出：压缩后应重建流并流出全部帧");
        assertEquals(1, compressCalls.get(), "首帧前溢出必须触发压缩回调");
        verify(chatModel, times(2)).stream(any(Prompt.class));
    }

    @Test
    void streamOverflowAfterFirstFrameDoesNotCompressOrReplay() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(ok()).concatWith(Flux.error(new RuntimeException("context too long"))));
        ResilientChatModelExecutor executor = build();
        AtomicInteger compressCalls = new AtomicInteger();
        executor.setCompressCallback((state, reason) -> {
            compressCalls.incrementAndGet();
            return true;
        });

        List<ChatResponse> received = new ArrayList<>();
        assertThrows(RuntimeException.class, () -> executor.stream(new Prompt("hi"))
                .doOnNext(received::add)
                .collectList().block(Duration.ofSeconds(5)));

        assertEquals(1, received.size(), "首帧后失败：已下发的帧应保留，不得重放");
        assertEquals(0, compressCalls.get(), "首帧后失败不进入恢复分支，压缩回调不应被调用");
        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void streamRebuildsPromptAfterCompression() {
        // 生产主循环走 chatModel.stream(...)（true-streaming 默认开），故流式路径才是重建请求的实战路径
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("context too long")))
                .thenReturn(Flux.just(ok()));
        ResilientChatModelExecutor executor = build();
        executor.setCompressCallback((state, reason) -> true);
        Prompt rebuilt = new Prompt("压缩后的请求");
        executor.setPromptRebuilder(() -> rebuilt);

        List<ChatResponse> frames = executor.stream(new Prompt("原始超长请求"))
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(1, frames.size(), "压缩后重建的流应正常流出帧");
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).stream(captor.capture());
        assertSame(rebuilt, captor.getAllValues().get(1),
                "压缩真实改变上下文后，重建流必须使用压缩后的请求");
    }

    @Test
    void streamKeepsOriginalPromptWhenCompressionChangedNothing() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("context too long")))
                .thenReturn(Flux.just(ok()));
        ResilientChatModelExecutor executor = build();
        executor.setCompressCallback((state, reason) -> false);
        AtomicInteger rebuilds = new AtomicInteger();
        executor.setPromptRebuilder(() -> {
            rebuilds.incrementAndGet();
            return new Prompt("不该被用到的请求");
        });
        Prompt original = new Prompt("原始请求");

        executor.stream(original).collectList().block(Duration.ofSeconds(5));

        assertEquals(0, rebuilds.get(), "上下文未改变时不应重建请求");
        verify(chatModel, times(2)).stream(same(original));
    }
}

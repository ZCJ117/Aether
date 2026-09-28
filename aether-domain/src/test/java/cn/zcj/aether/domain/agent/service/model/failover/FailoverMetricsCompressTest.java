package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VUL-04 / T4-7: 上下文压缩恢复分支的四种出口分别可归因。
 *
 * <p>修复前该分支只打 {@code log.debug}，生产日志级别下完全不可见；
 * 本类锁死 {@code aether.failover.compress{result=...}} 的四个 tag，
 * 使"回调缺失 / 无变化 / 成功 / 异常"在指标上可区分。</p>
 */
class FailoverMetricsCompressTest {

    private ChatModel chatModel;
    private ModelProvider provider;
    private ModelProviderRegistry registry;
    private ModelErrorClassifier classifier;
    private SimpleMeterRegistry meterRegistry;
    private FailoverMetrics metrics;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        provider = mock(ModelProvider.class);
        registry = mock(ModelProviderRegistry.class);
        classifier = mock(ModelErrorClassifier.class);
        when(provider.providerName()).thenReturn("anthropic");
        when(provider.createChatModel(any())).thenReturn(chatModel);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new FailoverMetrics(meterRegistry);
    }

    @Test
    void recordsAllFourCompressOutcomes() {
        metrics.recordCompressResult(FailoverMetrics.CompressOutcome.SUCCESS);
        metrics.recordCompressResult(FailoverMetrics.CompressOutcome.INEFFECTIVE);
        metrics.recordCompressResult(FailoverMetrics.CompressOutcome.NOOP);
        metrics.recordCompressResult(FailoverMetrics.CompressOutcome.ERROR);

        assertEquals(1.0, compressCount("success"));
        assertEquals(1.0, compressCount("ineffective"));
        assertEquals(1.0, compressCount("noop"));
        assertEquals(1.0, compressCount("error"));
    }

    @Test
    void nullRegistryIsNoOp() {
        FailoverMetrics noop = new FailoverMetrics(null);
        assertDoesNotThrow(() -> noop.recordCompressResult(FailoverMetrics.CompressOutcome.SUCCESS));
    }

    // ── 四出口经 tryCompress 的实际打点 ──

    @Test
    void missingCallbackRecordsNoop() {
        overflowOnce();
        // 未 setCompressCallback：配置缺陷信号，不得抛异常，且首次压缩重试后即恢复
        run(executor -> { });

        assertEquals(1.0, compressCount("noop"));
    }

    @Test
    void callbackChangedContextRecordsSuccess() {
        overflowOnce();
        run(executor -> executor.setCompressCallback((state, reason) -> true));

        assertEquals(1.0, compressCount("success"));
    }

    @Test
    void ineffectiveCallbackRecordsIneffective() {
        overflowOnce();
        run(executor -> executor.setCompressCallback((state, reason) -> false));

        assertEquals(1.0, compressCount("ineffective"));
    }

    @Test
    void throwingCallbackRecordsError() {
        overflowOnce();
        run(executor -> executor.setCompressCallback((state, reason) -> {
            throw new IllegalStateException("压缩回调炸了");
        }));

        assertEquals(1.0, compressCount("error"));
    }

    @Test
    void compressQuotaExhaustionRecordsNoopTwice() {
        // 回调缺失 + 持续溢出：两次压缩配额各记一次 noop，之后退化终止（不抛非预期异常）
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("context too long"));
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW, null,
                        "anthropic", "claude-sonnet", "context too long"));

        ResilientChatModelExecutor executor = build();
        executor.setFailoverMetrics(metrics);
        assertThrows(ResilientChatModelExecutor.ResilientCallException.class,
                () -> executor.call(new Prompt("hi")));

        assertEquals(2.0, compressCount("noop"));
    }

    // ── helpers ──

    /** 第一次调用溢出、第二次成功：保证一次压缩动作后恢复。 */
    private void overflowOnce() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("context too long"))
                .thenReturn(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("ok"))))
                        .build());
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW, null,
                        "anthropic", "claude-sonnet", "context too long"));
    }

    private void run(java.util.function.Consumer<ResilientChatModelExecutor> setup) {
        ResilientChatModelExecutor executor = build();
        executor.setFailoverMetrics(metrics);
        setup.accept(executor);
        executor.call(new Prompt("hi"));
    }

    private ResilientChatModelExecutor build() {
        ResilientChatModelExecutor executor = new ResilientChatModelExecutor(
                chatModel,
                ModelConfig.builder().modelId("claude-sonnet").apiKey("key1").build(),
                provider, registry, classifier, List.of());
        executor.setBackoffWaiter(sec -> { });
        return executor;
    }

    /** 计数器不存在与计数为 0 是两回事——本测试类的全部意义就在于区分四种出口是否真的被打点。 */
    private double compressCount(String result) {
        Counter c = meterRegistry.find(FailoverMetrics.COMPRESS_METRIC)
                .tag("result", result).counter();
        assertNotNull(c, "指标 " + FailoverMetrics.COMPRESS_METRIC
                + "{result=" + result + "} 未被注册，无法区分「未打点」与「计数为 0」");
        return c.count();
    }
}

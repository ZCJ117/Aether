package cn.zcj.aether.observability;

import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.runtime.ModelCallCache;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D4/F2-5 补齐：{@code callWithStreamCached} 的<b>缓存命中</b>路径同样产出 model span。
 *
 * <p>spec §7.1.6 把 {@code callWithStreamCached} 列为 model call 的四个入口之一，但命中时它
 * 直接返回、不落到 {@code callWithStream}／{@code callWithStreamAsync}——那两处的埋点全都
 * 不会执行，命中分支在 trace 里完全不可见。本用例锁住"命中也要有 span"这一修复。</p>
 *
 * <p>用真实 {@link SdkTracerProvider} + {@link SimpleSpanProcessor} + 自建
 * {@link RecordingSpanExporter}（{@code opentelemetry-sdk-testing} 未声明，不引入以守 §5）。</p>
 */
class ModelCacheHitSpanTest {

    private static final String MODEL = "claude-sonnet";
    private static final List<Message> MESSAGES = List.of(new UserMessage("你好"));

    private static SdkTracerProvider provider;
    private static RecordingSpanExporter exporter;

    @BeforeAll
    static void installRecordingSdk() {
        exporter = new RecordingSpanExporter();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        AgentTracer.setTracer(provider.get("aether-agent", "1.0.0"));
    }

    @AfterAll
    static void uninstallRecordingSdk() {
        provider.shutdown();
        // 静态状态必须清干净：surefire 复用 JVM，残留会污染同 JVM 的其它测试
        AgentTracer.setTracer(null);
    }

    @AfterEach
    void clearSpans() {
        exporter.reset();
    }

    /** 同步入口：第二次调用命中缓存，仍应留 span，且标出 cache.hit / cost=0。 */
    @Test
    @DisplayName("F2-5 callWithStreamCached 命中缓存时产出 model span")
    void syncCacheHitProducesModelSpan() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel chatModel = countingChatModel(modelCalls);
        ModelInvoker invoker = newInvoker();

        invoker.callWithStreamCached(chatModel, MESSAGES, "指令", MODEL, true, 60);
        invoker.callWithStreamCached(chatModel, MESSAGES, "指令", MODEL, true, 60);

        assertEquals(1, modelCalls.get(), "第二次应命中缓存，不得再打模型");
        assertMissAndHitSpans();
    }

    /** 异步入口（true-streaming=false 的灰度回滚路径）：命中也应留 span。 */
    @Test
    @DisplayName("F2-5 callWithStreamCachedAsync 命中缓存时产出 model span")
    void asyncCacheHitProducesModelSpan() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel chatModel = countingChatModel(modelCalls);
        ModelInvoker invoker = newInvoker();

        invoker.callWithStreamCachedAsync(chatModel, MESSAGES, "指令", MODEL, true, 60).block();
        invoker.callWithStreamCachedAsync(chatModel, MESSAGES, "指令", MODEL, true, 60).block();

        assertEquals(1, modelCalls.get(), "第二次应命中缓存，不得再打模型");
        assertMissAndHitSpans();
    }

    // ── helpers ──

    /** 未命中一次 + 命中一次 → 恰好两个 model span，且只有后者带 cache.hit。 */
    private static void assertMissAndHitSpans() {
        List<SpanData> spans = exporter.named("agent.model.call");
        assertEquals(2, spans.size(),
                "未命中与命中各应留下一个 model span；实际: " + exporter.spans().stream()
                        .map(SpanData::getName).toList());

        SpanData miss = spans.get(0);
        SpanData hit = spans.get(1);

        assertNull(miss.getAttributes().get(AttributeKey.booleanKey("cache.hit")),
                "未命中的 span 不应带 cache.hit");
        assertEquals(Boolean.TRUE, hit.getAttributes().get(AttributeKey.booleanKey("cache.hit")),
                "命中 span 必须带 cache.hit=true（错过它就没法按 trace 找缓存命中）");
        assertEquals(0.0, hit.getAttributes().get(AttributeKey.doubleKey("cost.usd")), 1e-9,
                "命中不发请求、不计费，成本应为 0");
        assertEquals(MODEL, hit.getAttributes().get(AttributeKey.stringKey("model.name")));
        assertEquals(StatusCode.OK, hit.getStatus().getStatusCode(),
                "只有非失败结果才入缓存，命中必然是 OK");
    }

    /** 打模型次数即"未命中次数"——命中时不应再调用 {@code stream()}。 */
    private static ChatModel countingChatModel(AtomicInteger modelCalls) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv -> {
            modelCalls.incrementAndGet();
            return Flux.just(ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage("来自模型的回答"))))
                    .build());
        });
        return chatModel;
    }

    /** 真 ModelInvoker，只补上 Spring 注入的缓存与定价表。 */
    private static ModelInvoker newInvoker() throws Exception {
        ModelInvoker invoker = new ModelInvoker();
        setField(invoker, "modelCallCache", new ModelCallCache());
        setField(invoker, "pricingRegistry", new ModelPricingRegistry());
        return invoker;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

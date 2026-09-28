package cn.zcj.aether.observability;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import cn.zcj.aether.domain.agent.service.tool.validation.ToolInputValidator;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D4/F2-4~F2-6 端到端：一次含工具调用的 ReAct 会话应产出三层 span —— T2-7。
 *
 * <p>全程真对象，只有模型（{@link ChatModel}）是替身：真 {@link ReActAgent#execute}
 * → 真 {@code queryLoop}（F2-4 turn span）→ 真 {@link ModelInvoker} 真流式路径（F2-5 model span）
 * → 真 {@link ToolExecutor#executeBatch}（F2-6 tool span）→ 自建
 * {@link RecordingSpanExporter} 断言导出结果。</p>
 *
 * <p><b>为什么不用 {@code @SpringBootTest}</b>：本用例要断言的是"埋点在真实调用链上被触发"，
 * 直接驱动真对象比拉起整个上下文更精确、也更少受外部依赖（DB/PG）影响；
 * Spring 装配本身由 {@code OtelDisabledTest}（T2-8）覆盖。</p>
 *
 * <p><b>为什么类名以 {@code Test} 而不是 {@code IT} 结尾</b>：{@code aether-app/pom.xml} 的
 * surefire 只 include {@code **}{@code /*Test.java}，叫 {@code *IT} 会被默认测试集<b>静默跳过</b>。
 * 本用例不依赖任何外部基础设施（模型是替身、其余全为真对象，秒级完成），理应随每次构建执行；
 * 只有需要 PG/Docker 的用例才留在 {@code *IT}。</p>
 */
class ToolCallSpanTest {

    private static final String SESSION = "span-e2e-session";
    private static final String MODEL = "claude-sonnet";

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

    @Test
    @DisplayName("T2-7 一次含工具调用的 ReAct 会话产出 turn/model/tool 三层 span")
    void toolCallingConversationProducesAllThreeSpanKinds() throws Exception {
        exporter.reset();

        // ── 替身：模型第 1 轮要求调用工具，第 2 轮给出最终答复 ──
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv ->
                modelCalls.getAndIncrement() == 0
                        ? Flux.just(toolCallResponse())
                        : Flux.just(textResponse("已完成")));

        // ── 真 ModelInvoker（注入定价表，使 cost.usd 有真值） ──
        ModelInvoker modelInvoker = new ModelInvoker();
        setField(modelInvoker, "pricingRegistry", new ModelPricingRegistry());

        // ── 真 ToolExecutor + 真 ToolRegistry（含一个只读 echo 工具） ──
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        ToolExecutor toolExecutor = new ToolExecutor(new ToolInputValidator());
        setField(toolExecutor, "toolRegistry", registry);

        // ── 真 ReActAgent，仅工具与模型之外的事件/检查点/预算为空 ──
        ReActAgent agent = new ReActAgent(
                AgentConfig.builder()
                        .name("span-e2e-agent")
                        .instruction("测试指令")
                        .modelRef(MODEL)
                        .cancelToken(new CancelToken())
                        .build(),
                chatModel, modelInvoker, toolExecutor, realContextManager(),
                null, null, null, null, null, null);

        agent.execute(new RuntimeContext("user1", SESSION, null, null, null, null, null))
                .toList().blockingGet();

        // ── 断言 1：三层 span 齐备 ──
        SpanData turn = exporter.find("agent.turn").orElseThrow(
                () -> new AssertionError("缺少 agent.turn span；实际导出: " + names()));
        SpanData model = exporter.find("agent.model.call").orElseThrow(
                () -> new AssertionError("缺少 agent.model.call span；实际导出: " + names()));
        SpanData tool = exporter.find("agent.tool.call").orElseThrow(
                () -> new AssertionError("缺少 agent.tool.call span；实际导出: " + names()));

        // ── 断言 2：同一 trace，且 model/tool 均挂在 turn 之下 ──
        String turnSpanId = turn.getSpanId();
        assertEquals(turn.getTraceId(), model.getTraceId(), "model span 应与 turn 同 trace");
        assertEquals(turn.getTraceId(), tool.getTraceId(), "tool span 应与 turn 同 trace");
        assertEquals(turnSpanId, model.getParentSpanId(), "model span 的父应是 turn span");
        assertEquals(turnSpanId, tool.getParentSpanId(), "tool span 的父应是 turn span");

        // ── 断言 3：模型确实调了两轮（工具调用 + 收尾），说明这条链路真被走通 ──
        assertEquals(2, modelCalls.get(), "应有 2 轮模型调用（工具轮 + 收尾轮）");
        assertTrue(model.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("model.name")).equals(MODEL));

        // ── 断言 4：工具 span 记下了真实执行结果 ──
        assertEquals("echo", tool.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("tool.name")));
        assertEquals(Boolean.TRUE, tool.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.booleanKey("tool.success")),
                "echo 工具执行成功，tool.success 应为 true");
    }

    /**
     * D4/F2-4 异常路径：轮次炸掉时 {@code agent.turn} 必须留下 {@code ERROR} 状态。
     *
     * <p>spec §7.1.6 要求"异常路径在 {@code turnScope.close()} 前 {@code setStatus(ERROR)}"。
     * 没有本用例时该分支不可测——失败的一轮与正常一轮在 trace 里长得一模一样，
     * "按 trace 定位失败轮次"就落空。</p>
     *
     * <p>制造失败的手法：{@link ModelInvoker} 用替身并在真流式入口直接抛异常。
     * 真实链路里模型/工具错误都会被吞成 {@code ModelCallResult.error} → {@code break}（不抛），
     * 故这里必须从更外层注入异常才能触达 catch。</p>
     */
    @Test
    @DisplayName("T2-7 异常路径：轮次失败时 agent.turn 记为 ERROR（且只结束一次）")
    void failedTurnIsMarkedError() throws Exception {
        exporter.reset();

        ModelInvoker throwingInvoker = mock(ModelInvoker.class);
        when(throwingInvoker.isTrueStreaming()).thenReturn(true);
        when(throwingInvoker.getCallTimeoutMs()).thenReturn(5_000L);
        when(throwingInvoker.callWithStreamingAsync(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("模拟模型链路故障"));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        ToolExecutor toolExecutor = new ToolExecutor(new ToolInputValidator());
        setField(toolExecutor, "toolRegistry", registry);

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder()
                        .name("span-e2e-agent")
                        .instruction("测试指令")
                        .modelRef(MODEL)
                        .cancelToken(new CancelToken())
                        .build(),
                mock(ChatModel.class), throwingInvoker, toolExecutor, realContextManager(),
                null, null, null, null, null, null);

        assertThrows(Throwable.class, () -> agent
                        .execute(new RuntimeContext("user1", SESSION, null, null, null, null, null))
                        .toList().blockingGet(),
                "模型链路抛异常应冒泡出 execute，从而触达 queryLoop 的 turn catch");

        SpanData turn = exporter.find("agent.turn").orElseThrow(
                () -> new AssertionError("缺少 agent.turn span；实际导出: " + names()));
        assertEquals(StatusCode.ERROR, turn.getStatus().getStatusCode(),
                "失败轮次的 turn span 必须是 ERROR，否则 trace 里无法区分失败轮次");
        assertEquals("模拟模型链路故障", turn.getStatus().getDescription(),
                "ERROR 描述应带上失败原因");
        assertEquals("模拟模型链路故障", turn.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("turn.error")),
                "turn.error 属性应记在当轮 turn span 上");
        assertEquals(1, exporter.named("agent.turn").size(),
                "turn span 只能结束一次（setSpanError 不 end，收口由 close() 负责）");
    }

    private String names() {
        return exporter.spans().stream().map(SpanData::getName).toList().toString();
    }

    // ── helpers ──

    private static ChatResponse toolCallResponse() {
        AssistantMessage message = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "echo", "{\"text\":\"hi\"}")));
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    /** 只读 echo 工具：只读可跳过写前快照，避免依赖 CheckpointCollector。 */
    private static final class EchoTool implements Tool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显输入文本";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public boolean isConcurrencySafe() {
            return true;
        }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext context) {
            return ToolResult.success(context.toolCallId(), "echo", "echo:" + input.get("text"));
        }
    }

    /** 真 ContextManager：与 ReActAgentCompressEndToEndTest 同一构造手法。 */
    private static ContextManager realContextManager() throws Exception {
        TokenEstimator estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text == null ? 0 : (int) Math.ceil(text.length() / 3.5);
        });
        when(estimator.getContextWindow(any())).thenReturn(30_000);

        ContextManager manager = new ContextManager(null, null);
        setField(manager, "tokenEstimator", estimator);
        setField(manager, "compactionTrigger", new CompactionTrigger());
        return manager;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

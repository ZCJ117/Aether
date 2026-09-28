package cn.zcj.aether.observability;

import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4/F2-4~F2-6: turn / model call / tool call 三类 span 的父子关系与属性 —— T2-3 ~ T2-6。
 *
 * <p>用真实 {@link SdkTracerProvider} + {@link SimpleSpanProcessor} + 自建
 * {@link RecordingSpanExporter}（{@code opentelemetry-sdk-testing} 未声明，不引入以守 §5）。</p>
 */
class AgentTracerSpanTest {

    private static SdkTracerProvider provider;
    private static RecordingSpanExporter exporter;

    @BeforeAll
    static void setUpProvider() {
        exporter = new RecordingSpanExporter();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        AgentTracer.setTracer(provider.get("aether-agent", "1.0.0"));
    }

    @AfterAll
    static void tearDownProvider() {
        provider.shutdown();
        AgentTracer.setTracer(null);
    }

    @AfterEach
    void clearSpans() {
        exporter.reset();
    }

    /** T2-3：turn 与 model call 处于同一 trace，且 model 的 parentSpanId 指向 turn。 */
    @Test
    @DisplayName("T2-3 agent.turn 与 agent.model.call 构成父子关系")
    void turnAndModelCallShareTraceWithParentLink() {
        String turnSpanId;
        try (AgentTracer.SpanScope turn =
                     AgentTracer.startAgentTurn("agent-1", "session-1", 3)) {
            turnSpanId = turn.span().getSpanContext().getSpanId();

            Span modelSpan = AgentTracer.startModelCall("claude-sonnet");
            AgentTracer.endModelCall(modelSpan, 1200, 300, 0.0045);
        }

        SpanData turnData = exporter.find("agent.turn").orElseThrow(
                () -> new AssertionError("必须导出 agent.turn span"));
        SpanData modelData = exporter.find("agent.model.call").orElseThrow(
                () -> new AssertionError("必须导出 agent.model.call span"));

        assertEquals(turnData.getTraceId(), modelData.getTraceId(),
                "两者必须在同一 trace 下");
        assertEquals(turnSpanId, modelData.getParentSpanId(),
                "model span 的父 span 必须是当轮 turn span");
        assertEquals("agent-1", turnData.getAttributes().get(AttributeKey.stringKey("agent.id")));
        assertEquals("session-1", turnData.getAttributes().get(AttributeKey.stringKey("session.id")));
        assertEquals(3L, longAttr(turnData, "turn.number"), "turn.number 应为当轮序号");
    }

    /** T2-4：model span 携带模型名、token 明细与成本。 */
    @Test
    @DisplayName("T2-4 agent.model.call 属性含 model.name / token.* / cost.usd")
    void modelSpanCarriesTokensAndCost() {
        Span span = AgentTracer.startModelCall("deepseek-chat");
        AgentTracer.endModelCall(span, 1000, 500, 0.125);

        SpanData data = exporter.find("agent.model.call").orElseThrow();
        assertEquals("deepseek-chat",
                data.getAttributes().get(AttributeKey.stringKey("model.name")));
        assertEquals(1000L, longAttr(data, "token.input"));
        assertEquals(500L, longAttr(data, "token.output"));
        assertEquals(1500L, longAttr(data, "token.total"), "token.total 应为输入+输出");
        assertEquals(0.125, data.getAttributes().get(AttributeKey.doubleKey("cost.usd")), 1e-9);
        assertEquals(StatusCode.OK, data.getStatus().getStatusCode());
    }

    /** 取 long 型 span 属性，缺失时以"属性名"给出可读的断言失败。 */
    private static long longAttr(SpanData data, String key) {
        Long value = data.getAttributes().get(AttributeKey.longKey(key));
        assertNotNull(value, "span " + data.getName() + " 缺少属性 " + key);
        return value;
    }

    /** T2-5：tool span 携带工具名、调用 id 与成功标记。 */
    @Test
    @DisplayName("T2-5 agent.tool.call 属性含 tool.name / tool.call_id / tool.success")
    void toolSpanCarriesIdentityAndSuccess() {
        Span span = AgentTracer.startToolCall("web_search", "call-42");
        AgentTracer.endToolCall(span, true, null);

        SpanData data = exporter.find("agent.tool.call").orElseThrow();
        assertEquals("web_search", data.getAttributes().get(AttributeKey.stringKey("tool.name")));
        assertEquals("call-42", data.getAttributes().get(AttributeKey.stringKey("tool.call_id")));
        assertTrue(Boolean.TRUE.equals(
                        data.getAttributes().get(AttributeKey.booleanKey("tool.success"))),
                "成功时 tool.success 应为 true");
        assertEquals(StatusCode.OK, data.getStatus().getStatusCode());
        assertEquals(null, data.getAttributes().get(AttributeKey.stringKey("tool.error")),
                "成功时不应有 tool.error");
    }

    /** T2-6：工具失败 → tool.success=false、status=ERROR、tool.error 非空（JD 职责4 的过滤依据）。 */
    @Test
    @DisplayName("T2-6 工具失败时 span 记为 ERROR 且 tool.error 非空")
    void failedToolSpanIsMarkedError() {
        Span span = AgentTracer.startToolCall("bash", "call-7");
        AgentTracer.endToolCall(span, false, "command not found");

        SpanData data = exporter.find("agent.tool.call").orElseThrow();
        assertFalse(Boolean.TRUE.equals(
                        data.getAttributes().get(AttributeKey.booleanKey("tool.success"))),
                "失败时 tool.success 应为 false");
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
        assertNotNull(data.getAttributes().get(AttributeKey.stringKey("tool.error")));
        assertEquals("command not found",
                data.getAttributes().get(AttributeKey.stringKey("tool.error")));

        List<SpanData> all = exporter.named("agent.tool.call");
        assertEquals(1, all.size());
    }
}

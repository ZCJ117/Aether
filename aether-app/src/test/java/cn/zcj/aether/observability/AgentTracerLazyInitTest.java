package cn.zcj.aether.observability;

import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4/F2-3: {@code AgentTracer} 懒解析改造的验收用例 —— T2-1 / T2-2。
 *
 * <p>改造前 {@code tracer} 是 {@code static final}，在类初始化时求值并永久缓存；
 * 若本类先于 SDK 注册被类加载，缓存的即 no-op 实例，之后任何注册都不生效。
 * 改造后为 {@code volatile} + 双重检查，{@link AgentTracer#setTracer} 可覆盖。</p>
 */
class AgentTracerLazyInitTest {

    @AfterEach
    void tearDown() {
        // AgentTracer 持静态状态，且 surefire 默认复用 JVM——必须清干净，
        // 否则本类的注入会污染同 JVM 内其它测试（如 ToolCallSpanIT）。
        AgentTracer.setTracer(null);
        GlobalOpenTelemetry.resetForTest();
    }

    /** T2-1：无 SDK 时全部方法走 no-op，不抛异常且 isRecording 为 false。 */
    @Test
    @DisplayName("T2-1 无 SDK 时懒解析回落 no-op，且埋点不抛异常")
    void withoutSdkFallsBackToNoopWithoutThrowing() {
        GlobalOpenTelemetry.resetForTest();
        AgentTracer.setTracer(null);

        Span toolSpan = AgentTracer.startToolCall("bash", "call-1");
        assertFalse(toolSpan.isRecording(), "无 SDK 时 span 不得处于 recording 状态");

        assertDoesNotThrow(() -> AgentTracer.endToolCall(toolSpan, false, "boom"),
                "热路径埋点必须吞掉一切异常（no-op 语义）");
        assertDoesNotThrow(() -> AgentTracer.endToolCall(toolSpan, true, null));
        assertDoesNotThrow(() -> AgentTracer.endSpanWithError(toolSpan, "x"));
        assertDoesNotThrow(() -> AgentTracer.addAgentAttributes("k", "v"));

        Span modelSpan = AgentTracer.startModelCall("claude-sonnet");
        assertFalse(modelSpan.isRecording());
        assertDoesNotThrow(() -> AgentTracer.endModelCall(modelSpan, 10, 20, 0.001));

        assertDoesNotThrow(() -> {
            try (AgentTracer.SpanScope scope =
                         AgentTracer.startAgentTurn("agent-1", "session-1", 1)) {
                assertFalse(scope.span().isRecording());
            }
        }, "SpanScope.close() 在 no-op span 上也必须安全");
    }

    /** T2-2：显式注入 Tracer 后 span 进入 recording 状态（改造前被 static final 吃掉）。 */
    @Test
    @DisplayName("T2-2 注入 tracer 后 span 可记录，且 setTracer 覆盖懒解析结果")
    void injectedTracerRecordsSpans() {
        RecordingSpanExporter exporter = new RecordingSpanExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            // 先触发一次懒解析，确保"注入"确实覆盖了已缓存的 no-op 实例
            GlobalOpenTelemetry.resetForTest();
            AgentTracer.setTracer(null);
            assertFalse(AgentTracer.startToolCall("t", "c").isRecording());

            AgentTracer.setTracer(provider.get("aether-agent", "1.0.0"));

            Span span = AgentTracer.startToolCall("bash", "call-1");
            assertTrue(span.isRecording(), "注入真实 Tracer 后 span 必须可记录");
            AgentTracer.endToolCall(span, true, null);

            assertEquals(1, exporter.named("agent.tool.call").size(),
                    "结束的 span 应已被导出");
        } finally {
            provider.shutdown();
        }
    }
}

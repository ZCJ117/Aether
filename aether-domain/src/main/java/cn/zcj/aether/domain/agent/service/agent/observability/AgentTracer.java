package cn.zcj.aether.domain.agent.service.agent.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Agent 可观测性 Span 管理器。
 * 灵感来源：AgentScope tracing/ + AutoGen BaseTool OTel + cc-haha cost-tracker。
 *
 * 使用示例：
 * <pre>
 * try (var scope = AgentTracer.startAgentTurn("agent-1", "session-123", 3)) {
 *     try (var ms = AgentTracer.startModelCall("gpt-4o")) {
 *         var result = model.call(messages);
 *         AgentTracer.recordTokenUsage(ms, 1500, 300, 0.0045);
 *     }
 *     try (var ts = AgentTracer.startToolCall("web_search")) {
 *         var result = tool.execute(input);
 *         AgentTracer.endToolCall(ts, true);
 *     }
 * }
 * </pre>
 */
public final class AgentTracer {

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("aether-agent", "1.0.0");

    private AgentTracer() {}

    // =========================================================
    // Span 创建
    // =========================================================

    public static SpanScope startAgentTurn(String agentId, String sessionId, int turnNumber) {
        Span span = tracer.spanBuilder("agent.turn")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("agent.id", agentId)
            .setAttribute("session.id", sessionId)
            .setAttribute("turn.number", turnNumber)
            .startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    public static Span startModelCall(String modelName) {
        return tracer.spanBuilder("agent.model.call")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("model.name", modelName)
            .startSpan();
    }

    public static Span startToolCall(String toolName, String toolCallId) {
        return tracer.spanBuilder("agent.tool.call")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("tool.name", toolName)
            .setAttribute("tool.call_id", toolCallId)
            .startSpan();
    }

    // =========================================================
    // Span 结束
    // =========================================================

    public static void endModelCall(Span span, int inputTokens, int outputTokens, double costUsd) {
        span.setAttribute("token.input", inputTokens);
        span.setAttribute("token.output", outputTokens);
        span.setAttribute("token.total", inputTokens + outputTokens);
        span.setAttribute("cost.usd", costUsd);
        span.setStatus(StatusCode.OK);
        span.end();
    }

    public static void endToolCall(Span span, boolean success, String errorMsg) {
        span.setAttribute("tool.success", success);
        if (!success && errorMsg != null) {
            span.setAttribute("tool.error", errorMsg);
            span.setStatus(StatusCode.ERROR, errorMsg);
        } else {
            span.setStatus(StatusCode.OK);
        }
        span.end();
    }

    public static void endSpanWithError(Span span, String errorMessage) {
        span.setStatus(StatusCode.ERROR, errorMessage);
        span.end();
    }

    // =========================================================
    // 便捷方法
    // =========================================================

    public static void addAgentAttributes(String key, String value) {
        Span current = Span.current();
        if (current != null && current.isRecording()) {
            current.setAttribute(key, value);
        }
    }

    // =========================================================
    // SpanScope — AutoCloseable 包装
    // =========================================================

    public record SpanScope(Span span, Scope scope) implements AutoCloseable {
        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}

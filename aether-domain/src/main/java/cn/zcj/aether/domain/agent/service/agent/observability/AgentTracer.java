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

    /** Tracer 作用域名（与 {@code OtelConfig} 装配时保持一致）。 */
    private static final String SCOPE_NAME = "aether-agent";

    /** Tracer 作用域版本。 */
    private static final String SCOPE_VERSION = "1.0.0";

    /**
     * 显式注入的 Tracer；为 {@code null} 时回落 {@link GlobalOpenTelemetry} 懒解析。
     *
     * <p>D4/F2-3：原为 {@code static final}，在类初始化时求值并永久缓存。若本类先于 SDK
     * 注册被加载（类初始化时机不可控），缓存下来的就是 no-op 实例，之后无论怎么注册都不生效。</p>
     */
    private static volatile Tracer tracer;

    private AgentTracer() {}

    /**
     * D4/F2-2: 显式注入 Tracer，覆盖懒解析结果。
     *
     * <p>由 {@code OtelConfig} 在 SDK 装配时调用（保证显式注入优先于懒解析）。
     * 必须 {@code public}：调用方 {@code cn.zcj.aether.config.OtelConfig} 在 aether-app 模块，
     * 与本类跨包且跨模块，包级可见无法生效。</p>
     *
     * @param t 已装配的 Tracer；测试可注入 fake 以断言 span 内容
     */
    public static void setTracer(Tracer t) {
        tracer = t;
    }

    /**
     * 获取 Tracer：优先显式注入，否则双重检查懒解析回落全局。
     *
     * @return 可用 Tracer；SDK 未注册时返回 no-op 实现（{@code isRecording()==false}）
     */
    private static Tracer tracer() {
        Tracer t = tracer;
        if (t == null) {
            synchronized (AgentTracer.class) {
                t = tracer;
                if (t == null) {
                    tracer = t = GlobalOpenTelemetry.getTracer(SCOPE_NAME, SCOPE_VERSION);
                }
            }
        }
        return t;
    }

    // =========================================================
    // Span 创建
    // =========================================================

    public static SpanScope startAgentTurn(String agentId, String sessionId, int turnNumber) {
        Span span = tracer().spanBuilder("agent.turn")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("agent.id", agentId)
            .setAttribute("session.id", sessionId)
            .setAttribute("turn.number", turnNumber)
            .startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    public static Span startModelCall(String modelName) {
        return tracer().spanBuilder("agent.model.call")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("model.name", modelName)
            .startSpan();
    }

    public static Span startToolCall(String toolName, String toolCallId) {
        return tracer().spanBuilder("agent.tool.call")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("tool.name", toolName)
            .setAttribute("tool.call_id", toolCallId)
            .startSpan();
    }

    /** 图级执行 span（graph.execute）— D4 可视化调试。 */
    public static SpanScope startGraphExecution(String graphExecutionId, String sessionId) {
        Span span = tracer().spanBuilder("graph.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("graph.execution.id", graphExecutionId)
            .setAttribute("session.id", sessionId)
            .startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    /** 图节点 span（graph.node.<type>.<id>）— D4 可视化调试。 */
    public static Span startGraphNode(String graphExecutionId, String agentType, String nodeName) {
        return tracer().spanBuilder("graph.node." + (agentType == null ? "unknown" : agentType) + "." + nodeName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("graph.execution.id", graphExecutionId)
            .setAttribute("graph.node.name", nodeName)
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

    /**
     * D4/F2-4: 只把 span 标成 ERROR，<b>不结束</b>它 —— 供 {@link SpanScope} 的持有者在
     * {@code close()} 之前声明"本次执行失败"。
     *
     * <p>为什么不能用 {@link #endSpanWithError} 代替：{@code SpanScope.close()} 内部还会
     * {@code span.end()}。此处若先结束，状态在结束时即定稿，后续 {@code setStatus} 静默失效，
     * 语义也不再是"标记失败"而是"提前收口"。</p>
     *
     * @param span         目标 span；为 {@code null} 时静默返回
     * @param errorMessage 失败原因；为 {@code null} 时只置状态、不带描述
     */
    public static void setSpanError(Span span, String errorMessage) {
        if (span == null) {
            return;
        }
        if (errorMessage != null) {
            span.setStatus(StatusCode.ERROR, errorMessage);
        } else {
            span.setStatus(StatusCode.ERROR);
        }
    }

    /** 结束图节点 span。 */
    public static void endGraphNode(Span span, boolean success, String errorMsg) {
        if (success) {
            span.setStatus(StatusCode.OK);
        } else if (errorMsg != null) {
            span.setStatus(StatusCode.ERROR, errorMsg);
        } else {
            span.setStatus(StatusCode.ERROR);
        }
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

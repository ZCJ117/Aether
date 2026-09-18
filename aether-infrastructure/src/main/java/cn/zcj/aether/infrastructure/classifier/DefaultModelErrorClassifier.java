package cn.zcj.aether.infrastructure.classifier;

import cn.zcj.aether.domain.agent.service.model.failover.ClassifiedError;
import cn.zcj.aether.domain.agent.service.model.failover.FailoverReason;
import cn.zcj.aether.domain.agent.service.model.failover.ModelErrorClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;

/**
 * 默认模型错误分类器 — 复刻 hermes error_classifier.py 优先级管线。
 *
 * <h3>分类优先级</h3>
 * <ol>
 *   <li>消息模式匹配（content_policy / context_length 等关键词 — 最高优先级）</li>
 *   <li>HTTP 状态码分类（401→AUTH, 402→BILLING, 404→MODEL_NOT_FOUND, 413→PAYLOAD_TOO_LARGE,
 *       429→RATE_LIMIT/OVERLOADED/UPSTREAM_RATE_LIMIT 三分支, 5xx→SERVER_ERROR）</li>
 *   <li>传输异常分类（TimeoutException → TIMEOUT, SSL → SSL_CERT）</li>
 *   <li>UNKNOWN 兜底（retryable=true，保守策略）</li>
 * </ol>
 *
 * <p>对齐 hermes error_classifier.py L573-760 classify_api_error()。</p>
 */
@Slf4j
@Component
public class DefaultModelErrorClassifier implements ModelErrorClassifier {

    //这个是通用的分类器
    // ── 消息模式关键词（对齐 hermes _CONTENT_POLICY_BLOCKED_PATTERNS）──
    //这个是错误消息中包含的关键词
    private static final String[] CONTENT_POLICY_PATTERNS = {
            "content_policy_violation",
            "content filter",
            "safety filter",
            "content filtering",
            "content management policy",
            "output blocked",
            "response blocked",
            "responsible_ai_policy_violation",
            "risk_management",
            "moderation flag",
            "content was filtered",
            "blocked by content filter"
    };

    private static final String[] CONTEXT_OVERFLOW_PATTERNS = {
            "context_length_exceeded",
            "maximum context length",
            "reduce the length",
            "too many tokens",
            "token limit",
            "context window",
            "max_tokens",
            "input length",
            "requested token count exceeds",
            "this model's maximum context length",
            "maximum context",
            "context is too long"
    };

    private static final String[] PAYLOAD_TOO_LARGE_PATTERNS = {
            "payload too large",
            "request too large",
            "request entity too large",
            "request body too large"
    };

    private static final String[] SSL_PATTERNS = {
            "ssl", "tls", "certificate", "cert",
            "ssl_alert", "tls_alert", "bad_record_mac",
            "[ssl:", "sslv3_alert", "handshake_failure"
    };

    @Override
    public ClassifiedError classify(Throwable error, String provider, String model) {
        // 递归解包 cause 链（处理 Spring AI 的 NestedRuntimeException 包装）
        Throwable root = NestedExceptionUtils.getMostSpecificCause(error);
        if (root == null) root = error;

        String msg = buildErrorMessage(error, root);
        String msgLower = msg.toLowerCase();
        Integer statusCode = extractStatusCode(error, root);
        String providerLower = provider != null ? provider.toLowerCase() : "";

        // ── 1. 消息模式匹配（最高优先级）──

        // 1a. 内容策略拦截
        if (matchesAny(msgLower, CONTENT_POLICY_PATTERNS)) {
            return ClassifiedError.of(FailoverReason.CONTENT_POLICY_BLOCKED,
                    statusCode, provider, model, msg);
        }

        // 1b. 上下文溢出
        if (matchesAny(msgLower, CONTEXT_OVERFLOW_PATTERNS)) {
            return ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW,
                    statusCode, provider, model, msg);
        }

        // 1c. 请求体过大
        if (statusCode != null && statusCode == 413
                || matchesAny(msgLower, PAYLOAD_TOO_LARGE_PATTERNS)) {
            return ClassifiedError.of(FailoverReason.PAYLOAD_TOO_LARGE,
                    statusCode, provider, model, msg);
        }

        // ── 2. HTTP 状态码分类 ──

        if (statusCode != null) {
            switch (statusCode) {
                case 401, 403 -> {
                    // 403 可能是永久禁止，但先按瞬态处理（可轮换凭证）
                    return ClassifiedError.of(FailoverReason.AUTH_TRANSIENT,
                            statusCode, provider, model, msg);
                }
                case 402 -> {
                    return ClassifiedError.of(FailoverReason.BILLING,
                            statusCode, provider, model, msg);
                }
                case 404 -> {
                    return ClassifiedError.of(FailoverReason.MODEL_NOT_FOUND,
                            statusCode, provider, model, msg);
                }
                case 429 -> {
                    // 429 三分支（对齐 hermes L1046-1081）
                    if (msgLower.contains("overloaded") || msgLower.contains("overload")) {
                        return ClassifiedError.of(FailoverReason.OVERLOADED,
                                statusCode, provider, model, msg);
                    }
                    if (msgLower.contains("upstream") || msgLower.contains("aggregator")) {
                        return ClassifiedError.of(FailoverReason.UPSTREAM_RATE_LIMIT,
                                statusCode, provider, model, msg);
                    }
                    return ClassifiedError.of(FailoverReason.RATE_LIMIT,
                            statusCode, provider, model, msg);
                }
                case 500, 502, 503, 504, 529 -> {
                    if (statusCode == 503 || statusCode == 529) {
                        return ClassifiedError.of(FailoverReason.OVERLOADED,
                                statusCode, provider, model, msg);
                    }
                    return ClassifiedError.of(FailoverReason.SERVER_ERROR,
                            statusCode, provider, model, msg);
                }
                case 400 -> {
                    // 小觅 mimo 兼容（原 ModelInvoker.isRetryable 特判 modelRef.contains("mimo")）：
                    // 非标准 API 的 400 可能为瞬时错误（MCP 工具定义未就绪），映射 UNKNOWN →
                    // 抖动退避重试（仍受 Resilient 层 maxAttempts 预算约束）；其余 Provider 的 400 为真实客户端错误
                    if (model != null && model.toLowerCase().contains("mimo")) {
                        return ClassifiedError.of(FailoverReason.UNKNOWN,
                                statusCode, provider, model, msg);
                    }
                    return ClassifiedError.of(FailoverReason.FORMAT_ERROR,
                            statusCode, provider, model, msg);
                }
            }
        }

        // ── 3. 传输异常分类 ──

        String exceptionName = root.getClass().getName().toLowerCase();

        // SSL/TLS 错误 → 确定性故障
        if (matchesAny(msgLower, SSL_PATTERNS)
                || exceptionName.contains("ssl")
                || exceptionName.contains("javax.net.ssl")) {
            return ClassifiedError.of(FailoverReason.SSL_CERT,
                    statusCode, provider, model, msg);
        }

        // 【容错】超时统一归类 TIMEOUT 分支，触发固定 1s 重建连接（而非指数退避），快速试探链路恢复
        if (root instanceof TimeoutException
                || msgLower.contains("timeout")
                || msgLower.contains("timed out")
                || msgLower.contains("read timed out")
                || msgLower.contains("connect timed out")) {
            return ClassifiedError.of(FailoverReason.TIMEOUT,
                    statusCode, provider, model, msg);
        }

        // IO/网络错误
        if (root instanceof IOException || root instanceof SocketException) {
            return ClassifiedError.of(FailoverReason.TIMEOUT,
                    statusCode, provider, model, msg);
        }

        // ── 4. UNKNOWN 兜底 ──

        return ClassifiedError.unknown(provider, model, msg);
    }

    // ── 辅助方法 ──

    /**
     * 构建用于模式匹配的综合错误消息。
     * 合并异常消息 + cause 链消息。
     */
    private String buildErrorMessage(Throwable error, Throwable root) {
        StringBuilder sb = new StringBuilder();
        appendSafe(sb, error.getMessage());

        // 遍历 cause 链
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && depth < 10) {
            appendSafe(sb, cause.getMessage());
            cause = cause.getCause();
            depth++;
        }

        // 如果 root 不是 error 的直接 cause，也加入
        if (root != error && root != error.getCause()) {
            appendSafe(sb, root.getMessage());
        }

        return sb.length() > 0 ? sb.toString() : error.getClass().getSimpleName();
    }

    private void appendSafe(StringBuilder sb, String msg) {
        if (msg != null && !msg.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(msg);
        }
    }

    /**
     * 从异常链中提取 HTTP 状态码。
     * 支持 Spring AI 的 HttpClientErrorException / HttpServerErrorException
     * 以及原始异常 message 中的状态码。
     */
    private Integer extractStatusCode(Throwable error, Throwable root) {
        // 尝试从 Spring 的 HttpStatusCodeException 提取
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            try {
                // 反射获取 statusCode（避免硬依赖 Spring Web）
                java.lang.reflect.Method getStatusCode =
                        current.getClass().getMethod("getStatusCode");
                Object code = getStatusCode.invoke(current);
                if (code instanceof org.springframework.http.HttpStatusCode sc) {
                    return sc.value();
                }
            } catch (Exception ignored) {
                // 不是 HttpStatusCodeException 子类
            }

            // 尝试 getRawStatusCode()
            try {
                java.lang.reflect.Method getRawStatusCode =
                        current.getClass().getMethod("getRawStatusCode");
                Object code = getRawStatusCode.invoke(current);
                if (code instanceof Integer i && i > 0) {
                    return i;
                }
            } catch (Exception ignored) {
                // 无此方法
            }

            current = current.getCause();
            depth++;
        }

        // 从消息中提取状态码
        String msg = error.getMessage();
        if (msg != null) {
            if (msg.contains("401") || msg.contains(" 401 ")) return 401;
            if (msg.contains("402") || msg.contains(" 402 ")) return 402;
            if (msg.contains("403") || msg.contains(" 403 ")) return 403;
            if (msg.contains("404") || msg.contains(" 404 ")) return 404;
            if (msg.contains("413") || msg.contains(" 413 ")) return 413;
            if (msg.contains("429") || msg.contains(" 429 ")) return 429;
            if (msg.contains("500") || msg.contains(" 500 ")) return 500;
            if (msg.contains("502") || msg.contains(" 502 ")) return 502;
            if (msg.contains("503") || msg.contains(" 503 ")) return 503;
            if (msg.contains("504") || msg.contains(" 504 ")) return 504;
            if (msg.contains("400") || msg.contains(" 400 ")) return 400;
        }

        return null;
    }

    private boolean matchesAny(String text, String[] patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }
}

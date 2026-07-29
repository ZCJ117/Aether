package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * 结构化错误分类结果 — 包含恢复动作提示。
 *
 * 对齐 hermes-agent error_classifier.py L77-94 的 ClassifiedError dataclass：
 * 分类器输出一次分类，后续重试循环按四个布尔字段决定恢复动作，
 * 无需在每个重试点重复分类。
 */
public record ClassifiedError(
        /** 失败原因枚举 */
        FailoverReason reason,

        /** HTTP 状态码（可为 null） */
        Integer statusCode,

        /** 提供商名称 */
        String provider,

        /** 模型 ID */
        String model,

        /** 人类可读的错误详情 */
        String detail,

        // ── 恢复动作提示（对齐 hermes L90-93）──

        /** 是否可退避重试 */
        boolean retryable,

        /** 是否应触发上下文压缩（CONTEXT_OVERFLOW / PAYLOAD_TOO_LARGE） */
        boolean shouldCompress,

        /** 是否应切换 fallback 模型 */
        boolean shouldFallback
) {

    /**
     * 创建带默认动作提示的分类结果。
     * 根据 reason 自动填充 retryable/shouldCompress/shouldFallback。
     */
    public static ClassifiedError of(FailoverReason reason, Integer statusCode,
                                     String provider, String model, String detail) {
        boolean retryable = switch (reason) {
            case AUTH_PERMANENT, CONTENT_POLICY_BLOCKED -> false;
            case SSL_CERT -> false;
            default -> true;
        };
        boolean shouldCompress = reason == FailoverReason.CONTEXT_OVERFLOW
                || reason == FailoverReason.PAYLOAD_TOO_LARGE;
        boolean shouldFallback = reason == FailoverReason.MODEL_NOT_FOUND
                || reason == FailoverReason.UPSTREAM_RATE_LIMIT
                || reason == FailoverReason.BILLING
                || reason == FailoverReason.AUTH_TRANSIENT;
        return new ClassifiedError(reason, statusCode, provider, model, detail,
                retryable, shouldCompress, shouldFallback);
    }

    /**
     * 创建 UNKNOWN 兜底分类（保守策略：可重试）。
     */
    public static ClassifiedError unknown(String provider, String model, String detail) {
        return new ClassifiedError(FailoverReason.UNKNOWN, null, provider, model, detail,
                true, false, false);
    }

    /** 是否属于认证类错误 */
    public boolean isAuth() {
        return reason == FailoverReason.AUTH_TRANSIENT
                || reason == FailoverReason.AUTH_PERMANENT;
    }
}

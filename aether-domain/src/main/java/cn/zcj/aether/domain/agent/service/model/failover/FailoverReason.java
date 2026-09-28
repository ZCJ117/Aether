package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * API 调用失败原因枚举 — 决定恢复策略。
 *
 * 对齐 hermes-agent error_classifier.py 的 FailoverReason（21 种），
 * 裁剪为 Aether 三 Provider（OpenAI/Anthropic/DashScope）实际可达的 15 种。
 *
 * 恢复策略由 {@link ClassifiedError} 的四个布尔动作提示驱动，
 * 而非在此枚举上硬编码行为。
 */
public enum FailoverReason {

    // ── 认证/授权 ──
    /** 瞬态认证失败（401/403）— 刷新/轮换凭证后重试 */
    AUTH_TRANSIENT,

    /** 永久认证失败（API Key 已吊销/过期）— 直接终止 */
    AUTH_PERMANENT,

    // ── 计费/配额 ──
    /** 402 或确认配额耗尽 — 立即轮换凭证或切换模型 */
    BILLING,

    // ── 限流 ──
    /** 客户端级限流（429，用户自身超额）— 退避后重试 */
    RATE_LIMIT,

    /** 上游模型限流（聚合器 429，如 OpenRouter 上游模型限流）— 切换 fallback 模型 */
    UPSTREAM_RATE_LIMIT,

    /** Provider 过载（503/529）— 退避重试 */
    OVERLOADED,

    // ── 服务端 ──
    /** 内部服务端错误（500/502）— 退避重试 */
    SERVER_ERROR,

    // ── 传输层 ──
    /** 连接/读取超时 — 重建连接后重试 */
    TIMEOUT,

    /** TLS 证书校验失败 — 确定性故障，不重试 */
    SSL_CERT,

    // ── 上下文/载荷 ──
    /** 上下文超出模型窗口 — 压缩而非重试 */
    CONTEXT_OVERFLOW,

    /** 请求体过大（413）— 压缩后重试 */
    PAYLOAD_TOO_LARGE,

    // ── 模型/策略 ──
    /** 模型不存在（404）— 切换 fallback 模型 */
    MODEL_NOT_FOUND,

    /** 内容安全策略拦截 — 确定性拒绝，不重试不变请求 */
    CONTENT_POLICY_BLOCKED,

    /** 请求格式错误（400）— 可选的 strip+retry 或终止 */
    FORMAT_ERROR,

    // ── 兜底 ──
    /** 无法分类 — 保守退避重试 */
    UNKNOWN
}

package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * 恢复分支枚举 — 对齐 hermes turn_retry_state.py 的恢复动作清单。
 * TurnRetryState 据此去重并限制每个分支在单 turn 内的尝试次数。
 */
public enum RecoveryBranch {

    /** 轮换 API 凭据后重试（AUTH_TRANSIENT / BILLING） */
    CREDENTIAL_ROTATION,

    /** 切换 fallback 模型（上游限流 / 模型不存在 / 其它分支耗尽） */
    PROVIDER_FALLBACK,

    /** 自适应限流退避（429 / 过载，30/60/90/120s 表） */
    ADAPTIVE_RATE_LIMIT_BACKOFF,

    /** 抖动指数退避（服务端错误 / 超时 / 未知） */
    JITTERED_BACKOFF,

    /** 上下文压缩后重试（上下文溢出 / 载荷过大） */
    CONTEXT_COMPRESSION,

    /** 重建连接后重试（超时，预留） */
    TIMEOUT_RECONNECT,

    /** 终止：所有恢复策略耗尽或错误不可恢复 */
    TERMINATE
}

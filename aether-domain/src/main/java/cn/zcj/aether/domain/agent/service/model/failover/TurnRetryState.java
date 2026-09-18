package cn.zcj.aether.domain.agent.service.model.failover;

import java.util.HashMap;
import java.util.Map;

/**
 * Turn 级恢复分支账本 — 对齐 hermes turn_retry_state.py。
 *
 * <p>每个模型调用（call）视为一个 turn，持有一个 TurnRetryState 实例。
 * 它消费 {@link ModelErrorClassifier} 的分类结果，结合"已尝试分支"账本
 * 去重并限次，输出下一个恢复动作 {@link RecoveryDirective}。</p>
 *
 * <p>分支限次：CREDENTIAL_ROTATION=1、CONTEXT_COMPRESSION=2、
 * ADAPTIVE_RATE_LIMIT_BACKOFF / JITTERED_BACKOFF / TIMEOUT_RECONNECT=maxAttempts、
 * PROVIDER_FALLBACK=fallback 链长度。</p>
 */
public class TurnRetryState {

    private static final int MAX_CREDENTIAL_ROTATIONS = 1;
    private static final int MAX_COMPRESSION_ATTEMPTS = 2;

    /** 通用退避最大次数（来自 ModelConfig.maxAttempts） */
    private final int maxAttempts;

    /** fallback 链长度（决定 PROVIDER_FALLBACK 上限） */
    private final int fallbackChainSize;

    /** 分支 → 已尝试次数 */
    private final Map<RecoveryBranch, Integer> attemptCount = new HashMap<>();

    public TurnRetryState(int maxAttempts, int fallbackChainSize) {
        this.maxAttempts = maxAttempts;
        this.fallbackChainSize = fallbackChainSize;
    }

    /**
     * 根据分类结果与当前账本，输出下一个恢复指令。
     */
    //NOTE 第 2 步：决策 —— TurnRetryState.nextDirective()
    public RecoveryDirective nextDirective(ClassifiedError e) {
        FailoverReason r = e.reason();

        // 【容错】确定性错误（AUTH_PERMANENT/CONTENT_POLICY_BLOCKED/SSL_CERT）直接终止，不浪费重试预算
        if (r == FailoverReason.AUTH_PERMANENT
                || r == FailoverReason.CONTENT_POLICY_BLOCKED
                || r == FailoverReason.SSL_CERT) {
            return RecoveryDirective.terminate("不可恢复错误: " + r);
        }

        // ── 上下文溢出 → 压缩（上限 2 次）→ 退化 fallback/终止 ──
        if (r == FailoverReason.CONTEXT_OVERFLOW || r == FailoverReason.PAYLOAD_TOO_LARGE) {
            if (count(RecoveryBranch.CONTEXT_COMPRESSION) < MAX_COMPRESSION_ATTEMPTS) {
                return RecoveryDirective.compress("上下文溢出: " + r);
            }
            return exhaustedBranch(RecoveryBranch.CONTEXT_COMPRESSION, e);
        }

        // ── 认证/计费 → 轮换凭据（1 次）→ fallback → 终止 ──
        if (r == FailoverReason.AUTH_TRANSIENT || r == FailoverReason.BILLING) {
            if (count(RecoveryBranch.CREDENTIAL_ROTATION) < MAX_CREDENTIAL_ROTATIONS) {
                return RecoveryDirective.rotateCredential("认证/计费: " + r);
            }
            return exhaustedBranch(RecoveryBranch.CREDENTIAL_ROTATION, e);
        }

        // ── 限流/过载 → 自适应限流退避（30/60/90/120s）→ fallback/终止 ──
        if (r == FailoverReason.RATE_LIMIT || r == FailoverReason.OVERLOADED) {
            int attempt = count(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF) + 1;
            if (attempt <= maxAttempts) {
                return RecoveryDirective.retry(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF,
                        RetryBackoff.adaptiveRateLimitBackoff(attempt), "限流/过载: " + r);
            }
            return exhaustedBranch(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF, e);
        }

        // ── 上游限流 / 模型不存在 → 直接 fallback ──
        if (r == FailoverReason.UPSTREAM_RATE_LIMIT || r == FailoverReason.MODEL_NOT_FOUND) {
            return exhaustedBranch(RecoveryBranch.PROVIDER_FALLBACK, e);
        }

        // ── 超时 → 固定 1s 重建连接（上限 maxAttempts）→ fallback/终止 ──
        if (r == FailoverReason.TIMEOUT) {
            int attempt = count(RecoveryBranch.TIMEOUT_RECONNECT) + 1;
            if (attempt <= maxAttempts) {
                return RecoveryDirective.retry(RecoveryBranch.TIMEOUT_RECONNECT,
                        1.0, "超时重建连接: " + r);
            }
            return exhaustedBranch(RecoveryBranch.TIMEOUT_RECONNECT, e);
        }

        // ── 服务端错误 / 未知 → 抖动退避 → fallback/终止 ──
        if (r == FailoverReason.SERVER_ERROR || r == FailoverReason.UNKNOWN) {
            int attempt = count(RecoveryBranch.JITTERED_BACKOFF) + 1;
            if (attempt <= maxAttempts) {
                return RecoveryDirective.retry(RecoveryBranch.JITTERED_BACKOFF,
                        RetryBackoff.jitteredBackoff(attempt), "可重试: " + r);
            }
            return exhaustedBranch(RecoveryBranch.JITTERED_BACKOFF, e);
        }

        // ── 其它（FORMAT_ERROR 等）→ 终止 ──
        return RecoveryDirective.terminate("无恢复分支: " + r);
    }

    /**
     * 分支尝试耗尽后的退化路径：若 fallback 链未用尽则切 fallback，否则终止。
     */
    private RecoveryDirective exhaustedBranch(RecoveryBranch branch, ClassifiedError e) {
        if (count(RecoveryBranch.PROVIDER_FALLBACK) < fallbackChainSize) {
            return RecoveryDirective.fallback("分支耗尽(" + branch + ") → fallback");
        }
        // fallback 链也耗尽 → 终止
        return RecoveryDirective.terminate("所有恢复分支耗尽: " + e.reason());
    }

    /** 记录一次分支尝试（由执行器在成功执行恢复动作后调用） */
    public void markAttempted(RecoveryBranch branch) {
        attemptCount.merge(branch, 1, Integer::sum);
    }

    /**
     * 将某分支直接标记为已尝试 N 次（用于把账本与实际外部状态同步，
     * 如 fallback 链已耗尽但未逐次 markAttempted 的场景）。
     */
    public void markExhausted(RecoveryBranch branch, int count) {
        attemptCount.put(branch, count);
    }

    /** 查询某分支已尝试次数 */
    public int count(RecoveryBranch branch) {
        return attemptCount.getOrDefault(branch, 0);
    }

    /** 清空账本（fallback 切换成功或整个 turn 成功时调用） */
    public void reset() {
        attemptCount.clear();
    }

    /**
     * 清空 per-model 账本（fallback 切换成功后调用）——
     * 保留 PROVIDER_FALLBACK 链位置，因为链消费是全局的、不随模型切换重置。
     */
    public void resetPerModel() {
        attemptCount.remove(RecoveryBranch.JITTERED_BACKOFF);
        attemptCount.remove(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF);
        attemptCount.remove(RecoveryBranch.CONTEXT_COMPRESSION);
        attemptCount.remove(RecoveryBranch.CREDENTIAL_ROTATION);
        attemptCount.remove(RecoveryBranch.TIMEOUT_RECONNECT);
    }
}

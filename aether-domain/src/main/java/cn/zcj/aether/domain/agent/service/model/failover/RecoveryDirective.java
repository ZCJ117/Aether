package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * 恢复指令 — TurnRetryState 的决策输出。
 * ResilientChatModelExecutor 按 branch 执行对应恢复动作。
 */
public record RecoveryDirective(
        /** 恢复分支 */
        RecoveryBranch branch,

        /** 是否应立即退避后重试 */
        boolean shouldRetry,

        /** 退避秒数（仅 shouldRetry 时有意义） */
        double backoffSec,

        /** 是否应轮换凭据 */
        boolean rotateCredential,

        /** 是否应压缩上下文 */
        boolean compress,

        /** 是否应切换 fallback 模型 */
        boolean fallback,

        /** 人类可读的决策原因 */
        String reason
) {

    public static RecoveryDirective terminate(String reason) {
        return new RecoveryDirective(RecoveryBranch.TERMINATE, false, 0, false, false, false, reason);
    }

    public static RecoveryDirective retry(RecoveryBranch branch, double backoffSec, String reason) {
        return new RecoveryDirective(branch, true, backoffSec, false, false, false, reason);
    }

    public static RecoveryDirective rotateCredential(String reason) {
        return new RecoveryDirective(RecoveryBranch.CREDENTIAL_ROTATION, true, 0, true, false, false, reason);
    }

    public static RecoveryDirective compress(String reason) {
        return new RecoveryDirective(RecoveryBranch.CONTEXT_COMPRESSION, true, 0, false, true, false, reason);
    }

    public static RecoveryDirective fallback(String reason) {
        return new RecoveryDirective(RecoveryBranch.PROVIDER_FALLBACK, true, 0, false, false, true, reason);
    }
}

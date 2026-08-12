package cn.zcj.aether.domain.agent.service.subagent;

import java.time.Instant;
import java.util.List;

/**
 * 异步委派持久化端口 — 对齐 hermes async_delegations 表（async_delegation.py L142）。
 * <p>实现位于 infrastructure（PgAsyncDelegationStore）。持久化未启用时实现为 null，
 * 调用方须空安全降级为内存态。</p>
 */
public interface AsyncDelegationStore {

    void save(DelegationRecord record);

    List<DelegationRecord> listBySession(String sessionId);

    /** 扫描非终态且 stale（updatedAt < staleBefore）的记录，供崩溃恢复。 */
    List<DelegationRecord> findPendingStale(Instant staleBefore, int limit);

    /** 扫描终态且 completion 未投递（completionDelivered=false）的记录，供启动回灌。 */
    List<DelegationRecord> findUndeliveredTerminal(int limit);

    /** 置终态 + 结果摘要（覆盖 markCompleted/markFailed/markInterrupted/markTimedOut）。 */
    void markTerminal(String id, SubagentState terminal, String resultSummary);

    /** 崩溃恢复重入队：置回 QUEUED 并递增 attemptCount。 */
    void markQueuedForRetry(String id, int newAttemptCount);

    void updateHeartbeat(String id, Instant at);

    /** completion 投递成功后标记 delivered（去重，对齐 hermes delivery_state L142）。 */
    void markCompletionDelivered(String id);
}

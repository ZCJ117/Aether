package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步委派管线 — 对齐 hermes async_delegation.py（dispatch L594 / recover L293 / restore L344 / interrupt L1331/L1360）。
 * <p>dispatch：LeaseManager 容量闸 → 落库 QUEUED → SubagentLifecycleService 执行 →
 * 完成时 markTerminal + 推 completion 到 CompletionBus + markCompletionDelivered。
 * 启动恢复：restoreUndelivered 回灌终态未投递 completion；recoverAbandoned 重入队 stale
 * （attemptCount++，上限 8，对齐 hermes _MAX_DELIVERY_ATTEMPTS=8）。
 * store 为 null 时内存降级（aether.delegation.persistence=false）。不持有线程池：
 * 子Agent执行由 SubagentLifecycleService 内部固定池承担。</p>
 */
@Slf4j
@Service
public class AsyncDelegationService {

    /** 对齐 hermes async_delegation.py _MAX_DELIVERY_ATTEMPTS=8（L85）。 */
    static final int MAX_ATTEMPTS = 8;
    static final Duration STALE_TIMEOUT = Duration.ofMinutes(30);

    private final SubagentLifecycleService lifecycle;
    private final CompletionBus completionBus;
    private final LeaseManager leaseManager;
    private final SpawnGate spawnGate;
    private final AsyncDelegationStore store;
    private final AtomicBoolean recoveryDone = new AtomicBoolean(false);

    public AsyncDelegationService(SubagentLifecycleService lifecycle,
                                  CompletionBus completionBus,
                                  LeaseManager leaseManager,
                                  SpawnGate spawnGate,
                                  AsyncDelegationStore store) {
        this.lifecycle = lifecycle;
        this.completionBus = completionBus;
        this.leaseManager = leaseManager;
        this.spawnGate = spawnGate;
        this.store = store;
    }

    @PostConstruct
    public void start() {
        // 顺序：先回灌完成事件，再恢复 abandoned（对齐 hermes restore_undelivered L356 先调 recover）
        restoreUndelivered();
        recoverAbandoned();
    }

    /**
     * 提交异步委派。成功返回 delegationId；被并发上限拒绝返回 null（不排队，对齐 hermes L680）。
     * SpawnGate 闸门由调用方（SubAgentDelegationTool）负责，本方法不重复加闸。
     */
    public String dispatch(DelegationTask task) {
        if (!leaseManager.acquireLease(task.parentSessionId())) {
            log.warn("AsyncDelegationService: session={} 并发委派达上限，拒绝 task={}",
                    task.parentSessionId(), truncate(task.task(), 120));
            return null;
        }
        String id = "ad-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            DelegationRecord rec = DelegationRecord.builder()
                    .id(id)
                    .parentSessionId(task.parentSessionId())
                    .parentAgentId(task.parentAgentId())
                    .taskPayload(task.task())
                    .toolNames(task.toolNames())
                    .state(SubagentState.QUEUED)
                    .attemptCount(1)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            if (store != null) {
                store.save(rec);
            }
            runDelegation(id, task);
            return id;
        } catch (Exception e) {
            leaseManager.releaseLease(task.parentSessionId());
            if (store != null) {
                store.markTerminal(id, SubagentState.FAILED, "[启动失败: " + e.getMessage() + "]");
            }
            log.error("AsyncDelegationService: dispatch 启动失败 id={}", id, e);
            return null;
        }
    }

    private void runDelegation(String id, DelegationTask task) {
        try {
            CompletableFuture<ResultRefiner.SubAgentResult> future = lifecycle.launch(id, task);
            future.whenComplete((result, err) -> finalizeDelegation(id, task, err));
        } catch (Exception e) {
            log.error("AsyncDelegationService: 启动委派执行失败 id={}", id, e);
            finalizeDelegation(id, task, e);
        }
    }

    private void finalizeDelegation(String id, DelegationTask task, Throwable err) {
        try {
            SubagentState terminal = lifecycle.status(id).orElse(SubagentState.FAILED);
            if (err != null && terminal != SubagentState.CANCELLED
                    && terminal != SubagentState.INTERRUPTED) {
                terminal = SubagentState.FAILED;
            }
            String summary = lifecycle.result(id)
                    .map(ResultRefiner.SubAgentResult::summary).orElse(null);
            if (store != null) {
                store.markTerminal(id, terminal, summary);
            }
            completionBus.publish(new DelegationCompletion(
                    id, task.parentSessionId(), task.parentAgentId(), task.task(),
                    terminal, summary, Instant.now()));
            if (store != null) {
                store.markCompletionDelivered(id);
            }
        } catch (Exception e) {
            log.error("AsyncDelegationService: 收尾委派失败 id={}", id, e);
        } finally {
            leaseManager.releaseLease(task.parentSessionId());
        }
    }

    /**
     * 启动恢复 abandoned：扫描非终态 stale 记录 → 重入队（attemptCount++，幂等仅一次）。
     * 超出 MAX_ATTEMPTS 则置 FAILED（对齐 hermes delivery_attempts>=8 → dropped 终态，L426）。
     * @return 重入队条数
     */
    public int recoverAbandoned() {
        if (!recoveryDone.compareAndSet(false, true)) {
            return 0;
        }
        if (store == null) {
            return 0;
        }
        Instant staleBefore = Instant.now().minus(STALE_TIMEOUT);
        List<DelegationRecord> stale = store.findPendingStale(staleBefore, 100);
        int recovered = 0;
        for (DelegationRecord rec : stale) {
            if (rec.getAttemptCount() >= MAX_ATTEMPTS) {
                store.markTerminal(rec.getId(), SubagentState.FAILED,
                        "[超出最大尝试次数 " + MAX_ATTEMPTS + "]");
                continue;
            }
            if (!leaseManager.acquireLease(rec.getParentSessionId())) {
                continue; // 容量不足，留给后续恢复
            }
            store.markQueuedForRetry(rec.getId(), rec.getAttemptCount() + 1);
            DelegationTask task = new DelegationTask(
                    rec.getTaskPayload(), rec.getToolNames(), null,
                    "system", rec.getParentSessionId(), rec.getParentAgentId());
            runDelegation(rec.getId(), task);
            recovered++;
        }
        log.info("AsyncDelegationService: recoverAbandoned 恢复 {} 条", recovered);
        return recovered;
    }

    /**
     * 启动回灌：COMPLETED/FAILED/... 未投递 completion → 推送到 CompletionBus。
     * @return 回灌条数
     */
    public int restoreUndelivered() {
        if (store == null) {
            return 0;
        }
        int replayed = completionBus.subscribeFromPersistence(store);
        log.info("AsyncDelegationService: restoreUndelivered 回灌 {} 条", replayed);
        return replayed;
    }

    /** 中断指定 session 的所有活跃委派（对齐 interrupt_for_session L1360）。 */
    public int interruptForSession(String sessionId, String reason) {
        int count = 0;
        for (String id : lifecycle.activeIds()) {
            Optional<DelegationTask> t = lifecycle.taskOf(id);
            if (t.isPresent() && sessionId.equals(t.get().parentSessionId())
                    && lifecycle.cancel(id)) {
                count++;
            }
        }
        log.info("AsyncDelegationService: interruptForSession session={} reason={} 中断 {} 个",
                sessionId, reason, count);
        return count;
    }

    /** 中断所有活跃委派（对齐 interrupt_all L1331）。 */
    public int interruptAll(String reason) {
        int count = 0;
        for (String id : lifecycle.activeIds()) {
            if (lifecycle.cancel(id)) {
                count++;
            }
        }
        log.info("AsyncDelegationService: interruptAll reason={} 中断 {} 个", reason, count);
        return count;
    }

    /** 查询 session 的委派记录（持久化启用时）。 */
    public List<DelegationRecord> listBySession(String sessionId) {
        if (store == null) {
            return List.of();
        }
        return store.listBySession(sessionId);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

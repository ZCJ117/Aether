package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
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
 * recoverAbandoned 为启动一次性（one-shot）：容量跳过或落库失败的记录在下次重启时重新扫描恢复。
 * store 为 null 时内存降级（aether.delegation.persistence=false）。不持有线程池：
 * 子Agent执行由 SubagentLifecycleService 内部固定池承担。
 * <p>注：detectStale 心跳冻结检测已由 {@code StaleDelegationScanner} 周期触发
 * （{@code aether.delegation.stale-scan-interval-ms}，默认 60000，&lt;=0 禁用）；
 * 该扫描被禁用时，挂起子Agent 会占用租约与池线程（与同步 SubAgentOrchestrator 同源）。</p>
 */
@Slf4j
@Service
@DependsOnDatabaseInitialization
@DependsOn("delegationCompletionSink")
public class AsyncDelegationService {

    /** 重执行上限：Aether 借用 hermes _MAX_DELIVERY_ATTEMPTS=8（投递预算）作为启动恢复重入队上限。 */
    static final int MAX_ATTEMPTS = 8;
    /**
     * O1: stale 阈值统一走配置键 {@code aether.delegation.stale-timeout}（缺省 PT10M，与
     * StaleDelegationScanner 同源）；本常量仅为无 Spring 环境时的缺省值。
     */
    static final Duration STALE_TIMEOUT = Duration.ofMinutes(10);

    private final SubagentLifecycleService lifecycle;
    private final CompletionBus completionBus;
    private final LeaseManager leaseManager;
    private final SpawnGate spawnGate;
    private final AsyncDelegationStore store;
    /** O1: stale 阈值（统一配置键 aether.delegation.stale-timeout，与 StaleDelegationScanner 同源）。 */
    private final Duration staleTimeout;
    private final AtomicBoolean recoveryDone = new AtomicBoolean(false);

    /**
     * Spring 构造：store 可选（持久化未启用时 getIfAvailable() 返回 null → 内存降级）。
     * O1: stale 阈值不再硬编码 30min，与 StaleDelegationScanner 统一读 aether.delegation.stale-timeout。
     */
    @Autowired
    public AsyncDelegationService(SubagentLifecycleService lifecycle,
                                  CompletionBus completionBus,
                                  LeaseManager leaseManager,
                                  SpawnGate spawnGate,
                                  ObjectProvider<AsyncDelegationStore> storeProvider,
                                  @org.springframework.beans.factory.annotation.Value("${aether.delegation.stale-timeout:PT10M}") String staleTimeout) {
        this(lifecycle, completionBus, leaseManager, spawnGate, storeProvider.getIfAvailable(),
                Duration.parse(staleTimeout));
    }

    /** 测试/直连构造：store 可为 null（持久化未启用时内存降级），stale 阈值取缺省。 */
    AsyncDelegationService(SubagentLifecycleService lifecycle,
                           CompletionBus completionBus,
                           LeaseManager leaseManager,
                           SpawnGate spawnGate,
                           AsyncDelegationStore store) {
        this(lifecycle, completionBus, leaseManager, spawnGate, store, STALE_TIMEOUT);
    }

    AsyncDelegationService(SubagentLifecycleService lifecycle,
                           CompletionBus completionBus,
                           LeaseManager leaseManager,
                           SpawnGate spawnGate,
                           AsyncDelegationStore store,
                           Duration staleTimeout) {
        this.lifecycle = lifecycle;
        this.completionBus = completionBus;
        this.leaseManager = leaseManager;
        this.spawnGate = spawnGate;
        this.store = store;
        this.staleTimeout = staleTimeout;
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
        boolean saved = false;
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
                saved = true;
            }
            if (!runDelegation(id, task)) {
                return null; // 同步启动失败：finalizeDelegation 已 markTerminal+publish+releaseLease
            }
            return id;
        } catch (Exception e) {
            leaseManager.releaseLease(task.parentSessionId());
            if (saved && store != null) {
                store.markTerminal(id, SubagentState.FAILED, "[启动失败: " + e.getMessage() + "]");
            }
            log.error("AsyncDelegationService: dispatch 启动失败 id={}", id, e);
            return null;
        }
    }

    private boolean runDelegation(String id, DelegationTask task) {
        try {
            CompletableFuture<ResultRefiner.SubAgentResult> future = lifecycle.launch(id, task);
            future.whenComplete((result, err) -> finalizeDelegation(id, task, err));
            return true;
        } catch (Exception e) {
            log.error("AsyncDelegationService: 启动委派执行失败 id={}", id, e);
            finalizeDelegation(id, task, e);
            return false;
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
            // VUL-05 (b): 置位条件必须严格是 delivered > 0。
            // 无条件置位会让 completion_delivered 永远为 TRUE，回灌查询（= FALSE）永远捞不到数据 —— 静默数据丢失。
            int delivered = completionBus.publish(new DelegationCompletion(
                    id, task.parentSessionId(), task.parentAgentId(), task.task(),
                    terminal, summary, Instant.now()));
            if (store != null && delivered > 0) {
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
     * 超出 MAX_ATTEMPTS 则置 FAILED。
     * 注：hermes recover_abandoned_delegations 将 stale 标记为 terminal-unknown 且不重跑（防多进程
     * 存活 owner 双执行）；Aether 为单进程且恢复仅启动一次性（前一 JVM 已死），重入队 at-most-once 成立。
     * @return 重入队条数
     */
    public int recoverAbandoned() {
        if (!recoveryDone.compareAndSet(false, true)) {
            return 0;
        }
        if (store == null) {
            return 0;
        }
        Instant staleBefore = Instant.now().minus(staleTimeout);
        List<DelegationRecord> stale = store.findPendingStale(staleBefore, 100);
        int recovered = 0;
        for (DelegationRecord rec : stale) {
            if (rec.getAttemptCount() >= MAX_ATTEMPTS) {
                store.markTerminal(rec.getId(), SubagentState.FAILED,
                        "[超出最大尝试次数 " + MAX_ATTEMPTS + "]");
                continue;
            }
            if (!leaseManager.acquireLease(rec.getParentSessionId())) {
                continue; // 容量不足，跳过（recoverAbandoned 为 one-shot：下次重启再恢复）
            }
            try {
                store.markQueuedForRetry(rec.getId(), rec.getAttemptCount() + 1);
                DelegationTask task = new DelegationTask(
                        rec.getTaskPayload(), rec.getToolNames(), null,
                        "system", rec.getParentSessionId(), rec.getParentAgentId());
                runDelegation(rec.getId(), task);
                recovered++;
            } catch (Exception e) {
                // markQueuedForRetry 落库失败 → 释放租约防泄漏（releaseLease 幂等，双释放无害）
                leaseManager.releaseLease(rec.getParentSessionId());
                store.markTerminal(rec.getId(), SubagentState.FAILED,
                        "[恢复重入队失败: " + e.getMessage() + "]");
                log.error("AsyncDelegationService: recover 重入队失败 id={}", rec.getId(), e);
            }
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

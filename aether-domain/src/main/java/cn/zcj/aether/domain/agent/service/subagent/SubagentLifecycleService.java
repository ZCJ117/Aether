package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子Agent生命周期状态机 + 异步执行 — 对齐 hermes subagent_lifecycle.py（L186）。
 * <p>launch：QUEUED→RUNNING，内部线程池执行子Agent（boundary/factory/refiner 复用），
 * 持有 SubagentRuntime（agent + cancelToken + future + 心跳）。状态转换经 AtomicReference
 * CAS 守卫（对齐 hermes RLock + 状态检查）。cancel→CANCELLED；stale（心跳冻结）→TIMED_OUT。
 * 注：hermes 无 TIMED_OUT 状态、超时仅作 wait 标志；Aether 按设计 §6.3③ 引入 stale→TIMED_OUT。</p>
 */
@Slf4j
@Component
public class SubagentLifecycleService {

    private final SubAgentBoundary boundary;
    private final DefaultAgentFactory agentFactory;
    private final ResultRefiner refiner;
    private final ExecutorService executor;
    private final Map<String, SubagentRuntime> runtimes = new ConcurrentHashMap<>();
    private final DelegationLiveLog liveLog;
    private final int terminalRetention;
    /** O1: 子代理生命周期钩子（SUBAGENT_START/STOP，与同步路径对齐）；可空（测试/未装配）。 */
    private final HookRegistry hookRegistry;
    /** 是否为自建线程池：自建才由本类关闭；注入的共享 delegationPool 由容器统一关闭。 */
    private final boolean ownsExecutor;

    /** Spring 构造：注入共享 delegationPool（P0-1 统一线程资源管理）；liveLog 可选（无 Bean 时 no-op）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                                    ResultRefiner refiner,
                                    org.springframework.beans.factory.ObjectProvider<DelegationLiveLog> liveLogProvider,
                                    org.springframework.beans.factory.ObjectProvider<HookRegistry> hookRegistryProvider,
                                    @org.springframework.beans.factory.annotation.Value("${aether.subagent.terminal-retention:100}") int terminalRetention,
                                    @org.springframework.beans.factory.annotation.Qualifier("delegationPool") ExecutorService executor) {
        this(boundary, agentFactory, refiner, executor, liveLogProvider.getIfAvailable(),
                terminalRetention, false, hookRegistryProvider.getIfAvailable());
    }

    /** 测试注入线程池（liveLog 空、retention 默认 100）。 */
    SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                             ResultRefiner refiner, ExecutorService executor) {
        this(boundary, agentFactory, refiner, executor, null, 100, true, null);
    }

    /** 测试注入线程池 + liveLog + retention。 */
    SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                             ResultRefiner refiner, ExecutorService executor,
                             DelegationLiveLog liveLog, int terminalRetention) {
        this(boundary, agentFactory, refiner, executor, liveLog, terminalRetention, true, null);
    }

    private SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                                     ResultRefiner refiner, ExecutorService executor,
                                     DelegationLiveLog liveLog, int terminalRetention, boolean ownsExecutor,
                                     HookRegistry hookRegistry) {
        this.boundary = boundary;
        this.agentFactory = agentFactory;
        this.refiner = refiner;
        this.executor = executor;
        this.liveLog = liveLog;
        this.terminalRetention = terminalRetention;
        this.ownsExecutor = ownsExecutor;
        this.hookRegistry = hookRegistry;
    }

    /**
     * 启动异步子Agent：登记 QUEUED → 线程池执行（RUNNING）→ 终态。返回完成 future。
     * @param id 由调用方生成的持久化委派 id（如 ad-xxx）
     * @param task 委派输入
     * @return 完成 future（completed 后经 status(id) 读取终态）
     */
    public CompletableFuture<ResultRefiner.SubAgentResult> launch(String id, DelegationTask task) {
        String hex = Integer.toHexString(Math.abs(task.task().hashCode()));
        String taskId = "t" + hex.substring(0, Math.min(6, hex.length()));
        AgentConfig config = boundary.createIsolatedConfig(
                task.parentSessionId(), task.task(), task.toolNames(), task.modelRef(), taskId, null);
        Agent agent = agentFactory.create(config);
        SubagentRuntime rt = new SubagentRuntime(id, task, config, agent, config.getCancelToken());
        runtimes.put(id, rt);
        if (liveLog != null) {
            liveLog.open(id, task.task());
        }

        CompletableFuture<ResultRefiner.SubAgentResult> future =
                CompletableFuture.supplyAsync(() -> runAgent(rt), executor);
        rt.setFuture(future);
        future.whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("SubagentLifecycleService: 子Agent执行异常 id={}", id, ex);
            }
        });
        return future;
    }

    private ResultRefiner.SubAgentResult runAgent(SubagentRuntime rt) {
        MDC.put("subagentId", rt.id());
        try {
            if (!rt.toRunning()) {
                // 启动前已被 cancel → 直接终态返回（对齐 hermes _run L402：非 CANCEL_REQUESTED 才置 RUNNING）
                return rt.result();
            }
            // O1: 异步路径触发 SUBAGENT_START（与同步 SubAgentOrchestrator 对齐，消除可观测漂移）
            notifyHook(HookPoint.SUBAGENT_START, HookContext.builder()
                    .agentId(rt.config().getName()).sessionId(rt.task().parentSessionId())
                    .request(rt.task().task()).build());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subAgentContext", Boolean.TRUE);
            metadata.put("taskId", rt.id());
            // O3: agentId=子Agent 自身，parentAgentId=委派来源 Agent
            RuntimeContext ctx = new RuntimeContext(rt.task().userId(), rt.config().getName(),
                    null, rt.task().parentAgentId(), rt.task().task(), metadata, null,
                    rt.config().getName());
            List<TurnMessage> collected = new ArrayList<>();
            // O1 步骤4: 绝对到期判据——1s 周期采样 CancelToken，事件流停滞（无 emission）时
            // takeUntil 谓词不会重估，此前异步子Agent可无限长跑；定时采样补上主动到期检查。
            rt.agent().execute(ctx)
                    .takeUntil(Flowable.interval(1, 1, TimeUnit.SECONDS)
                            .filter(tick -> rt.cancelToken().isCancelled())
                            .onErrorComplete())
                    .takeUntil((io.reactivex.rxjava3.functions.Predicate<RuntimeEvent>) ev ->
                            rt.cancelToken().isCancelled())
                    .blockingForEach(event -> {
                        rt.heartbeat(); // 每次事件刷新心跳（进度信号）
                        if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                            collected.add(TurnMessage.assistant(event.getText()));
                            if (liveLog != null) {
                                liveLog.append(rt.id(), "assistant", event.getText());
                            }
                        }
                        if (event.getType() == RuntimeEvent.EventType.toolResult) {
                            collected.add(TurnMessage.toolResult(event.getToolCallId(),
                                    event.getToolName(), event.getToolOutput()));
                            if (liveLog != null) {
                                liveLog.append(rt.id(), "result",
                                        (event.getToolName() == null ? "?" : event.getToolName())
                                        + (event.isToolError() ? " ERROR" : " ok") + ": "
                                        + (event.getToolOutput() == null ? "" : event.getToolOutput()));
                            }
                        }
                    });
            ResultRefiner.SubAgentResult result = refiner.refine(rt.task().task(), collected);
            rt.setResult(result);
            rt.tryTerminal(SubagentState.COMPLETED); // 已被 cancel/stale 置终态则 CAS 失败，保持原终态
            closeLiveLog(rt);
            evictTerminalIfNeeded();
            return result;
        } catch (Exception e) {
            log.error("SubagentLifecycleService: 子Agent执行异常 id={} task={}",
                    rt.id(), truncate(rt.task().task(), 120), e);
            ResultRefiner.SubAgentResult result = new ResultRefiner.SubAgentResult(
                    "失败", "[子任务异常: " + e.getMessage() + "]", Map.of());
            rt.setResult(result);
            rt.tryTerminal(SubagentState.FAILED);
            closeLiveLog(rt);
            evictTerminalIfNeeded();
            return result;
        } finally {
            // O1: 异步路径触发 SUBAGENT_STOP（与同步路径对齐）
            notifyHook(HookPoint.SUBAGENT_STOP, HookContext.builder()
                    .agentId(rt.config().getName()).sessionId(rt.task().parentSessionId())
                    .request(rt.task().task())
                    .response(rt.result() != null ? rt.result().summary() : null).build());
            MDC.remove("subagentId");
        }
    }

    /** O1: 钩子触发（空安全，对齐 SubAgentOrchestrator.notifyHook 防御模式）。 */
    private void notifyHook(HookPoint point, HookContext ctx) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(point, ctx);
        }
    }

    /** 写终态摘要并 close 直播日志（best-effort；close 幂等）。 */
    private void closeLiveLog(SubagentRuntime rt) {
        if (liveLog == null) {
            return;
        }
        String summary = rt.result() != null && rt.result().summary() != null
                ? rt.result().summary() : "";
        liveLog.close(rt.id(), "end status=" + rt.state()
                + (summary.isEmpty() ? "" : " | " + summary));
    }

    /**
     * O1: 同步路径登记运行时（SubAgentOrchestrator.dispatch 调用）——
     * 以本状态机为唯一状态源，同步子Agent对 stale 扫描 / activeIds / cancel 可见。
     * 不启动执行：同步路径在调用方线程立即执行，调用方自行 toRunning()/heartbeat()/finishSync()。
     */
    SubagentRuntime registerSync(String id, DelegationTask task, AgentConfig config, Agent agent) {
        SubagentRuntime rt = new SubagentRuntime(id, task, config, agent, config.getCancelToken());
        runtimes.put(id, rt);
        return rt;
    }

    /** O1: 同步路径终态落地（结果 + CAS 终态 + 终态逐出）；已被 stale/取消置终态时保持原终态。 */
    void finishSync(SubagentRuntime rt, ResultRefiner.SubAgentResult result, SubagentState terminal) {
        rt.setResult(result);
        rt.tryTerminal(terminal);
        evictTerminalIfNeeded();
    }

    /** 阻塞等待完成，超时返回 false（对齐 hermes wait timed_out 标志，L270）。 */
    public boolean wait(String id, long timeoutMs) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || rt.future() == null) {
            return true;
        }
        try {
            rt.future().get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true; // 已完成（含异常）
        }
    }

    /** 取消：非终态 → CANCELLED + cancel token。返回是否接受（对齐 hermes cancel L291）。 */
    // 注：cancel 后 worker 可能仍在收尾（event 流上的 takeUntil 需下一次 emission 才触发），
    // 已完成的 future 携带"取消"合成结果；result(id) 可能返回 null 或 worker 最终提炼的部分结果
    // （对齐 hermes：cancel → terminal，丢弃最终结果）。
    public boolean cancel(String id) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || !rt.toCancelled()) {
            return false;
        }
        // takeUntil 谓词仅对产生事件的流生效；若子Agent阻塞在非产出流上（如 Flowable.never），
        // worker 不会自行退出，故主动完成 future 使 wait(id) 解除阻塞（对齐 hermes cancel 置态语义）。
        CompletableFuture<ResultRefiner.SubAgentResult> future = rt.future();
        if (future != null) {
            future.complete(rt.result() != null ? rt.result()
                    : new ResultRefiner.SubAgentResult("取消", "[子任务已取消]", Map.of()));
        }
        if (liveLog != null) {
            liveLog.close(id, "end status=CANCELLED [取消]");
        }
        evictTerminalIfNeeded();
        return true;
    }

    /** 查询状态；未知 id 返回 empty。 */
    public Optional<SubagentState> status(String id) {
        SubagentRuntime rt = runtimes.get(id);
        return rt == null ? Optional.empty() : Optional.of(rt.state());
    }

    /** 查询结果；未就绪/未知返回 empty。 */
    // 注：cancel 后 worker 可能仍在收尾（event 流上的 takeUntil 需下一次 emission 才触发），
    // 已完成的 future 携带"取消"合成结果；result(id) 可能返回 null 或 worker 最终提炼的部分结果
    // （对齐 hermes：cancel → terminal，丢弃最终结果）。
    public Optional<ResultRefiner.SubAgentResult> result(String id) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || rt.result() == null) {
            return Optional.empty();
        }
        return Optional.of(rt.result());
    }

    /** 更新心跳（仅非终态有效）。 */
    public boolean heartbeat(String id) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || SubagentRuntime.isTerminal(rt.state())) {
            return false;
        }
        rt.heartbeat();
        return true;
    }

    /** 扫描 RUNNING 且心跳冻结超过 timeout 的委派 → TIMED_OUT + 取消 token。返回受影响 id 列表。 */
    public List<String> detectStale(Duration timeout) {
        List<String> stale = new ArrayList<>();
        Instant cutoff = Instant.now().minus(timeout);
        for (SubagentRuntime rt : runtimes.values()) {
            if (rt.state() == SubagentState.RUNNING && rt.lastHeartbeatAt().isBefore(cutoff)) {
                if (rt.tryTerminal(SubagentState.TIMED_OUT)) {
                    rt.cancelToken().cancel();
                    if (rt.future() != null) {
                        rt.future().complete(rt.result() != null ? rt.result()
                                : new ResultRefiner.SubAgentResult("超时", "[子任务超时]", Map.of()));
                    }
                    stale.add(rt.id());
                    if (liveLog != null) {
                        liveLog.close(rt.id(), "end status=TIMED_OUT [超时]");
                    }
                    evictTerminalIfNeeded();
                }
            }
        }
        return stale;
    }

    /** 非终态运行时 id 列表（供 interrupt 与 Batch 4 实时查询）。 */
    public List<String> activeIds() {
        List<String> ids = new ArrayList<>();
        for (SubagentRuntime rt : runtimes.values()) {
            if (!SubagentRuntime.isTerminal(rt.state())) {
                ids.add(rt.id());
            }
        }
        return ids;
    }

    /** 查询运行时的委派输入（供 interruptForSession 按 session 过滤）。 */
    public Optional<DelegationTask> taskOf(String id) {
        SubagentRuntime rt = runtimes.get(id);
        return rt == null ? Optional.empty() : Optional.of(rt.task());
    }

    /** 终态保留上限：超出 terminalRetention 时逐出最旧终态运行时（active 永不逐出）。
     * 仅逐出 future 已完成的终态运行时——finalizeDelegation 经 whenComplete 在 future 完成时
     * 同步触发，故 future.isDone() 保证收尾已执行，避免并发逐出把 COMPLETED 误标 FAILED。 */
    private void evictTerminalIfNeeded() {
        while (runtimes.size() > terminalRetention) {
            String oldest = null;
            Instant oldestAt = null;
            for (SubagentRuntime rt : runtimes.values()) {
                if (SubagentRuntime.isTerminal(rt.state()) && rt.future() != null && rt.future().isDone()) {
                    Instant at = rt.createdAt();
                    if (oldestAt == null || at.isBefore(oldestAt)) {
                        oldest = rt.id();
                        oldestAt = at;
                    }
                }
            }
            if (oldest == null) {
                break; // 无可逐出的终态条目（active / future 未完成占位）
            }
            runtimes.remove(oldest);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
        runtimes.values().forEach(rt -> rt.cancelToken().cancel());
    }

    /** 测试辅助：将某运行时的心跳拨回过去，模拟冻结。 */
    void rewindHeartbeatForTest(String id, Duration past) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt != null) {
            rt.rewindHeartbeat(Instant.now().minus(past));
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

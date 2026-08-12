package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
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
import java.util.concurrent.Executors;
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

    public static final int DEFAULT_POOL_SIZE = 5;

    private final SubAgentBoundary boundary;
    private final DefaultAgentFactory agentFactory;
    private final ResultRefiner refiner;
    private final ExecutorService executor;
    private final Map<String, SubagentRuntime> runtimes = new ConcurrentHashMap<>();

    public SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory, ResultRefiner refiner) {
        this(boundary, agentFactory, refiner, Executors.newFixedThreadPool(DEFAULT_POOL_SIZE));
    }

    /** 测试注入线程池。 */
    SubagentLifecycleService(SubAgentBoundary boundary, DefaultAgentFactory agentFactory,
                             ResultRefiner refiner, ExecutorService executor) {
        this.boundary = boundary;
        this.agentFactory = agentFactory;
        this.refiner = refiner;
        this.executor = executor;
    }

    /**
     * 启动异步子Agent：登记 QUEUED → 线程池执行（RUNNING）→ 终态。返回本次委派 id。
     * @param id 由调用方生成的持久化委派 id（如 ad-xxx）
     * @param task 委派输入
     * @return 本次委派 id（终态经 status(id)/result(id) 读取）
     */
    public String launch(String id, DelegationTask task) {
        String taskId = "t" + Integer.toHexString(Math.abs(task.task().hashCode())).substring(0, 6);
        AgentConfig config = boundary.createIsolatedConfig(
                task.parentSessionId(), task.task(), task.toolNames(), task.modelRef(), taskId, null);
        Agent agent = agentFactory.create(config);
        SubagentRuntime rt = new SubagentRuntime(id, task, config, agent, config.getCancelToken());
        runtimes.put(id, rt);

        CompletableFuture<ResultRefiner.SubAgentResult> future =
                CompletableFuture.supplyAsync(() -> runAgent(rt), executor);
        rt.setFuture(future);
        future.whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("SubagentLifecycleService: 子Agent执行异常 id={}", id, ex);
            }
        });
        return id;
    }

    private ResultRefiner.SubAgentResult runAgent(SubagentRuntime rt) {
        if (!rt.toRunning()) {
            // 启动前已被 cancel → 直接终态返回（对齐 hermes _run L402：非 CANCEL_REQUESTED 才置 RUNNING）
            return rt.result();
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subAgentContext", Boolean.TRUE);
            metadata.put("taskId", rt.id());
            RuntimeContext ctx = new RuntimeContext(rt.task().userId(), rt.config().getName(),
                    null, null, rt.task().task(), metadata, null);
            List<TurnMessage> collected = new ArrayList<>();
            rt.agent().execute(ctx)
                    .takeUntil((io.reactivex.rxjava3.functions.Predicate<RuntimeEvent>) ev ->
                            rt.cancelToken().isCancelled())
                    .blockingForEach(event -> {
                        rt.heartbeat(); // 每次事件刷新心跳（进度信号）
                        if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                            collected.add(TurnMessage.assistant(event.getText()));
                        }
                        if (event.getType() == RuntimeEvent.EventType.toolResult) {
                            collected.add(TurnMessage.toolResult(event.getToolCallId(),
                                    event.getToolName(), event.getToolOutput()));
                        }
                    });
            ResultRefiner.SubAgentResult result = refiner.refine(rt.task().task(), collected);
            rt.setResult(result);
            rt.tryTerminal(SubagentState.COMPLETED); // 已被 cancel/stale 置终态则 CAS 失败，保持原终态
            return result;
        } catch (Exception e) {
            log.error("SubagentLifecycleService: 子Agent执行异常 id={} task={}",
                    rt.id(), truncate(rt.task().task(), 120), e);
            ResultRefiner.SubAgentResult result = new ResultRefiner.SubAgentResult(
                    "失败", "[子任务异常: " + e.getMessage() + "]", Map.of());
            rt.setResult(result);
            rt.tryTerminal(SubagentState.FAILED);
            return result;
        }
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
    public boolean cancel(String id) {
        SubagentRuntime rt = runtimes.get(id);
        if (rt == null || !rt.toCancelled()) {
            return false;
        }
        // takeUntil 谓词仅对产生事件的流生效；若子Agent阻塞在非产出流上（如 Flowable.never），
        // worker 不会自行退出，故主动完成 future 使 wait(id) 解除阻塞（对齐 hermes cancel 置态语义）。
        CompletableFuture<ResultRefiner.SubAgentResult> future = rt.future();
        if (future != null) {
            future.complete(rt.result());
        }
        return true;
    }

    /** 查询状态；未知 id 返回 empty。 */
    public Optional<SubagentState> status(String id) {
        SubagentRuntime rt = runtimes.get(id);
        return rt == null ? Optional.empty() : Optional.of(rt.state());
    }

    /** 查询结果；未就绪/未知返回 empty。 */
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
                    stale.add(rt.id());
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

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
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

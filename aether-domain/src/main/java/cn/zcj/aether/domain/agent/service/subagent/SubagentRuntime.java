package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时子Agent登记 — 对齐 hermes subagent_lifecycle.py _Record（L149/L401）。
 * 状态用 AtomicReference 保证 cancel/complete/heartbeat 并发下的原子转换守卫。
 * <p><b>【架构亮点 · 状态机与持久化】</b><br>
 * 面试举证点：状态载体为 AtomicReference&lt;SubagentState&gt;（L23），所有转换走 CAS——
 * toRunning 仅 QUEUED→RUNNING（L60）、tryTerminal 仅 RUNNING→终态（L79），
 * 已 CANCELLED/TIMED_OUT 的终态因 CAS 期望值不匹配而不可被覆盖；isTerminal 显式终态集（L82-86）
 * 为并发判定提供单一真相，避免脏写与重复终态。</p>
 */
final class SubagentRuntime {

    private final String id;
    private final DelegationTask task;
    private final AgentConfig config;
    private final Agent agent;
    private final CancelToken cancelToken;
    private final Instant createdAt;
    private final AtomicReference<SubagentState> state;
    private volatile ResultRefiner.SubAgentResult result;
    private volatile Instant lastHeartbeatAt;
    private volatile CompletableFuture<ResultRefiner.SubAgentResult> future;

    SubagentRuntime(String id, DelegationTask task, AgentConfig config, Agent agent, CancelToken cancelToken) {
        this.id = id;
        this.task = task;
        this.config = config;
        this.agent = agent;
        this.cancelToken = cancelToken;
        this.createdAt = Instant.now();
        this.lastHeartbeatAt = Instant.now();
        this.state = new AtomicReference<>(SubagentState.QUEUED);
    }

    String id() { return id; }
    DelegationTask task() { return task; }
    AgentConfig config() { return config; }
    Agent agent() { return agent; }
    CancelToken cancelToken() { return cancelToken; }
    Instant createdAt() { return createdAt; }
    SubagentState state() { return state.get(); }
    ResultRefiner.SubAgentResult result() { return result; }
    Instant lastHeartbeatAt() { return lastHeartbeatAt; }
    CompletableFuture<ResultRefiner.SubAgentResult> future() { return future; }

    void setFuture(CompletableFuture<ResultRefiner.SubAgentResult> future) { this.future = future; }

    void setResult(ResultRefiner.SubAgentResult result) { this.result = result; }

    void heartbeat() { this.lastHeartbeatAt = Instant.now(); }

    void rewindHeartbeat(Instant past) { this.lastHeartbeatAt = past; }

    // 【状态机】CAS 守卫：仅当当前态为 QUEUED 才置 RUNNING，已被 cancel 则 CAS 失败返回 false
    /** QUEUED→RUNNING（worker 开始；若已被 cancel 则 CAS 失败返回 false）。 */
    boolean toRunning() {
        return state.compareAndSet(SubagentState.QUEUED, SubagentState.RUNNING);
    }

    /** 非终态 → CANCELLED（对齐 hermes cancel L291 + _run L402 的守卫，CAS 原子转换）。 */
    boolean toCancelled() {
        while (true) {
            SubagentState cur = state.get();
            if (isTerminal(cur)) {
                return false;
            }
            if (state.compareAndSet(cur, SubagentState.CANCELLED)) {
                cancelToken.cancel();
                return true;
            }
        }
    }

    // 【状态机】仅 RUNNING → 指定终态：终态竞争下已 CANCELLED/TIMED_OUT 因期望值不符而 CAS 失败，不覆盖
    /** 仅 RUNNING → 指定终态（终态竞争：已 CANCELLED/TIMED_OUT 则 CAS 失败，不覆盖）。 */
    boolean tryTerminal(SubagentState target) {
        return state.compareAndSet(SubagentState.RUNNING, target);
    }

    // 【状态机】显式终态集：COMPLETED/FAILED/CANCELLED/TIMED_OUT/INTERRUPTED，状态机判定的唯一权威来源
    static boolean isTerminal(SubagentState s) {
        return s == SubagentState.COMPLETED || s == SubagentState.FAILED
                || s == SubagentState.CANCELLED || s == SubagentState.TIMED_OUT
                || s == SubagentState.INTERRUPTED;
    }
}

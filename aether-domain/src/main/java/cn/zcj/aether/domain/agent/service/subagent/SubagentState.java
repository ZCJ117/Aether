package cn.zcj.aether.domain.agent.service.subagent;

/**
 * 子Agent委派状态机 — 对齐 hermes subagent_lifecycle.py SubagentState（L37）。
 * <p>Aether 语义：QUEUED=已落库待执行；PENDING=已提交待 worker 接管；
 * RUNNING=执行中；COMPLETED/FAILED/CANCELLED/INTERRUPTED/TIMED_OUT 为终态。
 * 注：hermes 无 TIMED_OUT（超时仅作 wait 标志），Aether 按设计 §6.3① 引入。</p>
 * <p><b>【架构亮点 · 状态机与持久化】</b><br>
 * 面试举证点：子 Agent 生命周期被显式建模为 8 态枚举（L9-12：QUEUED/PENDING/RUNNING +
 * 5 个终态 COMPLETED/FAILED/CANCELLED/TIMED_OUT/INTERRUPTED），状态语义单一可信，
 * 终态集合由 SubagentRuntime.isTerminal（L82-86）集中维护，是 CAS 原子流转与
 * stale→TIMED_OUT 判定的唯一权威来源。</p>
 */
public enum SubagentState {
    // 【状态机】子 Agent 8 态：前 3 态为活跃态，后 5 态为不可再迁移的终态（CANCELLED/TIMED_OUT 不可被覆盖）
    QUEUED, PENDING, RUNNING,
    COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED
}

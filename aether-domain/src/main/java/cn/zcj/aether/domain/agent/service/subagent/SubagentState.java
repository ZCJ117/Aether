package cn.zcj.aether.domain.agent.service.subagent;

/**
 * 子Agent委派状态机 — 对齐 hermes subagent_lifecycle.py SubagentState（L37）。
 * <p>Aether 语义：QUEUED=已落库待执行；PENDING=已提交待 worker 接管；
 * RUNNING=执行中；COMPLETED/FAILED/CANCELLED/INTERRUPTED/TIMED_OUT 为终态。
 * 注：hermes 无 TIMED_OUT（超时仅作 wait 标志），Aether 按设计 §6.3① 引入。</p>
 */
public enum SubagentState {
    QUEUED, PENDING, RUNNING,
    COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED
}

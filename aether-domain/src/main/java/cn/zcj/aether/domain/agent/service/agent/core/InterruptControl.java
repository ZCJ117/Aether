package cn.zcj.aether.domain.agent.service.agent.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H5: 运行期中断控制信号。
 *
 * <p>对齐 AgentScope {@code InterruptControl}——volatile 中断标志，
 * 配合 {@code AgentState} 中的 transient 字段使用，确保中断信号永不序列化落盘。
 *
 * <p>使用模式：Agent 主循环每轮检查 {@link #isInterrupted()}，
 * ReActAgent 在 {@code execute()} 开始时通过 {@link #reset()} 清除上轮中断。
 */
public class InterruptControl {

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /** 检查是否已被中断 */
    public boolean isInterrupted() {
        return interrupted.get();
    }

    /** 发出中断信号 */
    public void interrupt() {
        interrupted.set(true);
    }

    /** 清除中断信号（新一轮开始时调用） */
    public void reset() {
        interrupted.set(false);
    }
}

package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 可变状态容器。
 * 灵感来源：AgentScope AgentState（双模式访问：防御拷贝 + 可变句柄）。
 */
public class AgentState {

    // ============ 核心字段 ============

    /** 对话历史 */
    private final List<TurnMessage> messages = new ArrayList<>();

    /** 滚动摘要（由 ContextManager 维护） */
    private String rollingSummary = "";

    /** 当前轮次 */
    private int currentTurn = 0;

    /** Agent 运行状态 */
    private AgentStatus status = AgentStatus.IDLE;

    /** 扩展属性（工具自定义状态等） */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** Agent 配置快照 */
    private AgentConfig config;

    // ============ H5 新增：槽位化子上下文 ============

    /**
     * 工具上下文槽位——工具激活组、校验计数等。
     * 对齐 AgentScope {@code ToolContextState}，可序列化以支持跨会话恢复。
     */
    private final ToolContextState toolContext = new ToolContextState();

    /**
     * H5-步骤2: 压缩熔断计数（P2 方案落地槽位）。
     * 追踪连续自动压缩失败次数，用于熔断判定。
     */
    private int compactFailureCount = 0;

    // ============ H4 新增：权限挂起上下文 ============

    /**
     * 挂起的工具调用列表（等待用户确认）。
     * 使用 CopyOnWriteArrayList 保证并发安全。
     */
    private final List<SuspendedToolCall> asking = new CopyOnWriteArrayList<>();

    // ============ H5 新增：运行期中断信号（transient，永不序列化） ============

    /**
     * 运行期中断控制信号——transient 语义，永不序列化落盘。
     * 对齐 AgentScope {@code InterruptControl}（AgentState.java L79）。
     *
     * <p>双重检查懒挂载：首次访问时才创建，避免无关 Agent 的额外分配。
     * 中断信号仅作用于当前 JVM 进程内的活跃会话，重启后自动清除。
     */
    private transient volatile InterruptControl interruptControl;

    // ============ 双模式访问 ============

    /** 防御性拷贝——给外部消费者（ModelInvoker、ContextManager）读取 */
    public List<TurnMessage> getMessages() {
        return List.copyOf(messages);
    }

    /** 可变句柄——给内部 ReActAgent 循环修改 */
    public List<TurnMessage> messagesMutable() {
        return messages;
    }

    // ============ 属性访问 ============

    public String getRollingSummary() { return rollingSummary; }
    public void setRollingSummary(String s) { this.rollingSummary = s; }

    public int getCurrentTurn() { return currentTurn; }
    public void incrementTurn() { this.currentTurn++; }

    public AgentStatus getStatus() { return status; }
    public void setStatus(AgentStatus s) { this.status = s; }

    public AgentConfig getConfig() { return config; }
    public void setConfig(AgentConfig c) { this.config = c; }

    /** H5: 设置当前轮次（loadState 恢复需要） */
    public void setCurrentTurn(int turn) { this.currentTurn = turn; }

    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }

    // ============ H5 新增：槽位访问 ============

    /** 获取工具上下文槽位 */
    public ToolContextState getToolContext() { return toolContext; }

    /** 获取压缩熔断计数 */
    public int getCompactFailureCount() { return compactFailureCount; }
    public void setCompactFailureCount(int count) { this.compactFailureCount = count; }
    public void incrementCompactFailure() { this.compactFailureCount++; }
    public void resetCompactFailure() { this.compactFailureCount = 0; }

    /**
     * 获取或懒创建中断控制信号。
     * 双重检查锁定，对齐 AgentScope {@code interruptControl()}（AgentState.java L254-267）。
     */
    public InterruptControl interruptControl() {
        if (interruptControl == null) {
            synchronized (this) {
                if (interruptControl == null) {
                    interruptControl = new InterruptControl();
                }
            }
        }
        return interruptControl;
    }

    // ============ H4 新增：权限挂起上下文访问 ============

    /** 获取挂起的工具调用列表（防御性拷贝） */
    public List<SuspendedToolCall> getAsking() {
        return List.copyOf(asking);
    }

    /** 可变句柄——给 ReActAgent 循环修改 */
    public List<SuspendedToolCall> askingMutable() {
        return asking;
    }

    /** 是否有挂起的工具调用等待确认 */
    public boolean hasPendingAsking() {
        return !asking.isEmpty()
                && asking.stream().anyMatch(s -> s.state() == SuspendedToolCall.SuspendedState.ASKING);
    }

    /** 清空挂起列表 */
    public void clearAsking() {
        asking.clear();
    }

    // ============ 枚举 ============

    public enum AgentStatus {
        IDLE, RUNNING, PAUSED, ERROR, SHUTTING_DOWN
    }
}

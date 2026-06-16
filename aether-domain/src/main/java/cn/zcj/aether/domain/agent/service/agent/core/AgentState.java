package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }

    // ============ 枚举 ============

    public enum AgentStatus {
        IDLE, RUNNING, PAUSED, ERROR, SHUTTING_DOWN
    }
}

package cn.zcj.aether.domain.agent.service.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H5: AgentState 槽位——工具上下文状态。
 *
 * <p>承载工具执行相关的可序列化状态，对齐 AgentScope {@code ToolContextState}。
 * 包括工具激活组、校验失败计数等需要跨会话恢复的状态。
 *
 * <p>注意：运行期快照去重集合（snapshottedThisTurn）不入此槽位，
 * 由 {@code ToolExecutor} 内部维护，每轮通过 {@code clearSnapshotTracking()} 清空。
 */
public class ToolContextState {

    /** 已激活的工具名称组 */
    private String activeToolGroup = "default";

    /** 扩展属性（工具自定义状态逃逸舱） */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    // ============ 访问器 ============

    public String getActiveToolGroup() { return activeToolGroup; }
    public void setActiveToolGroup(String group) { this.activeToolGroup = group; }

    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }
    public Map<String, Object> getAttributes() { return Map.copyOf(attributes); }

    /** 重置为默认状态 */
    public void reset() {
        this.activeToolGroup = "default";
        this.attributes.clear();
    }
}

package cn.zcj.aether.domain.agent.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流边定义
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentEdge {

    // ====== 现有字段不变 ======
    private String workflowName;
    private AgentEdgeType type;
    private List<String> subAgents;
    private String description;

    @Builder.Default
    private Integer maxIterations = 3;

    // ====== P1-1 新增：GraphFlow 专用字段 ======

    /**
     * 边的起点节点名称（GRAPHFLOW 模式使用）。
     * 当 type=GRAPHFLOW 时，用 from/to 替代 subAgents 列表。
     */
    private String from;

    /**
     * 边的终点节点名称。
     */
    private String to;

    /**
     * 条件表达式（SpEL 语法）。
     * 只有条件求值为 true 时，此边才被激活。
     * 示例: "output.contains('错误')"、"#output.length() > 100"、"true"
     */
    private String condition;

    /**
     * 激活组语义：当 to 有多个父边时生效。
     * "all" — 所有父节点都完成后才激活（默认，fan-in）
     * "any" — 任意一个父节点完成即激活
     */
    @Builder.Default
    private String activation = "all";

    /**
     * 循环退出条件表达式（SpEL）。
     * 当循环边上的此条件为 true 时退出循环。
     * 示例: "output.contains('通过')"
     */
    private String exitCondition;

    // ====== M1 新增：消息路由字段 ======

    /**
     * 产生此边关联消息的 Agent 名称（对齐 MetaGPT cause_by）。
     * 当消息的 causeBy 匹配此字段时，边被激活并路由消息到目标 Agent。
     * 可为 null（不限制消息来源）。
     */
    private String causeBy;

    /**
     * Agent 订阅的主题列表（对齐 AutoGen Topic 发布订阅）。
     * 当消息的 topic 或 causeBy 匹配此列表时，目标 Agent 接收消息。
     * 支持通配符 "*" 匹配所有消息。
     * 可为 null 或空列表（不订阅任何主题）。
     */
    private List<String> watch;

    /** 事件驱动模式的等待超时（毫秒），默认 30000（30秒） */
    @Builder.Default
    private Integer eventTimeoutMs = 30_000;

    // ====== 兼容判断 ======

    /** 是否为 GraphFlow 模式的边 */
    public boolean isGraphFlow() {
        return type == AgentEdgeType.GRAPHFLOW;
    }
}

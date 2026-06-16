package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import lombok.Data;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DAG 中每个节点的运行时状态。
 * 灵感来源：AutoGen DiGraph 的拓扑倒计数 + 就绪队列算法。
 */
@Data
public class GraphFlowState {

    /** 节点定义 */
    private final AgentNodeDef nodeDef;

    /** 本节点有多少个入边（父节点数） */
    private final int totalParents;

    /** 已经完成的父节点计数（原子操作） */
    private final AtomicInteger completedParents = new AtomicInteger(0);

    /** 累积输出（来自多个父节点的输出合并） */
    private final List<String> accumulatedOutputs = Collections.synchronizedList(new ArrayList<>());

    /** 节点执行状态 */
    private volatile NodeStatus status = NodeStatus.PENDING;

    public GraphFlowState(AgentNodeDef nodeDef, int totalParents) {
        this.nodeDef = nodeDef;
        this.totalParents = totalParents;
    }

    /** 记录一个父节点完成，返回是否所有父节点都完成了 */
    public boolean recordParentCompletion(String output) {
        accumulatedOutputs.add(output);
        return completedParents.incrementAndGet() >= totalParents;
    }

    public boolean isReady() {
        return status == NodeStatus.PENDING && completedParents.get() >= totalParents;
    }

    public enum NodeStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    }
}

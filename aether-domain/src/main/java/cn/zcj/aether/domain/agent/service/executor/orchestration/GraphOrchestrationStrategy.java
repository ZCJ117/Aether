package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.FlowableEmitter;

/** Public contract for one edge-orchestration mode. */
public interface GraphOrchestrationStrategy {
    AgentEdgeType supports();

    void execute(AgentGraph graph, AgentEdge edge, String userId, String sessionId,
                 ExecutionState state, FlowableEmitter<RuntimeEvent> emitter);
}

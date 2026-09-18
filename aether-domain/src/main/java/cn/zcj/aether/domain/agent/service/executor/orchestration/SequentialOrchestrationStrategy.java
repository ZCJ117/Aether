package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;

/** SEQUENTIAL edge execution with output-to-input handoff. */
//NOTE 串行执行：每个子Agent的输出作为下一个子Agent的输入，形成链式传递。
@Slf4j
public final class SequentialOrchestrationStrategy implements GraphOrchestrationStrategy {
    private final OrchestrationServices services;

    public SequentialOrchestrationStrategy(OrchestrationServices services) {
        this.services = services;
    }

    @Override
    public AgentEdgeType supports() {
        return AgentEdgeType.SEQUENTIAL;
    }

    @Override
    public void execute(AgentGraph graph, AgentEdge edge, String userId, String sessionId,
                        ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {
        log.info("串行执行: {} subAgents={}", edge.getWorkflowName(), edge.getSubAgents());
        for (String agentName : edge.getSubAgents()) {
            AgentNodeDef def = graph.getAgentDefs().get(agentName);
            if (def == null) {
                log.warn("Agent not found: {}", agentName);
                continue;
            }
            AgentNodeDef resolved = resolve(def, state);
            String input = state.getLastOutput().isEmpty() ? "" : state.getLastOutput();
            InterventionContext ictx = services.buildInterventionContext(
                    InterventionContext.ChannelType.DIRECT, agentName, sessionId, userId,
                    edge.getType().name(), edge.getWorkflowName());
            if (!services.applyDirectInterception(input, ictx, emitter)) {
                log.warn("SEQUENTIAL 节点 [{}] 被拦截阻断，终止链路", agentName);
                break;
            }
            services.executeSingle(resolved, userId, sessionId + "-" + agentName,
                    input, state, emitter, agentName);
        }
    }

    private AgentNodeDef resolve(AgentNodeDef def, ExecutionState state) {
        return AgentNodeDef.builder()
                .name(def.getName())
                .instruction(state.resolveTemplate(def.getInstruction()))
                .description(def.getDescription())
                .outputKey(def.getOutputKey())
                .toolNames(def.getToolNames())
                .modelRef(def.getModelRef())
                .agentType(def.getAgentType())
                .build();
    }
}

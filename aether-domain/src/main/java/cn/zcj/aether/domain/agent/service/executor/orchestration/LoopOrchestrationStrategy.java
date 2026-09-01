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

/** LOOP edge execution with fixed-point convergence detection. */
@Slf4j
public final class LoopOrchestrationStrategy implements GraphOrchestrationStrategy {
    private final OrchestrationServices services;

    public LoopOrchestrationStrategy(OrchestrationServices services) {
        this.services = services;
    }

    @Override
    public AgentEdgeType supports() {
        return AgentEdgeType.LOOP;
    }

    @Override
    public void execute(AgentGraph graph, AgentEdge edge, String userId, String sessionId,
                        ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {
        int maxIter = edge.getMaxIterations() != null ? edge.getMaxIterations() : 3;
        log.info("循环执行: {} maxIterations={}", edge.getWorkflowName(), maxIter);
        String previousOutput = "";

        for (int i = 0; i < maxIter; i++) {
            String input = i == 0 ? "" : state.getLastOutput();
            for (String agentName : edge.getSubAgents()) {
                AgentNodeDef def = graph.getAgentDefs().get(agentName);
                if (def == null) continue;

                AgentNodeDef resolved = resolve(def, state);
                InterventionContext ictx = services.buildInterventionContext(
                        InterventionContext.ChannelType.DIRECT, agentName, sessionId, userId,
                        edge.getType().name(), edge.getWorkflowName());
                if (!services.applyDirectInterception(input, ictx, emitter)) {
                    log.warn("LOOP 节点 [{}] 被拦截阻断，终止循环", agentName);
                    return;
                }
                services.executeSingle(resolved, userId, sessionId + "-iter" + i + "-" + agentName,
                        input, state, emitter, agentName);
            }

            String currentOutput = state.getLastOutput();
            if (previousOutput.equals(currentOutput) && !currentOutput.isEmpty()) {
                log.info("循环收敛于迭代 #{}", i + 1);
                break;
            }
            previousOutput = currentOutput;
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

package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.subagent.ResultRefiner;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** SUBAGENT edge execution via the structured delegation orchestrator. */
@Slf4j
public final class SubAgentOrchestrationStrategy implements GraphOrchestrationStrategy {
    private final OrchestrationServices services;
    private final cn.zcj.aether.domain.agent.service.subagent.SubAgentOrchestrator subAgentOrchestrator;

    public SubAgentOrchestrationStrategy(OrchestrationServices services,
                                         cn.zcj.aether.domain.agent.service.subagent.SubAgentOrchestrator subAgentOrchestrator) {
        this.services = services;
        this.subAgentOrchestrator = subAgentOrchestrator;
    }

    @Override
    public AgentEdgeType supports() {
        return AgentEdgeType.SUBAGENT;
    }

    @Override
    public void execute(AgentGraph graph, AgentEdge edge, String userId, String sessionId,
                        ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {
        log.info("SUBAGENT 派遣: subAgents={}", edge.getSubAgents());
        for (String agentName : edge.getSubAgents()) {
            AgentNodeDef def = graph.getAgentDefs().get(agentName);
            if (def == null) {
                log.warn("SUBAGENT: Agent not found: {}", agentName);
                continue;
            }
            String task = def.getInstruction();
            if (task == null || task.isBlank()) {
                log.warn("SUBAGENT: Agent {} 无 instruction", agentName);
                continue;
            }

            InterventionContext ictx = services.buildInterventionContext(
                    InterventionContext.ChannelType.DIRECT, agentName, sessionId, userId,
                    edge.getType().name(), edge.getWorkflowName());
            if (!services.applyDirectInterception(task, ictx, emitter)) {
                log.warn("SUBAGENT 节点 [{}] 被拦截阻断", agentName);
                continue;
            }

            List<String> toolNames = def.getToolNames() != null ? def.getToolNames() : List.of();
            ResultRefiner.SubAgentResult result = subAgentOrchestrator.dispatch(
                    task, toolNames, null, def.getModelRef(), userId, sessionId);
            emitter.onNext(RuntimeEvent.text("[子Agent: " + agentName + "] " + result.summary()));

            String outputKey = def.getOutputKey() != null ? def.getOutputKey() : agentName;
            state.appendOutput(outputKey, result.summary());
            state.setFinalOutput(outputKey, result.summary());
            state.setLastAgentName(agentName);
            log.info("SUBAGENT 完成: agent={} status={}", agentName, result.status());
        }
    }
}

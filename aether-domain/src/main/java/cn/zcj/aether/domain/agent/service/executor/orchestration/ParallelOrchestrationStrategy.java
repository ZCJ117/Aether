package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** PARALLEL edge execution on the shared bounded graph pool. */
//NOTE 并行执行：所有子Agent同时启动，独立执行，最终结果按顺序收集。
@Slf4j
public final class ParallelOrchestrationStrategy implements GraphOrchestrationStrategy {
    private final OrchestrationServices services;

    public ParallelOrchestrationStrategy(OrchestrationServices services) {
        this.services = services;
    }

    @Override
    public AgentEdgeType supports() {
        return AgentEdgeType.PARALLEL;
    }

    @Override
    public void execute(AgentGraph graph, AgentEdge edge, String userId, String sessionId,
                        ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {
        List<String> agentNames = edge.getSubAgents();
        log.info("并行执行: {} subAgents={}", edge.getWorkflowName(), agentNames);

        CountDownLatch latch = new CountDownLatch(agentNames.size());
        List<ExecutionState> subStates = new CopyOnWriteArrayList<>();
        Map<String, String> mdcCtx = MDC.getCopyOfContextMap();

        for (String agentName : agentNames) {
            AgentNodeDef def = graph.getAgentDefs().get(agentName);
            if (def == null) {
                log.warn("Agent not found: {}", agentName);
                latch.countDown();
                continue;
            }

            AgentNodeDef resolved = resolve(def, state);
            ExecutionState localState = state.forkSource();
            InterventionContext ictx = services.buildInterventionContext(
                    InterventionContext.ChannelType.BROADCAST, agentName, sessionId, userId,
                    edge.getType().name(), edge.getWorkflowName());
            if (!services.applyBroadcastInterception(resolved.getInstruction(), ictx, agentName)) {
                latch.countDown();
                continue;
            }

            services.graphPoolOrDefault().submit(() -> runNode(graph, agentNames, agentName, def, resolved,
                    localState, subStates, userId, sessionId, emitter, mdcCtx, latch));
        }

        await(latch);
        for (ExecutionState sub : subStates) {
            for (String agentName : agentNames) {
                AgentNodeDef def = graph.getAgentDefs().get(agentName);
                if (def != null) {
                    state.merge(sub, def.getOutputKey());
                }
            }
        }
        log.info("并行执行完成: {} 个Agent, {} 个子状态已合并", agentNames.size(), subStates.size());
    }

    private void runNode(AgentGraph graph, List<String> agentNames, String agentName, AgentNodeDef def,
                         AgentNodeDef resolved, ExecutionState localState, List<ExecutionState> subStates,
                         String userId, String sessionId, FlowableEmitter<RuntimeEvent> emitter,
                         Map<String, String> mdcCtx, CountDownLatch latch) {
        if (mdcCtx != null) {
            MDC.setContextMap(mdcCtx);
        }
        try {
            Agent agent = services.createAgent(resolved);
            RuntimeContext ctx = new RuntimeContext(userId, sessionId + "-" + agentName, null, null,
                    "", null, null, agentName);
            services.notifyGraphHook(HookPoint.ON_GRAPH_NODE_START, HookContext.builder()
                    .agentId(agentName).sessionId(sessionId).graphNodeId(resolved.getOutputKey()).build());

            List<RuntimeEvent> agentEvents = new ArrayList<>();
            agent.execute(ctx).blockingForEach(event -> {
                agentEvents.add(event);
                synchronized (emitter) {
                    if (!emitter.isCancelled()) emitter.onNext(event);
                }
                if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                    localState.appendOutput(def.getOutputKey(), event.getText());
                }
            });

            localState.markComplete(def.getOutputKey(), localState.getText(def.getOutputKey()));
            subStates.add(localState);
            services.notifyGraphHook(HookPoint.ON_GRAPH_NODE_END, HookContext.builder()
                    .agentId(agentName).sessionId(sessionId).graphNodeId(resolved.getOutputKey())
                    .response(localState.getText(def.getOutputKey())).build());
            log.info("并行Agent完成: {} events={}", agentName, agentEvents.size());
        } catch (Exception e) {
            log.error("并行Agent失败: {}", agentName, e);
            synchronized (emitter) {
                if (!emitter.isCancelled()) {
                    emitter.onNext(RuntimeEvent.error("[" + agentName + "] " + e.getMessage()));
                }
            }
        } finally {
            if (mdcCtx != null) {
                MDC.setContextMap(mdcCtx);
            } else {
                MDC.clear();
            }
            latch.countDown();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            boolean done = latch.await(10, TimeUnit.MINUTES);
            if (!done) log.warn("并行执行超时，部分Agent未完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("并行执行被中断");
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

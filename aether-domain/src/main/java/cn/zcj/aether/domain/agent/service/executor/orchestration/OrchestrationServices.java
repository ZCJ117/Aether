package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionResult;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//NOTE  第 5 层：所有策略共享的底座 —— OrchestrationServices
// 五种策略 + DAG 调度器都不直接碰 Agent 创建、拦截、钩子，
// 而是通过 OrchestrationServices 统一访问，便于统一拦截、日志、钩子、线程池等行为。
/** Shared dependencies and behavior for the five graph orchestration strategies. */
@Slf4j
public final class OrchestrationServices {

    private final DefaultAgentFactory agentFactory;
    private final InterventionHandler interventionHandler;
    private final HookRegistry hookRegistry;
    private final ExecutorService graphPool;

    public OrchestrationServices(DefaultAgentFactory agentFactory,
                                 InterventionHandler interventionHandler,
                                 HookRegistry hookRegistry,
                                 ExecutorService graphPool) {
        this.agentFactory = agentFactory;
        this.interventionHandler = interventionHandler;
        this.hookRegistry = hookRegistry;
        this.graphPool = graphPool;
    }

    ExecutorService graphPoolOrDefault() {
        if (graphPool != null) {
            return graphPool;
        }
        return new ThreadPoolExecutor(4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "aether-graph");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    void notifyGraphHook(HookPoint point, HookContext ctx) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(point, ctx);
        }
    }

    Agent createAgent(AgentNodeDef def) {
        return agentFactory.create(AgentConfig.fromNodeDef(def));
    }

    InterventionContext buildInterventionContext(InterventionContext.ChannelType channelType,
                                                 String agentId, String sessionId, String userId,
                                                 String edgeType, String workflowName) {
        return new InterventionContext(channelType, agentId, sessionId, userId,
                edgeType, workflowName, Map.of());
    }

    boolean applyDirectInterception(String message, InterventionContext ctx,
                                    FlowableEmitter<RuntimeEvent> emitter) {
        if (interventionHandler == null) return true;
        try {
            InterventionResult result = interventionHandler.onSend(message, ctx);
            return handleInterventionResult(result, ctx, emitter);
        } catch (Exception e) {
            log.error("DIRECT 通道拦截异常: agent={}", ctx.agentId(), e);
            emitter.onNext(RuntimeEvent.error("拦截异常: " + e.getMessage()));
            return false;
        }
    }

    boolean applyBroadcastInterception(String message, InterventionContext ctx, String agentName) {
        if (interventionHandler == null) return true;
        try {
            InterventionResult result = interventionHandler.onPublish(message, ctx);
            return handleBroadcastResult(result, ctx, agentName);
        } catch (Exception e) {
            log.warn("BROADCAST 通道拦截异常 [{}]（已静默）: {}", agentName, e.getMessage());
            return false;
        }
    }

    private boolean handleBroadcastResult(InterventionResult result, InterventionContext ctx, String agentName) {
        if (result.isPass()) return true;
        if (result.isBlock()) {
            log.warn("BROADCAST 通道拦截 BLOCK 降级 DROP: agent={}, reason={}", agentName, result.reason());
            return false;
        }
        if (result.isDrop()) {
            log.info("BROADCAST 消息被丢弃: agent={}, reason={}", ctx.agentId(), result.reason());
            return false;
        }
        return true;
    }

    private boolean handleInterventionResult(InterventionResult result,
                                             InterventionContext ctx,
                                             FlowableEmitter<RuntimeEvent> emitter) {
        if (result.isPass()) return true;
        if (result.isBlock()) {
            log.warn("DIRECT 通道拦截阻断: agent={}, reason={}", ctx.agentId(), result.reason());
            emitter.onNext(RuntimeEvent.error(
                    "消息被拦截阻断: " + (result.reason() != null ? result.reason() : "未知原因")));
            return false;
        }
        if (result.isDrop()) {
            log.info("消息被丢弃: agent={}, reason={}", ctx.agentId(), result.reason());
            return false;
        }
        return true;
    }

    String buildNodeInput(AgentNodeDef def, List<String> parentOutputs, String initialMessage) {
        StringBuilder sb = new StringBuilder();
        if (initialMessage != null && !initialMessage.isEmpty()) {
            sb.append("任务: ").append(initialMessage).append("\n\n");
        }
        for (int i = 0; i < parentOutputs.size(); i++) {
            sb.append("上游输出").append(i + 1).append(": ").append(parentOutputs.get(i)).append("\n");
        }
        return sb.toString();
    }

    void executeSingle(AgentNodeDef def, String userId, String sessionId, String input,
                       ExecutionState state, FlowableEmitter<RuntimeEvent> emitter, String agentName) {
        AgentConfig agentConfig = AgentConfig.fromNodeDef(def);
        Agent agent = agentFactory.create(agentConfig);
        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, input, null, null,
                def.getName());

        String nodeId = agentName != null ? agentName : def.getName();
        notifyGraphHook(HookPoint.ON_GRAPH_NODE_START, HookContext.builder()
                .agentId(nodeId).sessionId(sessionId).graphNodeId(def.getOutputKey()).request(input).build());

        StringBuilder collectedOutput = new StringBuilder();
        try {
            agent.execute(ctx).blockingForEach(event -> {
                if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                    state.appendOutput(def.getOutputKey(), event.getText());
                    collectedOutput.append(event.getText());
                }
                emitter.onNext(event);
            });
        } finally {
            notifyGraphHook(HookPoint.ON_GRAPH_NODE_END, HookContext.builder()
                    .agentId(nodeId).sessionId(sessionId).graphNodeId(def.getOutputKey())
                    .response(collectedOutput.toString()).build());
        }

        if (interventionHandler != null && collectedOutput.length() > 0) {
            InterventionContext ictx = buildInterventionContext(
                    InterventionContext.ChannelType.DIRECT,
                    nodeId, sessionId, userId, def.getAgentType(), "response");
            try {
                InterventionResult responseResult = interventionHandler.onResponse(collectedOutput.toString(), ictx);
                if (responseResult.isBlock()) {
                    log.warn("DIRECT 通道响应被拦截阻断: agent={}, reason={}", agentName, responseResult.reason());
                } else if (responseResult.isDrop()) {
                    log.info("响应被丢弃: agent={}", agentName);
                }
            } catch (Exception e) {
                log.warn("onResponse 拦截异常: agent={}", agentName, e);
            }
        }

        state.setLastAgentName(def.getName());
        if (def.getOutputKey() != null) {
            state.setFinalOutput(def.getOutputKey(), state.getLastOutput());
        }
    }

    /** Stable low-visibility accessor used by GraphFlowCoordinator's audit-only support. */
    GraphFlowState.NodeStatus normalizeStatus(GraphFlowState.NodeStatus status) {
        return status == null ? GraphFlowState.NodeStatus.PENDING : status;
    }
}

package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import cn.zcj.aether.domain.agent.service.agent.observability.BackgroundReviewer;
import cn.zcj.aether.domain.agent.service.agent.observability.GraphExecutionRecorder;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.executor.orchestration.EventDrivenOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.GraphFlowCoordinator;
import cn.zcj.aether.domain.agent.service.executor.orchestration.GraphOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.LoopOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.OrchestrationServices;
import cn.zcj.aether.domain.agent.service.executor.orchestration.ParallelOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.SequentialOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.executor.orchestration.SubAgentOrchestrationStrategy;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.subagent.SubAgentOrchestrator;
import io.opentelemetry.api.trace.StatusCode;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Graph lifecycle boundary and dispatcher for five orchestration strategies.
 *
 * <p>P2-2.3: orchestration details moved to {@code orchestration.*}; this class
 * retains execution lifecycle (trace, hooks, MDC, error completion).</p>
 *
 * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
 * 面试举证点：{@code execute()}（行98）返回 {@code Flowable<RuntimeEvent>} 是统一流式出口；
 * 内部以 {@code Flowable.create} + {@code FlowableEmitter}（行153、168）逐事件发射 RuntimeEvent，
 * 五类编排策略（顺序/并行/循环/事件驱动/子代理，行174-187）共享同一 emitter 出口，下游
 * HTTP(SSE) 与 Kafka 桥均消费同一 Flowable 原语，进程内全链路统一。</p>
 */
@Slf4j
@Service
public class GraphExecutor {

    @Resource
    private DefaultAgentFactory agentFactory;

    @Resource
    private ConditionEvaluator conditionEvaluator;

    @Resource
    private SubAgentOrchestrator subAgentOrchestrator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InterventionHandler interventionHandler;

    @Resource
    private HookRegistry hookRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GraphExecutionRecorder graphExecutionRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BackgroundReviewer backgroundReviewer;

    @jakarta.annotation.Resource(name = "graphPool")
    private ExecutorService graphPool;

    private ExecutorService graphPoolOrDefault() {
        ExecutorService pool = this.graphPool;
        if (pool == null) {
            synchronized (this) {
                pool = this.graphPool;
                if (pool == null) {
                    pool = new ThreadPoolExecutor(4, 8, 60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(200),
                            r -> {
                                Thread t = new Thread(r, "aether-graph");
                                t.setDaemon(true);
                                return t;
                            },
                            new ThreadPoolExecutor.CallerRunsPolicy());
                    this.graphPool = pool;
                }
            }
        }
        return pool;
    }

    // 【流式】统一流式出口：返回 Flowable<RuntimeEvent>，全链路事件原语
    public Flowable<RuntimeEvent> execute(AgentGraph graph, String userId, String sessionId,
                                          String initialMessage) {
        return Flowable.create(emitter -> {
            String prevGraphId = MDC.get("graphExecutionId");
            String prevSessionId = MDC.get("sessionId");
            String graphExecutionId = null;
            AgentTracer.SpanScope graphSpan = null;
            boolean graphFailed = false;
            try {
                ExecutionState state = new ExecutionState();
                List<AgentEdge> edges = graph.getEdges();
                boolean isGraphFlow = edges.stream().anyMatch(AgentEdge::isGraphFlow);
                if (graphExecutionRecorder != null && isGraphFlow) {
                    graphExecutionId = graphExecutionRecorder.beginExecution(sessionId);
                    MDC.put("graphExecutionId", graphExecutionId);
                    MDC.put("sessionId", sessionId);
                    graphSpan = AgentTracer.startGraphExecution(graphExecutionId, sessionId);
                }

                if (edges.isEmpty() && graph.getEntryPoint() != null) {
                    executeEntryOnly(graph, userId, sessionId, initialMessage, state, emitter);
                    notifyFinalize(sessionId);
                    emitter.onComplete();
                    return;
                }

                if (dispatchEdges(graph, userId, sessionId, initialMessage, state, emitter, graphExecutionId)) {
                    return;
                }

                notifyFinalize(sessionId);
                emitter.onComplete();
            } catch (Exception e) {
                log.error("GraphExecutor error", e);
                graphFailed = true;
                if (graphSpan != null) graphSpan.span().setStatus(StatusCode.ERROR, e.getMessage());
                if (graphExecutionRecorder != null && graphExecutionId != null) {
                    graphExecutionRecorder.endExecution(graphExecutionId, e);
                }
                if (!emitter.isCancelled()) {
                    emitter.onNext(RuntimeEvent.error(e.getMessage()));
                    notifyFinalize(sessionId);
                    emitter.onComplete();
                }
            } finally {
                if (graphSpan != null) {
                    if (!graphFailed) graphSpan.span().setStatus(StatusCode.OK);
                    graphSpan.close();
                }
                restoreMdc(prevGraphId, prevSessionId, graphExecutionId);
            }
        }, BackpressureStrategy.BUFFER);
    }

    private boolean dispatchEdges(AgentGraph graph, String userId, String sessionId, String initialMessage,
                                  // 【流式】统一事件出口：所有编排策略共用同一 FlowableEmitter
                                  ExecutionState state, FlowableEmitter<RuntimeEvent> emitter,
                                  String graphExecutionId) {
        for (AgentEdge edge : graph.getEdges()) {
            if (edge.isGraphFlow()) {
                GraphFlowCoordinator coordinator = new GraphFlowCoordinator(
                        services(), conditionEvaluator, graphExecutionRecorder, backgroundReviewer);
                coordinator.execute(graph, userId, sessionId, initialMessage, emitter, graphExecutionId);
                return true;
            }
            // 【事件驱动】五类编排策略共享统一 emitter 出口，事件经同一 Flowable 流向下游
            strategyFor(edge.getType()).execute(graph, edge, userId, sessionId, state, emitter);
        }
        return false;
    }

    private void executeEntryOnly(AgentGraph graph, String userId, String sessionId, String initialMessage,
                                  ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {
        AgentEdge entry = AgentEdge.builder().workflowName("entry").subAgents(List.of(graph.getEntryPoint())).build();
        strategyFor(AgentEdgeType.SEQUENTIAL).execute(
                graph, entry, userId, sessionId, state, emitter);
    }

    private GraphOrchestrationStrategy strategyFor(AgentEdgeType type) {
        OrchestrationServices services = services();
        Map<AgentEdgeType, GraphOrchestrationStrategy> strategies = new EnumMap<>(AgentEdgeType.class);
        strategies.put(AgentEdgeType.SEQUENTIAL, new SequentialOrchestrationStrategy(services));
        strategies.put(AgentEdgeType.PARALLEL, new ParallelOrchestrationStrategy(services));
        strategies.put(AgentEdgeType.LOOP, new LoopOrchestrationStrategy(services));
        strategies.put(AgentEdgeType.SUBAGENT, new SubAgentOrchestrationStrategy(services, subAgentOrchestrator));
        strategies.put(AgentEdgeType.EVENT_DRIVEN, new EventDrivenOrchestrationStrategy(services));
        GraphOrchestrationStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("未支持的编排类型: " + type);
        }
        return strategy;
    }

    private OrchestrationServices services() {
        return new OrchestrationServices(agentFactory, interventionHandler, hookRegistry, graphPoolOrDefault());
    }

    private void notifyFinalize(String sessionId) {
        // Keep graph finalization local so error and success paths always use the same hook context.
        if (hookRegistry != null) {
            hookRegistry.invokeAll(HookPoint.ON_GRAPH_FINALIZE, HookContext.builder().sessionId(sessionId).build());
        }
    }

    private void restoreMdc(String prevGraphId, String prevSessionId, String graphExecutionId) {
        if (graphExecutionId == null) return;
        if (prevGraphId != null) MDC.put("graphExecutionId", prevGraphId);
        else MDC.remove("graphExecutionId");
        if (prevSessionId != null) MDC.put("sessionId", prevSessionId);
        else MDC.remove("sessionId");
    }
}

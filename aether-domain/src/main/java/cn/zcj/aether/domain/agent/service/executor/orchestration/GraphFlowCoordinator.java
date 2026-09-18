package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import cn.zcj.aether.domain.agent.service.agent.observability.BackgroundReviewer;
import cn.zcj.aether.domain.agent.service.agent.observability.GraphExecutionRecorder;
import cn.zcj.aether.domain.agent.service.executor.ConditionEvaluator;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.opentelemetry.api.trace.Span;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//NOTE DAG 前四种策略是"固定路线的带团方式"，GraphFlowCoordinator 是"拿到任意地铁线路图都能规划出乘车顺序"的导航——节点可并行时自动并行（同一批），
// 有依赖时自动等待（父节点计数），还支持条件边（condition）和循环退出（exitCondition）
// 标准的拓扑层调度
/** GRAPHFLOW DAG scheduler. Extracted from the original monolithic executor. */
@Slf4j
public final class GraphFlowCoordinator {
    private static final int MAX_ITERATIONS = 50;

    private final OrchestrationServices services;
    private final ConditionEvaluator conditionEvaluator;
    private final GraphExecutionRecorder recorder;
    private final BackgroundReviewer backgroundReviewer;

    public GraphFlowCoordinator(OrchestrationServices services,
                                ConditionEvaluator conditionEvaluator,
                                GraphExecutionRecorder recorder,
                                BackgroundReviewer backgroundReviewer) {
        this.services = services;
        this.conditionEvaluator = conditionEvaluator;
        this.recorder = recorder;
        this.backgroundReviewer = backgroundReviewer;
    }

    public void execute(AgentGraph graph, String userId, String sessionId, String initialMessage,
                        FlowableEmitter<RuntimeEvent> emitter, String graphExecutionId) {
        List<AgentEdge> edges = graph.getEdges();
        Map<String, AgentNodeDef> nodeDefs = graph.getAgentDefs();

        Map<String, List<Map.Entry<String, AgentEdge>>> children = new java.util.LinkedHashMap<>();
        Map<String, Integer> parentCount = new java.util.LinkedHashMap<>();
        for (String nodeName : nodeDefs.keySet()) {
            parentCount.put(nodeName, 0);
            children.put(nodeName, new ArrayList<>());
        }
        for (AgentEdge edge : edges) {
            if (!edge.isGraphFlow()) continue;
            children.get(edge.getFrom()).add(new java.util.AbstractMap.SimpleEntry<>(edge.getTo(), edge));
            parentCount.merge(edge.getTo(), 1, Integer::sum);
        }

        List<String> entryNodes = parentCount.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();
        if (entryNodes.isEmpty()) {
            emitter.onNext(RuntimeEvent.error("GraphFlow DAG 没有入口节点（可能存在循环依赖）"));
            services.notifyGraphHook(HookPoint.ON_GRAPH_FINALIZE,
                    HookContext.builder().sessionId(sessionId).build());
            emitter.onComplete();
            return;
        }

        Map<String, GraphFlowState> flowStates = new ConcurrentHashMap<>();
        for (Map.Entry<String, AgentNodeDef> entry : nodeDefs.entrySet()) {
            flowStates.put(entry.getKey(), new GraphFlowState(entry.getValue(), parentCount.get(entry.getKey())));
        }

        Map<String, String> nodeOutputs = new ConcurrentHashMap<>();
        Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
        Queue<String> readyQueue = new ConcurrentLinkedQueue<>(entryNodes);
        ExecutionState globalState = new ExecutionState();
        int iteration = 0;

        //NOTE 主循环：按拓扑顺序执行节点，处理条件分支和循环边，支持流式事件发射和后台审查。
        while (!readyQueue.isEmpty() && iteration < MAX_ITERATIONS) {
            iteration++;
            List<String> currentBatch = new ArrayList<>();
            String nodeName;
            while ((nodeName = readyQueue.poll()) != null) {
                currentBatch.add(nodeName);
            }
            if (currentBatch.isEmpty()) break;
            runBatch(currentBatch, flowStates, children, nodeOutputs, readyQueue, userId, sessionId,
                    initialMessage, emitter, graphExecutionId, mdcCtx, iteration);
        }

        for (Map.Entry<String, String> entry : nodeOutputs.entrySet()) {
            globalState.setFinalOutput(entry.getKey(), entry.getValue());
        }

        if (backgroundReviewer != null && graphExecutionId != null) {
            backgroundReviewer.submit(graphExecutionId, initialMessage, String.join("\n", nodeOutputs.values()));
        }
        emitter.onNext(RuntimeEvent.done());
        services.notifyGraphHook(HookPoint.ON_GRAPH_FINALIZE,
                HookContext.builder().sessionId(sessionId).build());
        emitter.onComplete();
    }

    private void runBatch(List<String> currentBatch, Map<String, GraphFlowState> flowStates,
                          Map<String, List<Map.Entry<String, AgentEdge>>> children,
                          Map<String, String> nodeOutputs, Queue<String> readyQueue,
                          String userId, String sessionId, String initialMessage,
                          FlowableEmitter<RuntimeEvent> emitter, String graphExecutionId,
                          Map<String, String> mdcCtx, int iteration) {
        CountDownLatch batchLatch = new CountDownLatch(currentBatch.size());
        for (String name : currentBatch) {
            GraphFlowState flowState = flowStates.get(name);
            flowState.setStatus(GraphFlowState.NodeStatus.RUNNING);
            Instant nodeStart = Instant.now();
            AgentNodeDef def = flowState.getNodeDef();
            Span nodeSpan = graphExecutionId != null
                    ? AgentTracer.startGraphNode(graphExecutionId, def.getAgentType(), name) : null;
            record(graphExecutionId, name, def.getAgentType(), GraphFlowState.NodeStatus.RUNNING,
                    nodeStart, null);
            InterventionContext ictx = services.buildInterventionContext(
                    InterventionContext.ChannelType.BROADCAST, name, sessionId, userId,
                    "GRAPHFLOW", "graphflow-batch-" + iteration);
            String input = services.buildNodeInput(def, flowState.getAccumulatedOutputs(), initialMessage);

            if (!services.applyBroadcastInterception(input, ictx, name)) {
                skip(flowState, name, nodeStart, graphExecutionId, nodeSpan, batchLatch);
                continue;
            }

            services.graphPoolOrDefault().execute(() -> {
                if (mdcCtx != null) MDC.setContextMap(mdcCtx);
                try {
                    executeNode(name, def, input, flowStates, children, nodeOutputs, readyQueue,
                            userId, sessionId, emitter, graphExecutionId, nodeStart, nodeSpan);
                } catch (Exception e) {
                    failNode(flowState, name, nodeStart, graphExecutionId, nodeSpan, e, emitter);
                } finally {
                    if (mdcCtx != null) MDC.setContextMap(mdcCtx);
                    else MDC.clear();
                    batchLatch.countDown();
                }
            });
        }

        try {
            boolean ok = batchLatch.await(10, TimeUnit.MINUTES);
            if (!ok) log.warn("GraphFlow 批次超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeNode(String name, AgentNodeDef def, String input,
                             Map<String, GraphFlowState> flowStates,
                             Map<String, List<Map.Entry<String, AgentEdge>>> children,
                             Map<String, String> nodeOutputs, Queue<String> readyQueue,
                             String userId, String sessionId, FlowableEmitter<RuntimeEvent> emitter,
                             String graphExecutionId, Instant nodeStart, Span nodeSpan) {
        GraphFlowState flowState = flowStates.get(name);
        Agent agent = services.createAgent(def);
        RuntimeContext ctx = new RuntimeContext(userId, sessionId + "-" + name, null, null, input,
                null, null, name);
        services.notifyGraphHook(HookPoint.ON_GRAPH_NODE_START, HookContext.builder()
                .agentId(name).sessionId(sessionId).graphNodeId(def.getOutputKey()).build());

        ExecutionState localState = new ExecutionState();
        agent.execute(ctx).blockingForEach(event -> {
            synchronized (emitter) {
                emitter.onNext(event);
            }
            if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                localState.appendOutput(def.getOutputKey(), event.getText());
            }
        });

        String output = localState.getOutput(def.getOutputKey());
        nodeOutputs.put(name, output);
        flowState.setStatus(GraphFlowState.NodeStatus.COMPLETED);
        record(graphExecutionId, name, def.getAgentType(), GraphFlowState.NodeStatus.COMPLETED,
                nodeStart, null);
        if (nodeSpan != null) AgentTracer.endGraphNode(nodeSpan, true, null);
        services.notifyGraphHook(HookPoint.ON_GRAPH_NODE_END, HookContext.builder()
                .agentId(name).sessionId(sessionId).graphNodeId(def.getOutputKey())
                .response(output).build());

        synchronized (flowStates) {
            routeChildren(name, output, children, flowStates, readyQueue);
        }
    }

    private void routeChildren(String name, String output,
                               Map<String, List<Map.Entry<String, AgentEdge>>> children,
                               Map<String, GraphFlowState> flowStates,
                               Queue<String> readyQueue) {
        for (Map.Entry<String, AgentEdge> childEntry : children.get(name)) {
            String childName = childEntry.getKey();
            AgentEdge childEdge = childEntry.getValue();
            if (!conditionEvaluator.evaluate(childEdge.getCondition(), output)) {
                log.debug("边 [{}→{}] 条件不满足，跳过", name, childName);
                continue;
            }
            GraphFlowState childState = flowStates.get(childName);
            if (childState == null) continue;
            boolean allParentsDone = childState.recordParentCompletion(output);
            String activation = childEdge.getActivation() != null ? childEdge.getActivation() : "all";
            boolean shouldActivate = "any".equalsIgnoreCase(activation) || allParentsDone;
            if (!shouldActivate || childState.getStatus() != GraphFlowState.NodeStatus.PENDING) continue;
            if (childEdge.getExitCondition() != null
                    && conditionEvaluator.evaluate(childEdge.getExitCondition(), output)) {
                log.info("循环边 [{}→{}] 满足退出条件", name, childName);
                childState.setStatus(GraphFlowState.NodeStatus.SKIPPED);
            } else {
                readyQueue.offer(childName);
            }
        }
    }

    private void skip(GraphFlowState flowState, String name, Instant nodeStart, String graphExecutionId,
                      Span nodeSpan, CountDownLatch latch) {
        flowState.setStatus(GraphFlowState.NodeStatus.SKIPPED);
        record(graphExecutionId, name, flowState.getNodeDef().getAgentType(),
                GraphFlowState.NodeStatus.SKIPPED, nodeStart, null);
        if (nodeSpan != null) AgentTracer.endGraphNode(nodeSpan, true, null);
        latch.countDown();
    }

    private void failNode(GraphFlowState flowState, String name, Instant nodeStart, String graphExecutionId,
                          Span nodeSpan, Exception e, FlowableEmitter<RuntimeEvent> emitter) {
        log.error("GraphFlow 节点 [{}] 执行失败", name, e);
        flowState.setStatus(GraphFlowState.NodeStatus.FAILED);
        record(graphExecutionId, name, flowState.getNodeDef().getAgentType(),
                GraphFlowState.NodeStatus.FAILED, nodeStart, e.getMessage());
        if (nodeSpan != null) AgentTracer.endGraphNode(nodeSpan, false, e.getMessage());
        synchronized (emitter) {
            emitter.onNext(RuntimeEvent.error("节点 [" + name + "] 失败: " + e.getMessage()));
        }
    }

    private void record(String graphExecutionId, String name, String agentType,
                        GraphFlowState.NodeStatus status, Instant startedAt, String error) {
        if (recorder == null || graphExecutionId == null) return;
        Instant endedAt = status == GraphFlowState.NodeStatus.RUNNING ? null : Instant.now();
        long duration = status == GraphFlowState.NodeStatus.RUNNING ? 0
                : Duration.between(startedAt, Instant.now()).toMillis();
        recorder.recordNodeEvent(graphExecutionId, name, agentType, status, startedAt, endedAt, duration, error);
    }
}

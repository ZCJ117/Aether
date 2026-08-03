package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionResult;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.subagent.ResultRefiner;
import cn.zcj.aether.domain.agent.service.subagent.SubAgentOrchestrator;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;

/**
 * 多Agent图执行器。
 *
 * <p>H4-步骤7 更新：集成通道差异化拦截机制。
 * <ul>
 *   <li><b>DIRECT 通道</b>（SEQUENTIAL / LOOP / SUBAGENT）：
 *       拦截异常回传调用方（Future.setException），阻断当前链路。</li>
 *   <li><b>BROADCAST 通道</b>（PARALLEL / GRAPHFLOW 并发批次）：
 *       拦截异常只记日志静默丢弃，不波及其他并发节点。
 *       对齐 AutoGen _single_threaded_agent_runtime.py L748-750。</li>
 * </ul>
 *
 * <p>SEQUENTIAL → 串行推进，{outputKey} 传递
 * <br>PARALLEL   → 多线程并发，事件实时转发到 emitter
 * <br>LOOP       → 循环直到收敛或达到 maxIterations
 * <br>GRAPHFLOW  → DAG 拓扑排序 + 就绪队列
 * <br>SUBAGENT   → 子Agent派遣
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

    /** H4-步骤7: 拦截处理器（可选注入，无 Bean 时为 null） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InterventionHandler interventionHandler;

    private final ExecutorService parallelPool = Executors.newCachedThreadPool();

    // ========== 主入口 ==========

    public Flowable<RuntimeEvent> execute(
            AgentGraph graph,
            String userId,
            String sessionId,
            String initialMessage) {

        return Flowable.create(emitter -> {
            try {
                ExecutionState state = new ExecutionState();
                List<AgentEdge> edges = graph.getEdges();
                Map<String, AgentNodeDef> agentDefs = graph.getAgentDefs();

                if (edges.isEmpty() && graph.getEntryPoint() != null) {
                    AgentNodeDef entry = agentDefs.get(graph.getEntryPoint());
                    if (entry != null) {
                        executeSingle(entry, userId, sessionId,
                                initialMessage, state, emitter, "entry");
                    }
                    emitter.onComplete();
                    return;
                }

                for (AgentEdge edge : edges) {
                    if (edge.isGraphFlow()) {
                        executeGraphFlow(graph, userId, sessionId, initialMessage, emitter);
                        return;
                    }
                    switch (edge.getType()) {
                        case SEQUENTIAL -> executeSequential(
                                graph, edge, userId, sessionId, state, emitter);
                        case PARALLEL -> executeParallel(
                                graph, edge, userId, sessionId, state, emitter);
                        case LOOP -> executeLoop(
                                graph, edge, userId, sessionId, state, emitter);
                        case SUBAGENT -> executeSubAgents(
                                graph, edge, userId, sessionId, state, emitter);
                        case EVENT_DRIVEN -> executeEventDriven(
                                graph, edge, userId, sessionId, state, emitter);
                    }
                }

                emitter.onComplete();
            } catch (Exception e) {
                log.error("GraphExecutor error", e);
                if (!emitter.isCancelled()) {
                    emitter.onNext(RuntimeEvent.error(e.getMessage()));
                    emitter.onComplete();
                }
            }
        }, BackpressureStrategy.BUFFER);
    }

    // ========== SEQUENTIAL (DIRECT 通道) ==========

    private void executeSequential(
            AgentGraph graph, AgentEdge edge,
            String userId, String sessionId,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        log.info("串行执行: {} subAgents={}", edge.getWorkflowName(), edge.getSubAgents());

        for (String agentName : edge.getSubAgents()) {
            AgentNodeDef def = graph.getAgentDefs().get(agentName);
            if (def == null) {
                log.warn("Agent not found: {}", agentName);
                continue;
            }

            String resolvedInstruction = state.resolveTemplate(def.getInstruction());
            AgentNodeDef resolved = AgentNodeDef.builder()
                    .name(def.getName())
                    .instruction(resolvedInstruction)
                    .description(def.getDescription())
                    .outputKey(def.getOutputKey())
                    .toolNames(def.getToolNames())
                    .modelRef(def.getModelRef())
                    .agentType(def.getAgentType())
                    .build();

            String input = state.getLastOutput().isEmpty()
                    ? "" : state.getLastOutput();

            // H4-步骤7: DIRECT 通道拦截
            InterventionContext ictx = buildInterventionContext(
                    InterventionContext.ChannelType.DIRECT,
                    agentName, sessionId, userId,
                    edge.getType().name(), edge.getWorkflowName());

            boolean shouldProceed = applyDirectInterception(input, ictx, emitter);
            if (!shouldProceed) {
                log.warn("SEQUENTIAL 节点 [{}] 被拦截阻断，终止链路", agentName);
                break; // BLOCK 语义：终止当前 SEQUENTIAL 链路
            }

            executeSingle(resolved, userId,
                    sessionId + "-" + agentName, input, state, emitter, agentName);
        }
    }

    // ========== PARALLEL (BROADCAST 通道) ==========

    private void executeParallel(
            AgentGraph graph, AgentEdge edge,
            String userId, String sessionId,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        List<String> agentNames = edge.getSubAgents();
        int count = agentNames.size();
        log.info("并行执行: {} subAgents={}", edge.getWorkflowName(), agentNames);

        CountDownLatch latch = new CountDownLatch(count);
        List<ExecutionState> subStates = new CopyOnWriteArrayList<>();

        for (String agentName : agentNames) {
            AgentNodeDef def = graph.getAgentDefs().get(agentName);
            if (def == null) {
                log.warn("Agent not found: {}", agentName);
                latch.countDown();
                continue;
            }

            String resolvedInstruction = state.resolveTemplate(def.getInstruction());
            AgentNodeDef resolved = AgentNodeDef.builder()
                    .name(def.getName())
                    .instruction(resolvedInstruction)
                    .description(def.getDescription())
                    .outputKey(def.getOutputKey())
                    .toolNames(def.getToolNames())
                    .modelRef(def.getModelRef())
                    .agentType(def.getAgentType())
                    .build();

            ExecutionState localState = state.forkSource();

            // H4-步骤7: BROADCAST 通道拦截 — 仅记日志，不波及其他节点
            InterventionContext ictx = buildInterventionContext(
                    InterventionContext.ChannelType.BROADCAST,
                    agentName, sessionId, userId,
                    edge.getType().name(), edge.getWorkflowName());

            boolean shouldProceed = applyBroadcastInterception(
                    resolvedInstruction, ictx, agentName);
            if (!shouldProceed) {
                // BROADCAST 通道：DROP 语义，仅跳过当前节点
                latch.countDown();
                continue;
            }

            parallelPool.submit(() -> {
                try {
                    AgentConfig agentConfig = AgentConfig.fromNodeDef(resolved);
                    Agent agent = agentFactory.create(agentConfig);
                    RuntimeContext ctx = new RuntimeContext(userId,
                            sessionId + "-" + agentName, null, null, "", null, null);

                    List<RuntimeEvent> agentEvents = new ArrayList<>();
                    agent.execute(ctx)
                            .blockingForEach(event -> {
                                agentEvents.add(event);
                                synchronized (emitter) {
                                    if (!emitter.isCancelled()) {
                                        emitter.onNext(event);
                                    }
                                }
                                if (event.getType() == RuntimeEvent.EventType.textDelta
                                        && event.getText() != null) {
                                    localState.appendOutput(def.getOutputKey(), event.getText());
                                }
                            });

                    localState.markComplete(def.getOutputKey(), localState.getText(def.getOutputKey()));
                    subStates.add(localState);
                    log.info("并行Agent完成: {} events={}", agentName, agentEvents.size());
                } catch (Exception e) {
                    log.error("并行Agent失败: {}", agentName, e);
                    synchronized (emitter) {
                        if (!emitter.isCancelled()) {
                            emitter.onNext(RuntimeEvent.error(
                                    "[" + agentName + "] " + e.getMessage()));
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean done = latch.await(10, TimeUnit.MINUTES);
            if (!done) {
                log.warn("并行执行超时，部分Agent未完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("并行执行被中断");
        }

        for (ExecutionState sub : subStates) {
            for (String agentName : agentNames) {
                AgentNodeDef def = graph.getAgentDefs().get(agentName);
                if (def != null) {
                    state.merge(sub, def.getOutputKey());
                }
            }
        }

        log.info("并行执行完成: {} 个Agent, {} 个子状态已合并",
                agentNames.size(), subStates.size());
    }

    // ========== LOOP (DIRECT 通道) ==========

    private void executeLoop(
            AgentGraph graph, AgentEdge edge,
            String userId, String sessionId,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        int maxIter = edge.getMaxIterations() != null ? edge.getMaxIterations() : 3;
        log.info("循环执行: {} maxIterations={}", edge.getWorkflowName(), maxIter);

        String previousOutput = "";

        for (int i = 0; i < maxIter; i++) {
            String input = i == 0 ? "" : state.getLastOutput();

            for (String agentName : edge.getSubAgents()) {
                AgentNodeDef def = graph.getAgentDefs().get(agentName);
                if (def == null) continue;

                String resolvedInstruction = state.resolveTemplate(def.getInstruction());
                AgentNodeDef resolved = AgentNodeDef.builder()
                        .name(def.getName())
                        .instruction(resolvedInstruction)
                        .description(def.getDescription())
                        .outputKey(def.getOutputKey())
                        .toolNames(def.getToolNames())
                        .modelRef(def.getModelRef())
                        .agentType(def.getAgentType())
                        .build();

                // H4-步骤7: DIRECT 通道拦截
                InterventionContext ictx = buildInterventionContext(
                        InterventionContext.ChannelType.DIRECT,
                        agentName, sessionId, userId,
                        edge.getType().name(), edge.getWorkflowName());

                boolean shouldProceed = applyDirectInterception(input, ictx, emitter);
                if (!shouldProceed) {
                    log.warn("LOOP 节点 [{}] 被拦截阻断，终止循环", agentName);
                    return; // BLOCK 语义：终止循环
                }

                executeSingle(resolved, userId,
                        sessionId + "-iter" + i + "-" + agentName,
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

    // ========== SUBAGENT (DIRECT 通道) ==========
    //
    // M2 (委派即工具): 子Agent派遣现在有两条路径:
    //   1. 结构路径(本方法) — GraphExecutor 按 YAML 配置的 SUBAGENT edge 派遣，LLM 无感知
    //   2. Tool 路径 — LLM 通过 tool_use 调用 SubAgentDelegationTool，
    //      自动走 ToolExecutor → 两级校验 → 权限检查 → 执行，LLM 自主决策何时委派
    // 两条路径互补共存，向后兼容。

    private void executeSubAgents(
            AgentGraph graph, AgentEdge edge,
            String userId, String sessionId,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        List<String> agentNames = edge.getSubAgents();
        log.info("SUBAGENT 派遣: subAgents={}", agentNames);

        for (String agentName : agentNames) {
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

            // H4-步骤7: DIRECT 通道拦截
            InterventionContext ictx = buildInterventionContext(
                    InterventionContext.ChannelType.DIRECT,
                    agentName, sessionId, userId,
                    edge.getType().name(), edge.getWorkflowName());

            boolean shouldProceed = applyDirectInterception(task, ictx, emitter);
            if (!shouldProceed) {
                log.warn("SUBAGENT 节点 [{}] 被拦截阻断", agentName);
                continue;
            }

            List<String> toolNames = def.getToolNames() != null
                    ? def.getToolNames() : List.of();

            ResultRefiner.SubAgentResult result = subAgentOrchestrator.dispatch(
                    task, toolNames, null, def.getModelRef(), userId, sessionId);

            String summaryText = "[子Agent: " + agentName + "] " + result.summary();
            emitter.onNext(RuntimeEvent.text(summaryText));

            String outputKey = def.getOutputKey() != null
                    ? def.getOutputKey() : agentName;
            state.appendOutput(outputKey, result.summary());
            state.setFinalOutput(outputKey, result.summary());
            state.setLastAgentName(agentName);

            log.info("SUBAGENT 完成: agent={} status={}", agentName, result.status());
        }
    }

    // ========== EVENT_DRIVEN (M1 新增：基于 cause_by/watch 订阅路由) ==========

    /**
     * 事件驱动执行模式。
     *
     * <p>Agent 通过声明式 {@code watch} 订阅接收消息，
     * SubscriptionRouter 根据消息的 {@code topic} 和 {@code causeBy} 投递到订阅者邮箱。
     * 对齐 MetaGPT 的双重过滤 + AutoGen Topic 发布订阅。
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>构建订阅声明映射：从 edge.watch + nodeDef.subscriptions 合并</li>
     *   <li>为每个订阅者创建有界邮箱</li>
     *   <li>发送初始消息到匹配的订阅者邮箱</li>
     *   <li>循环：排空订阅者邮箱 → 执行 Agent → 发布输出到下游订阅者</li>
     *   <li>所有邮箱空且无活跃 Agent 时退出</li>
     * </ol>
     */
    private void executeEventDriven(
            AgentGraph graph, AgentEdge edge,
            String userId, String sessionId,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        List<String> agentNames = edge.getSubAgents() != null
                ? edge.getSubAgents() : List.of();
        if (agentNames.isEmpty()) {
            log.warn("EVENT_DRIVEN: subAgents 列表为空，跳过");
            return;
        }

        Map<String, AgentNodeDef> agentDefs = graph.getAgentDefs();
        Map<String, List<String>> watches = buildWatchMap(graph, edge, agentNames);
        SubscriptionRouter router = new SubscriptionRouter();

        // 发送初始消息（initialMessage 作为无 topic 的广播）
        if (edge.getCauseBy() != null || edge.getWatch() != null) {
            MessageEnvelope initialMsg = MessageEnvelope.broadcast(
                    "init", edge.getCauseBy() != null ? edge.getCauseBy() : "system",
                    sessionId, "graph-executor");
            router.route(initialMsg, state.agentMailboxes, watches);
        }

        int maxIterations = edge.getMaxIterations() != null
                ? edge.getMaxIterations() : 10;
        int eventTimeoutMs = edge.getEventTimeoutMs() != null
                ? edge.getEventTimeoutMs() : 30_000;

        for (int iter = 0; iter < maxIterations; iter++) {
            boolean anyExecuted = false;

            for (String agentName : agentNames) {
                long pollTimeout = iter == 0 ? eventTimeoutMs : 1000;
                List<MessageEnvelope> msgs = state.drainMailbox(agentName, pollTimeout);
                if (msgs.isEmpty()) continue;

                anyExecuted = true;
                // 合并接收到的消息作为 Agent 输入
                String combinedInput = msgs.stream()
                        .map(m -> "[来自 " + m.causeBy() + "] " + m.content())
                        .reduce((a, b) -> a + "\n" + b).orElse("");

                AgentNodeDef def = agentDefs.get(agentName);
                if (def == null) {
                    log.warn("EVENT_DRIVEN: Agent 未找到: {}", agentName);
                    continue;
                }

                log.info("EVENT_DRIVEN: 执行 agent={} messagesCount={} topic={}",
                        agentName, msgs.size(),
                        msgs.stream().map(MessageEnvelope::topic)
                                .filter(Objects::nonNull).findFirst().orElse("none"));

                String instruction = state.resolveTemplate(
                        def.getInstruction() != null ? def.getInstruction() : "");
                String input = instruction.isEmpty() ? combinedInput
                        : instruction + "\n\n" + combinedInput;

                // DIRECT 通道：拦截异常回传
                InterventionContext ictx = new InterventionContext(
                        InterventionContext.ChannelType.DIRECT,
                        agentName, sessionId, userId,
                        AgentEdgeType.EVENT_DRIVEN.name(),
                        edge.getWorkflowName(), Map.of());
                if (applyDirectInterception(input, ictx, emitter)) continue;

                executeSingle(def, userId, sessionId, input, state, emitter, agentName);

                // 执行完成后，将输出作为消息发布到匹配的订阅者
                String output = state.getLastOutput();
                if (output != null && !output.isBlank()) {
                    MessageEnvelope outMsg = MessageEnvelope.create(
                            output, agentName,
                            def.getOutputKey(),  // 用 outputKey 作为 topic
                            sessionId, agentName);
                    int delivered = router.route(outMsg, state.agentMailboxes, watches);
                    log.debug("EVENT_DRIVEN: agent={} 发布消息到 {} 个订阅者", agentName, delivered);
                }
            }

            if (!anyExecuted) {
                log.info("EVENT_DRIVEN: 无活跃 Agent，第 {} 轮退出", iter);
                break;
            }
        }
    }

    /**
     * 构建订阅声明映射（agentName → watch 主题列表）。
     * 合并 AgentEdge.watch 和 AgentNodeDef.subscriptions。
     */
    private Map<String, List<String>> buildWatchMap(
            AgentGraph graph, AgentEdge edge, List<String> agentNames) {
        Map<String, List<String>> watches = new LinkedHashMap<>();
        Map<String, AgentNodeDef> agentDefs = graph.getAgentDefs();

        for (String agentName : agentNames) {
            List<String> merged = new ArrayList<>();

            // 边级别的 watch（所有 subAgents 共享）
            if (edge.getWatch() != null) {
                merged.addAll(edge.getWatch());
            }

            // Agent 级别的 subscriptions
            AgentNodeDef def = agentDefs.get(agentName);
            if (def != null && def.getSubscriptions() != null) {
                merged.addAll(def.getSubscriptions());
            }

            if (!merged.isEmpty()) {
                watches.put(agentName, List.copyOf(merged));
            }
        }

        log.info("EVENT_DRIVEN 订阅声明: agents={} subscribers={}",
                agentNames, watches.keySet());
        return watches;
    }

    // ========== GRAPHFLOW (BROADCAST 通道 per 并发批次) ==========

    private void executeGraphFlow(AgentGraph graph, String userId, String sessionId,
            String initialMessage, FlowableEmitter<RuntimeEvent> emitter) {

        List<AgentEdge> edges = graph.getEdges();
        Map<String, AgentNodeDef> nodeDefs = graph.getAgentDefs();

        // 构建邻接表
        Map<String, List<Map.Entry<String, AgentEdge>>> children = new LinkedHashMap<>();
        Map<String, Integer> parentCount = new LinkedHashMap<>();

        for (String nodeName : nodeDefs.keySet()) {
            parentCount.put(nodeName, 0);
            children.put(nodeName, new ArrayList<>());
        }

        for (AgentEdge edge : edges) {
            if (!edge.isGraphFlow()) continue;
            String from = edge.getFrom();
            String to = edge.getTo();
            children.get(from).add(new AbstractMap.SimpleEntry<>(to, edge));
            parentCount.merge(to, 1, Integer::sum);
        }

        // 找到入口节点
        List<String> entryNodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : parentCount.entrySet()) {
            if (entry.getValue() == 0) {
                entryNodes.add(entry.getKey());
            }
        }
        if (entryNodes.isEmpty()) {
            emitter.onNext(RuntimeEvent.error("GraphFlow DAG 没有入口节点（可能存在循环依赖）"));
            emitter.onComplete();
            return;
        }

        // 创建运行时状态
        Map<String, GraphFlowState> flowStates = new ConcurrentHashMap<>();
        for (Map.Entry<String, AgentNodeDef> entry : nodeDefs.entrySet()) {
            flowStates.put(entry.getKey(),
                new GraphFlowState(entry.getValue(), parentCount.get(entry.getKey())));
        }

        Map<String, String> nodeOutputs = new ConcurrentHashMap<>();
        Queue<String> readyQueue = new ConcurrentLinkedQueue<>(entryNodes);
        ExecutionState globalState = new ExecutionState();

        int maxIterations = 50;
        int iteration = 0;

        while (!readyQueue.isEmpty() && iteration < maxIterations) {
            iteration++;

            List<String> currentBatch = new ArrayList<>();
            String nodeName;
            while ((nodeName = readyQueue.poll()) != null) {
                currentBatch.add(nodeName);
            }

            if (currentBatch.isEmpty()) break;

            // 并发执行本轮所有就绪节点
            CountDownLatch batchLatch = new CountDownLatch(currentBatch.size());

            for (String name : currentBatch) {
                GraphFlowState flowState = flowStates.get(name);
                flowState.setStatus(GraphFlowState.NodeStatus.RUNNING);
                AgentNodeDef def = flowState.getNodeDef();

                // H4-步骤7: GRAPHFLOW 并发批次 → BROADCAST 通道
                // 每个节点在执行前做广播拦截，失败的节点静默跳过
                InterventionContext ictx = buildInterventionContext(
                        InterventionContext.ChannelType.BROADCAST,
                        name, sessionId, userId,
                        "GRAPHFLOW", "graphflow-batch-" + iteration);

                List<String> parentOutputs = flowState.getAccumulatedOutputs();
                String input = buildNodeInput(def, parentOutputs, initialMessage);

                boolean shouldProceed = applyBroadcastInterception(input, ictx, name);
                if (!shouldProceed) {
                    // BROADCAST 通道：DROP 语义，仅跳过当前节点
                    flowState.setStatus(GraphFlowState.NodeStatus.SKIPPED);
                    batchLatch.countDown();
                    continue;
                }

                new Thread(() -> {
                    try {
                        ExecutionState localState = globalState.forkSource();
                        AgentConfig agentConfig = AgentConfig.fromNodeDef(def);
                        Agent agent = agentFactory.create(agentConfig);
                        RuntimeContext ctx = new RuntimeContext(userId,
                            sessionId + "-" + name, null, null, input, null, null);

                        agent.execute(ctx)
                            .blockingForEach(event -> {
                                synchronized (emitter) {
                                    emitter.onNext(event);
                                }
                                if (event.getType() == RuntimeEvent.EventType.textDelta
                                        && event.getText() != null) {
                                    localState.appendOutput(def.getOutputKey(), event.getText());
                                }
                            });

                        String output = localState.getOutput(def.getOutputKey());
                        nodeOutputs.put(name, output);
                        flowState.setStatus(GraphFlowState.NodeStatus.COMPLETED);

                        // 按边条件路由到子节点
                        synchronized (flowStates) {
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

                                String activation = childEdge.getActivation() != null
                                    ? childEdge.getActivation() : "all";
                                boolean shouldActivate = "any".equalsIgnoreCase(activation)
                                    ? true : allParentsDone;

                                if (shouldActivate && childState.getStatus() == GraphFlowState.NodeStatus.PENDING) {
                                    if (childEdge.getExitCondition() != null
                                        && conditionEvaluator.evaluate(childEdge.getExitCondition(), output)) {
                                        log.info("循环边 [{}→{}] 满足退出条件", name, childName);
                                        childState.setStatus(GraphFlowState.NodeStatus.SKIPPED);
                                    } else {
                                        readyQueue.offer(childName);
                                    }
                                }
                            }
                        }

                    } catch (Exception e) {
                        log.error("GraphFlow 节点 [{}] 执行失败", name, e);
                        flowState.setStatus(GraphFlowState.NodeStatus.FAILED);
                        synchronized (emitter) {
                            emitter.onNext(RuntimeEvent.error("节点 [" + name + "] 失败: " + e.getMessage()));
                        }
                    } finally {
                        batchLatch.countDown();
                    }
                }, "graphflow-" + name).start();
            }

            try {
                boolean ok = batchLatch.await(10, TimeUnit.MINUTES);
                if (!ok) log.warn("GraphFlow 批次超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (Map.Entry<String, String> entry : nodeOutputs.entrySet()) {
            globalState.setFinalOutput(entry.getKey(), entry.getValue());
        }

        emitter.onNext(RuntimeEvent.done());
        emitter.onComplete();
    }

    // ========== 拦截辅助方法 ==========

    /**
     * DIRECT 通道拦截 —— BLOCK 结果以异常形式回传调用方。
     *
     * @return true=放行，false=被阻断/DROP
     */
    private boolean applyDirectInterception(String message, InterventionContext ctx,
            FlowableEmitter<RuntimeEvent> emitter) {
        if (interventionHandler == null) return true;

        try {
            InterventionResult result = interventionHandler.onSend(message, ctx);
            return handleInterventionResult(result, ctx, emitter);
        } catch (Exception e) {
            log.error("DIRECT 通道拦截异常: agent={}", ctx.agentId(), e);
            // 拦截器本身的异常：阻塞链路，回传错误
            emitter.onNext(RuntimeEvent.error("拦截异常: " + e.getMessage()));
            return false;
        }
    }

    /**
     * BROADCAST 通道拦截 —— BLOCK 降级为 DROP + 日志。
     *
     * @return true=放行，false=被丢弃
     */
    private boolean applyBroadcastInterception(String message, InterventionContext ctx,
            String agentName) {
        if (interventionHandler == null) return true;

        try {
            InterventionResult result = interventionHandler.onPublish(message, ctx);
            if (result.isPass()) return true;

            if (result.isBlock()) {
                // BROADCAST 通道：BLOCK 降级为 DROP
                log.warn("BROADCAST 通道拦截 [{}] 降级为丢弃: reason={}",
                        agentName, result.reason());
                return false;
            }

            if (result.isDrop()) {
                log.info("BROADCAST 通道消息被丢弃: agent={}, reason={}",
                        agentName, result.reason());
                return false;
            }

            return true;
        } catch (Exception e) {
            // BROADCAST 通道：拦截器异常只记日志，不影响其他节点
            log.warn("BROADCAST 通道拦截异常 [{}]（已静默）: {}", agentName, e.getMessage());
            return false;
        }
    }

    /**
     * 统一处理拦截结果。
     */
    private boolean handleInterventionResult(InterventionResult result,
            InterventionContext ctx, FlowableEmitter<RuntimeEvent> emitter) {
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

    /**
     * 构建干预上下文。
     */
    private InterventionContext buildInterventionContext(
            InterventionContext.ChannelType channelType,
            String agentId, String sessionId, String userId,
            String edgeType, String workflowName) {
        return new InterventionContext(
                channelType, agentId, sessionId, userId,
                edgeType, workflowName, Map.of());
    }

    // ========== 工具方法 ==========

    private String buildNodeInput(AgentNodeDef def, List<String> parentOutputs, String initialMessage) {
        StringBuilder sb = new StringBuilder();
        if (initialMessage != null && !initialMessage.isEmpty()) {
            sb.append("任务: ").append(initialMessage).append("\n\n");
        }
        for (int i = 0; i < parentOutputs.size(); i++) {
            sb.append("上游输出").append(i + 1).append(": ").append(parentOutputs.get(i)).append("\n");
        }
        return sb.toString();
    }

    private void executeSingle(
            AgentNodeDef def,
            String userId, String sessionId, String input,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter,
            String agentName) {

        AgentConfig agentConfig = AgentConfig.fromNodeDef(def);
        Agent agent = agentFactory.create(agentConfig);
        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, input, null, null);

        // 收集输出用于 onResponse 回调
        StringBuilder collectedOutput = new StringBuilder();

        agent.execute(ctx)
                .blockingForEach(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        state.appendOutput(def.getOutputKey(), event.getText());
                        collectedOutput.append(event.getText());
                    }
                    emitter.onNext(event);
                });

        // H4-步骤7: 执行完成后触发 onResponse 拦截
        if (interventionHandler != null && collectedOutput.length() > 0) {
            InterventionContext ictx = buildInterventionContext(
                    InterventionContext.ChannelType.DIRECT,
                    agentName != null ? agentName : def.getName(),
                    sessionId, userId, def.getAgentType(), "response");
            try {
                InterventionResult responseResult = interventionHandler.onResponse(
                        collectedOutput.toString(), ictx);
                if (responseResult.isBlock()) {
                    log.warn("DIRECT 通道响应被拦截阻断: agent={}, reason={}",
                            agentName, responseResult.reason());
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
}

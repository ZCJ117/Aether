package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多Agent图执行器
 *
 * SEQUENTIAL → 串行推进，{outputKey} 传递
 * PARALLEL   → 多线程并发，事件实时转发到 emitter
 * LOOP       → 循环直到收敛或达到 maxIterations
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

    private final ExecutorService parallelPool = Executors.newCachedThreadPool();

    /**
     * P0-1 改造：不再需要 ChatModel 参数，由 AgentFactory 按 Agent 名称解析
     */
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
                                initialMessage, state, emitter);
                    }
                    emitter.onComplete();
                    return;
                }

                for (AgentEdge edge : edges) {
                    // ====== P1-1: GRAPHFLOW 路由 ======
                    if (edge.isGraphFlow()) {
                        executeGraphFlow(graph, userId, sessionId, initialMessage, emitter);
                        return; // GraphFlow 是一次性执行整个 DAG
                    }
                    // ====== 旧模式不变 ======
                    switch (edge.getType()) {
                        case SEQUENTIAL -> executeSequential(
                                graph, edge, userId, sessionId, state, emitter);
                        case PARALLEL -> executeParallel(
                                graph, edge, userId, sessionId, state, emitter);
                        case LOOP -> executeLoop(
                                graph, edge, userId, sessionId, state, emitter);
                        case SUBAGENT -> executeSubAgents(
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

            executeSingle(resolved, userId,
                    sessionId + "-" + agentName, input, state, emitter);
        }
    }

    /**
     * 并行执行 — 事件实时转发到 emitter
     *
     * 使用 CountDownLatch 等待所有并行 Agent 完成
     * 使用同步块保护 emitter.onNext() 避免并发发射问题
     */
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

            parallelPool.submit(() -> {
                try {
                    // P0-1: 通过 AgentFactory 创建 Agent 实例
                    AgentConfig agentConfig = AgentConfig.fromNodeDef(resolved);
                    Agent agent = agentFactory.create(agentConfig);
                    RuntimeContext ctx = new RuntimeContext(userId,
                            sessionId + "-" + agentName, null, null, "", null, null);

                    List<RuntimeEvent> agentEvents = new ArrayList<>();
                    agent.execute(ctx)
                            .blockingForEach(event -> {
                                // 收集到本地列表
                                agentEvents.add(event);
                                // 转发事件到主 emitter（同步保护）
                                synchronized (emitter) {
                                    if (!emitter.isCancelled()) {
                                        emitter.onNext(event);
                                    }
                                }
                                // 收集文本输出
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

        // 等待所有并行 Agent 完成（最多 10 分钟）
        try {
            boolean done = latch.await(10, TimeUnit.MINUTES);
            if (!done) {
                log.warn("并行执行超时，部分Agent未完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("并行执行被中断");
        }

        // 合并所有并行结果到主 state
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

                executeSingle(resolved, userId,
                        sessionId + "-iter" + i + "-" + agentName,
                        input, state, emitter);
            }

            String currentOutput = state.getLastOutput();
            if (previousOutput.equals(currentOutput) && !currentOutput.isEmpty()) {
                log.info("循环收敛于迭代 #{}", i + 1);
                break;
            }
            previousOutput = currentOutput;
        }
    }

    /**
     * 执行 SUBAGENT 边 — 通过 SubAgentOrchestrator 派遣子Agent。
     */
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

            List<String> toolNames = def.getToolNames() != null
                    ? def.getToolNames() : List.of();

            ResultRefiner.SubAgentResult result = subAgentOrchestrator.dispatch(
                    task, toolNames, null, def.getModelRef(), userId, sessionId);

            // 将子Agent结果作为文本事件发送给客户端
            String summaryText = "[子Agent: " + agentName + "] " + result.summary();
            emitter.onNext(RuntimeEvent.text(summaryText));

            // 存储输出到状态
            String outputKey = def.getOutputKey() != null
                    ? def.getOutputKey() : agentName;
            state.appendOutput(outputKey, result.summary());
            state.setFinalOutput(outputKey, result.summary());
            state.setLastAgentName(agentName);

            log.info("SUBAGENT 完成: agent={} status={}", agentName, result.status());
        }
    }

    /**
     * 执行 GraphFlow DAG。
     * 算法：拓扑排序 → 就绪队列 → 每个就绪节点 fork 执行 → 完成后按边条件路由到子节点。
     *
     * 灵感来源：AutoGen DiGraph + GraphFlowManager（拓扑倒计数 + 就绪队列算法）。
     */
    private void executeGraphFlow(AgentGraph graph, String userId, String sessionId,
            String initialMessage, FlowableEmitter<RuntimeEvent> emitter) {

        List<AgentEdge> edges = graph.getEdges();
        Map<String, AgentNodeDef> nodeDefs = graph.getAgentDefs();

        // ============ 第 1 步：构建邻接表 ============
        // children[parentName] = List<(childName, edge)>
        Map<String, List<Map.Entry<String, AgentEdge>>> children = new LinkedHashMap<>();
        // parentCount[nodeName] = 入边数量
        Map<String, Integer> parentCount = new LinkedHashMap<>();

        // 初始化所有节点的 parentCount 为 0
        for (String nodeName : nodeDefs.keySet()) {
            parentCount.put(nodeName, 0);
            children.put(nodeName, new ArrayList<>());
        }

        // 遍历所有边，构建拓扑关系
        for (AgentEdge edge : edges) {
            if (!edge.isGraphFlow()) continue;
            String from = edge.getFrom();
            String to = edge.getTo();
            children.get(from).add(new AbstractMap.SimpleEntry<>(to, edge));
            parentCount.merge(to, 1, Integer::sum);
        }

        // ============ 第 2 步：找到入口节点（parentCount == 0 的节点） ============
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

        // ============ 第 3 步：创建运行时状态 ============
        Map<String, GraphFlowState> flowStates = new ConcurrentHashMap<>();
        for (Map.Entry<String, AgentNodeDef> entry : nodeDefs.entrySet()) {
            flowStates.put(entry.getKey(),
                new GraphFlowState(entry.getValue(), parentCount.get(entry.getKey())));
        }

        // 节点输出存储（nodeName → output text）
        Map<String, String> nodeOutputs = new ConcurrentHashMap<>();

        // 就绪队列
        Queue<String> readyQueue = new ConcurrentLinkedQueue<>(entryNodes);

        // 全局执行状态
        ExecutionState globalState = new ExecutionState();

        // ============ 第 4 步：主调度循环 ============
        int maxIterations = 50;
        int iteration = 0;

        while (!readyQueue.isEmpty() && iteration < maxIterations) {
            iteration++;

            // 取出本轮所有就绪节点
            List<String> currentBatch = new ArrayList<>();
            String nodeName;
            while ((nodeName = readyQueue.poll()) != null) {
                currentBatch.add(nodeName);
            }

            if (currentBatch.isEmpty()) break;

            // 并发执行本轮所有就绪节点
            CountDownLatch batchLatch = new CountDownLatch(currentBatch.size());
            List<Thread> batchThreads = new ArrayList<>();

            for (String name : currentBatch) {
                GraphFlowState flowState = flowStates.get(name);
                flowState.setStatus(GraphFlowState.NodeStatus.RUNNING);
                AgentNodeDef def = flowState.getNodeDef();

                Thread t = new Thread(() -> {
                    try {
                        // 构建该节点的输入（合并所有父节点输出 + 初始消息）
                        List<String> parentOutputs = flowState.getAccumulatedOutputs();
                        String input = buildNodeInput(def, parentOutputs, initialMessage);

                        // Fork 独立的 ExecutionState
                        ExecutionState localState = globalState.forkSource();

                        // P0-1: 通过 AgentFactory 创建 Agent 实例
                        AgentConfig agentConfig = AgentConfig.fromNodeDef(def);
                        Agent agent = agentFactory.create(agentConfig);
                        RuntimeContext ctx = new RuntimeContext(userId,
                            sessionId + "-" + name, null, null, input, null, null);

                        // 执行 Agent 并收集输出
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

                        // 收集输出
                        String output = localState.getOutput(def.getOutputKey());
                        nodeOutputs.put(name, output);
                        flowState.setStatus(GraphFlowState.NodeStatus.COMPLETED);

                        // ============ 第 5 步：按边条件路由到子节点 ============
                        synchronized (flowStates) {
                            for (Map.Entry<String, AgentEdge> childEntry : children.get(name)) {
                                String childName = childEntry.getKey();
                                AgentEdge edge = childEntry.getValue();

                                // 评估条件边
                                if (!conditionEvaluator.evaluate(edge.getCondition(), output)) {
                                    log.debug("边 [{}→{}] 条件不满足，跳过: condition={}",
                                        name, childName, edge.getCondition());
                                    continue;
                                }

                                GraphFlowState childState = flowStates.get(childName);
                                if (childState == null) continue;

                                boolean allParentsDone = childState.recordParentCompletion(output);

                                // 检查激活语义
                                String activation = edge.getActivation() != null
                                    ? edge.getActivation() : "all";
                                boolean shouldActivate = "any".equalsIgnoreCase(activation)
                                    ? true                           // any: 任意父完成即激活
                                    : allParentsDone;                // all: 所有父完成才激活（默认）

                                if (shouldActivate && childState.getStatus() == GraphFlowState.NodeStatus.PENDING) {
                                    // 检查退出条件（用于循环边）
                                    if (edge.getExitCondition() != null
                                        && conditionEvaluator.evaluate(edge.getExitCondition(), output)) {
                                        log.info("循环边 [{}→{}] 满足退出条件，不重新激活: exitCondition={}",
                                            name, childName, edge.getExitCondition());
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
                }, "graphflow-" + name);
                batchThreads.add(t);
                t.start();
            }

            // 等待本轮所有节点完成
            try {
                boolean ok = batchLatch.await(10, TimeUnit.MINUTES);
                if (!ok) log.warn("GraphFlow 批次超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // ============ 收集最终输出 ============
        for (Map.Entry<String, String> entry : nodeOutputs.entrySet()) {
            globalState.setFinalOutput(entry.getKey(), entry.getValue());
        }

        emitter.onNext(RuntimeEvent.done());
        emitter.onComplete();
    }

    /** 构建 DAG 节点的输入文本 */
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
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        // P0-1: 通过 AgentFactory 创建 Agent 实例
        AgentConfig agentConfig = AgentConfig.fromNodeDef(def);
        Agent agent = agentFactory.create(agentConfig);
        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, input, null, null);

        agent.execute(ctx)
                .blockingForEach(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        state.appendOutput(def.getOutputKey(), event.getText());
                    }
                    emitter.onNext(event);
                });

        state.setLastAgentName(def.getName());
        if (def.getOutputKey() != null) {
            state.setFinalOutput(def.getOutputKey(), state.getLastOutput());
        }
    }
}

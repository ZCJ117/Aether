package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.runtime.AgentRuntime;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private AgentRuntime agentRuntime;

    private final ExecutorService parallelPool = Executors.newCachedThreadPool();

    public Flowable<RuntimeEvent> execute(
            AgentGraph graph,
            ChatModel chatModel,
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
                        executeSingle(entry, chatModel, userId, sessionId,
                                initialMessage, state, emitter);
                    }
                    emitter.onComplete();
                    return;
                }

                for (AgentEdge edge : edges) {
                    switch (edge.getType()) {
                        case SEQUENTIAL -> executeSequential(
                                graph, edge, chatModel, userId, sessionId, state, emitter);
                        case PARALLEL -> executeParallel(
                                graph, edge, chatModel, userId, sessionId, state, emitter);
                        case LOOP -> executeLoop(
                                graph, edge, chatModel, userId, sessionId, state, emitter);
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
            ChatModel chatModel, String userId, String sessionId,
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
                    .build();

            String input = state.getLastOutput().isEmpty()
                    ? "" : state.getLastOutput();

            executeSingle(resolved, chatModel, userId,
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
            ChatModel chatModel, String userId, String sessionId,
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
                    .build();

            ExecutionState localState = state.forkSource();

            parallelPool.submit(() -> {
                try {
                    List<RuntimeEvent> agentEvents = new ArrayList<>();
                    agentRuntime.execute(resolved, chatModel, userId,
                                    sessionId + "-" + agentName, "")
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
            ChatModel chatModel, String userId, String sessionId,
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
                        .outputKey(def.getOutputKey())
                        .modelRef(def.getModelRef())
                        .build();

                executeSingle(resolved, chatModel, userId,
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

    private void executeSingle(
            AgentNodeDef def, ChatModel chatModel,
            String userId, String sessionId, String input,
            ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {

        agentRuntime.execute(def, chatModel, userId, sessionId, input)
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

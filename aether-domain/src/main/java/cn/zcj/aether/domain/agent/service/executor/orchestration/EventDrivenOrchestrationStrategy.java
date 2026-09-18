package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.executor.ExecutionState;
import cn.zcj.aether.domain.agent.service.executor.MessageEnvelope;
import cn.zcj.aether.domain.agent.service.executor.SubscriptionRouter;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EVENT_DRIVEN execution using topic subscriptions and bounded mailboxes.
 *
 * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
 * 面试举证点：五类编排策略之一，采用 topic 订阅 + 有界邮箱的事件驱动执行（行22、60-96）：
 * 通过 SubscriptionRouter 在 Agent 间以 MessageEnvelope 异步传递，轮询 {@code drainMailbox}
 * （行60）解耦生产者/消费者；同包另含顺序/并行/循环/子代理四种策略，均共享 GraphExecutor 的
 * 统一 {@code Flowable<RuntimeEvent>} 出口。</p>
 */
// NOTE 事件驱动执行：Agent 间以 MessageEnvelope 解耦传递，轮询 mailbox 触发执行
@Slf4j
public final class EventDrivenOrchestrationStrategy implements GraphOrchestrationStrategy {
    private final OrchestrationServices services;

    public EventDrivenOrchestrationStrategy(OrchestrationServices services) {
        this.services = services;
    }

    @Override
    public AgentEdgeType supports() {
        return AgentEdgeType.EVENT_DRIVEN;
    }

    @Override
    public void execute(AgentGraph graph, AgentEdge edge, String userId, String sessionId,
                        ExecutionState state, FlowableEmitter<RuntimeEvent> emitter) {
        List<String> agentNames = edge.getSubAgents() != null ? edge.getSubAgents() : List.of();
        if (agentNames.isEmpty()) {
            log.warn("EVENT_DRIVEN: subAgents 列表为空，跳过");
            return;
        }

        Map<String, AgentNodeDef> agentDefs = graph.getAgentDefs();
        Map<String, List<String>> watches = buildWatchMap(graph, edge, agentNames);
        // 【事件驱动】topic 订阅路由：Agent 间以 MessageEnvelope 解耦传递
        SubscriptionRouter router = new SubscriptionRouter();

        if (edge.getCauseBy() != null || edge.getWatch() != null) {
            MessageEnvelope initialMsg = MessageEnvelope.broadcast(
                    "init", edge.getCauseBy() != null ? edge.getCauseBy() : "system", sessionId, "graph-executor");
            router.route(initialMsg, state.mailboxes(), watches);
        }

        int maxIterations = edge.getMaxIterations() != null ? edge.getMaxIterations() : 10;
        int eventTimeoutMs = edge.getEventTimeoutMs() != null ? edge.getEventTimeoutMs() : 30_000;

        for (int iter = 0; iter < maxIterations; iter++) {
            boolean anyExecuted = false;
            for (String agentName : agentNames) {
                long pollTimeout = iter == 0 ? eventTimeoutMs : 1000;
                // 【事件驱动】有界邮箱轮询：解耦生产者与消费者，事件驱动触发执行
                List<MessageEnvelope> msgs = state.drainMailbox(agentName, pollTimeout);
                if (msgs.isEmpty()) continue;
                anyExecuted = true;
                String combinedInput = msgs.stream()
                        .map(m -> "[来自 " + m.causeBy() + "] " + m.content())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                AgentNodeDef def = agentDefs.get(agentName);
                if (def == null) {
                    log.warn("EVENT_DRIVEN: Agent 未找到: {}", agentName);
                    continue;
                }
                String instruction = state.resolveTemplate(
                        def.getInstruction() != null ? def.getInstruction() : "");
                String input = instruction.isEmpty() ? combinedInput : instruction + "\n\n" + combinedInput;

                InterventionContext ictx = new InterventionContext(
                        InterventionContext.ChannelType.DIRECT, agentName, sessionId, userId,
                        AgentEdgeType.EVENT_DRIVEN.name(), edge.getWorkflowName(), Map.of());
                if (!services.applyDirectInterception(input, ictx, emitter)) continue;

                // 【流式】共享 GraphExecutor 的统一 FlowableEmitter 出口，事件汇入全链路流
                services.executeSingle(def, userId, sessionId, input, state, emitter, agentName);
                publishOutput(state, def, agentName, sessionId, router, watches);
            }
            if (!anyExecuted) {
                log.info("EVENT_DRIVEN: 无活跃 Agent，第 {} 轮退出", iter);
                break;
            }
        }
    }

    private void publishOutput(ExecutionState state, AgentNodeDef def, String agentName, String sessionId,
                               SubscriptionRouter router, Map<String, List<String>> watches) {
        String output = state.getLastOutput();
        if (output == null || output.isBlank()) return;
        MessageEnvelope outMsg = MessageEnvelope.create(output, agentName, def.getOutputKey(),
                sessionId, agentName);
        int delivered = router.route(outMsg, state.mailboxes(), watches);
        log.debug("EVENT_DRIVEN: agent={} 发布消息到 {} 个订阅者", agentName, delivered);
    }

    private Map<String, List<String>> buildWatchMap(AgentGraph graph, AgentEdge edge, List<String> agentNames) {
        Map<String, List<String>> watches = new LinkedHashMap<>();
        Map<String, AgentNodeDef> agentDefs = graph.getAgentDefs();
        for (String agentName : agentNames) {
            List<String> merged = new java.util.ArrayList<>();
            if (edge.getWatch() != null) merged.addAll(edge.getWatch());
            AgentNodeDef def = agentDefs.get(agentName);
            if (def != null && def.getSubscriptions() != null) merged.addAll(def.getSubscriptions());
            if (!merged.isEmpty()) watches.put(agentName, List.copyOf(merged));
        }
        log.info("EVENT_DRIVEN 订阅声明: agents={} subscribers={}", agentNames, watches.keySet());
        return watches;
    }
}

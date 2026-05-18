package cn.zcj.aether.domain.agent.service.runtime;

import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent运行时引擎 — 自研主循环
 */
@Slf4j
@Service
public class AgentRuntime {

    @Resource
    private ContextManager contextManager;

    @Resource
    private ToolExecutor toolExecutor;

    @Resource
    private ModelInvoker modelInvoker;

    private static final int MAX_TURNS = 100;

    /**
     * 会话对话历史存储 — sessionId → TurnMessage列表
     * 每次请求从Map中加载历史消息，完成后写回，实现多轮对话上下文保持
     */
    private final ConcurrentHashMap<String, List<TurnMessage>> sessionHistories = new ConcurrentHashMap<>();

    public Flowable<RuntimeEvent> execute(
            AgentNodeDef agentDef,
            ChatModel chatModel,
            String userId,
            String sessionId,
            String userMessage) {

        return Flowable.create(emitter -> {
            try {
                queryLoop(agentDef, chatModel, userId, sessionId, userMessage, emitter);
            } catch (Exception e) {
                log.error("AgentRuntime error", e);
                if (!emitter.isCancelled()) {
                    emitter.onNext(RuntimeEvent.error(e.getMessage()));
                    emitter.onComplete();
                }
            }
        }, BackpressureStrategy.BUFFER);
    }

    private void queryLoop(
            AgentNodeDef agentDef,
            ChatModel chatModel,
            String userId,
            String sessionId,
            String userInput,
            FlowableEmitter<RuntimeEvent> emitter) {

        // 从会话历史中加载已有消息，实现多轮对话上下文保持
        List<TurnMessage> messages = sessionHistories.computeIfAbsent(
                sessionId, k -> new ArrayList<>());
        if (userInput != null && !userInput.isEmpty()) {
            messages.add(TurnMessage.user(userInput));
        }

        AtomicBoolean aborted = new AtomicBoolean(false);
        emitter.setCancellable(() -> aborted.set(true));

        int turnCount = 0;
        int consecutiveToolErrors = 0;

        try {
        while (turnCount < MAX_TURNS && !aborted.get()) {
            turnCount++;
            log.info("Turn {} — {} messages", turnCount, messages.size());

            // === Phase 1: 上下文管理 ===
            messages = contextManager.applyToolResultBudget(messages);
            messages = contextManager.microCompact(messages);

            var compactResult = contextManager.autoCompactIfNeeded(
                    messages, agentDef.getModelRef());
            if (compactResult.isCompacted()) {
                @SuppressWarnings("unchecked")
                List<TurnMessage> compacted = (List<TurnMessage>) compactResult.getCompressedMessages();
                messages = compacted;
                emitter.onNext(RuntimeEvent.builder()
                        .type(RuntimeEvent.EventType.compactBoundary)
                        .compactSummary(compactResult.getSummary())
                        .build());
            }

            // === Phase 2: 模型调用 ===
            List<Message> springMessages = convertToSpringMessages(messages);

            ModelInvoker.ModelCallResult callResult = modelInvoker.callWithStream(
                    chatModel, springMessages, agentDef.getInstruction(), agentDef.getModelRef());

            if (callResult.hasError()) {
                emitter.onNext(RuntimeEvent.error(callResult.getError()));
                break;
            }

            for (RuntimeEvent evt : callResult.getEvents()) {
                emitter.onNext(evt);
            }

            // 存储 assistant 消息 — 保留 tool_use 元数据
            if (!callResult.getToolCalls().isEmpty()) {
                // 有工具调用: 保留完整的 tool_use 信息
                List<Map<String, Object>> tcMeta = callResult.getToolCalls().stream()
                        .map(tc -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", tc.getId());
                            m.put("name", tc.getName());
                            m.put("input", tc.getInput());
                            return m;
                        })
                        .toList();
                messages.add(TurnMessage.assistantWithToolCalls(
                        callResult.getFullText(), tcMeta));
            } else if (!callResult.getFullText().isEmpty()) {
                // 无工具调用: 纯文本回复
                messages.add(TurnMessage.assistant(callResult.getFullText()));
            }

            // === Phase 3: 退出判断 ===
            if (callResult.getToolCalls().isEmpty()) {
                emitter.onNext(RuntimeEvent.done());
                emitter.onComplete();
                return;
            }

            // === Phase 4: 工具执行 ===
            List<ToolExecutor.ToolCallRequest> requests = callResult.getToolCalls().stream()
                    .map(tc -> new ToolExecutor.ToolCallRequest(
                            tc.getId(), tc.getName(), tc.getInput()))
                    .toList();

            List<ToolResult> results = toolExecutor.executeBatch(requests, userId, sessionId);

            for (ToolResult result : results) {
                emitter.onNext(RuntimeEvent.builder()
                        .type(RuntimeEvent.EventType.toolResult)
                        .toolCallId(result.getToolCallId())
                        .toolName(result.getToolName())
                        .toolOutput(result.getContent())
                        .toolError(result.isError())
                        .build());

                messages.add(TurnMessage.toolResult(
                        result.getToolCallId(), result.getToolName(), result.getContent()));
            }

            // 连续工具错误检测：当前 turn 全部工具失败 → 计数+1，否则归零
            boolean allErrored = !results.isEmpty() && results.stream().allMatch(ToolResult::isError);
            if (allErrored) {
                consecutiveToolErrors++;
                log.warn("Turn {} 所有 {} 个工具均失败: {}",
                        turnCount, results.size(),
                        results.stream().map(ToolResult::getToolName).toList());
            } else {
                consecutiveToolErrors = 0;
            }
            if (consecutiveToolErrors >= 3) {
                log.warn("连续 {} 轮工具全部失败，中断对话", consecutiveToolErrors);
                emitter.onNext(RuntimeEvent.error("工具连续失败，已中断对话"));
                break;
            }

            emitter.onNext(RuntimeEvent.builder()
                    .type(RuntimeEvent.EventType.turnComplete)
                    .turnCount(turnCount)
                    .build());
        }

        if (turnCount >= MAX_TURNS) {
            emitter.onNext(RuntimeEvent.builder()
                    .type(RuntimeEvent.EventType.maxTurnsReached)
                    .build());
        }
        emitter.onComplete();
        } finally {
            // 保存对话历史到会话存储，下次请求可继续上下文
            sessionHistories.put(sessionId, messages);
        }
    }

    /**
     * TurnMessage → Spring AI Message 转换
     *
     * 关键修复:
     *   - tool_result → ToolResponseMessage (role: "tool", 带 tool_call_id)
     *   - assistant(含toolCalls) → 带 function_call 的 AssistantMessage
     *   - 避免将 tool_result 错误转成 UserMessage 导致 API 400
     */
    private List<Message> convertToSpringMessages(List<TurnMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (TurnMessage msg : messages) {
            switch (msg.role()) {
                case "user" -> result.add(new UserMessage(msg.content()));
                case "assistant" -> {
                    if (msg.hasToolCalls()) {
                        // 重建带 tool_use 的 AssistantMessage
                        List<AssistantMessage.ToolCall> calls = msg.toolCalls().stream()
                                .map(tc -> new AssistantMessage.ToolCall(
                                        (String) tc.get("id"),
                                        "function",
                                        (String) tc.get("name"),
                                        toJsonString(tc.get("input"))))
                                .toList();
                        result.add(new AssistantMessage(
                                msg.content(), Map.of(), calls));
                    } else {
                        result.add(new AssistantMessage(msg.content()));
                    }
                }
                case "tool_result" -> {
                    String responseText = msg.content() != null ? msg.content() : "";
                    result.add(new ToolResponseMessage(
                            List.of(new ToolResponseMessage.ToolResponse(
                                    msg.toolCallId(), msg.toolName(), responseText)),
                            Map.of()));
                }
                default -> {
                    if (msg.isToolResult()) {
                        String responseText = msg.content() != null ? msg.content() : "";
                        result.add(new ToolResponseMessage(
                                List.of(new ToolResponseMessage.ToolResponse(
                                        msg.toolCallId(), msg.toolName(), responseText)),
                                Map.of()));
                    } else {
                        result.add(new UserMessage(
                                msg.content() != null ? msg.content() : ""));
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String toJsonString(Object input) {
        if (input == null) return "{}";
        if (input instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }
}

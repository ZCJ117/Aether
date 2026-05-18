package cn.zcj.aether.domain.agent.service.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM模型调用封装
 *
 * 包含超时配置、连接重置重试、指数退避
 */
@Slf4j
@Service
public class ModelInvoker {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 15000;

    @SuppressWarnings("unchecked")
    public ModelCallResult callWithStream(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName) {

        List<RuntimeEvent> events = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        List<ToolCallDef> toolCalls = new ArrayList<>();

        List<Message> fullMessages = new ArrayList<>();
        fullMessages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
        fullMessages.addAll(messages);

        log.info("模型调用请求: model={}, systemPromptLen={}, messagesCount={}",
                modelName, systemPrompt != null ? systemPrompt.length() : 0, messages.size());
        if (systemPrompt != null && systemPrompt.length() > 0) {
            log.info("systemPrompt preview: {}",
                    systemPrompt.substring(0, Math.min(300, systemPrompt.length())));
        }
        log.info("模型调用请求详情: messageRoles={}, contentLengths={}",
                fullMessages.stream().map(m -> m.getMessageType().name()).toList(),
                fullMessages.stream().map(m -> {
                    String content = m.getText();
                    return content != null ? content.length() : 0;
                }).toList());

        Prompt prompt = new Prompt(fullMessages);

        Exception lastException = null;
        long backoff = INITIAL_BACKOFF_MS;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("模型调用重试 #{}/{} — 等待 {}ms", attempt, MAX_RETRIES, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                // 重置收集器
                events.clear();
                fullText.setLength(0);
                toolCalls.clear();
            }

            try {
                Flux<ChatResponse> flux = chatModel.stream(prompt);
                List<ChatResponse> responses = flux.collectList().block();

                if (responses != null) {
                    for (ChatResponse response : responses) {
                        List<Generation> generations = response.getResults();
                        if (generations == null) continue;

                        for (Generation gen : generations) {
                            AssistantMessage output = gen.getOutput();
                            if (output == null) continue;

                            String text = output.getText();
                            if (text != null && !text.isEmpty()) {
                                fullText.append(text);
                                events.add(RuntimeEvent.text(text));
                            }

                            List<AssistantMessage.ToolCall> tcList = output.getToolCalls();
                            if (tcList != null && !tcList.isEmpty()) {
                                for (AssistantMessage.ToolCall tc : tcList) {
                                    Map<String, Object> parsedArgs = parseArguments(tc.arguments());
                                    toolCalls.add(ToolCallDef.builder()
                                            .id(tc.id()).name(tc.name()).input(parsedArgs).build());
                                    events.add(RuntimeEvent.builder()
                                            .type(RuntimeEvent.EventType.toolCall)
                                            .toolCallId(tc.id()).toolName(tc.name())
                                            .toolInput(tc.arguments()).build());
                                    log.info("解析到工具调用: id={} name={}", tc.id(), tc.name());
                                }
                            }
                        }
                    }
                }

                // 成功 — 退出重试循环
                log.info("模型调用完成: attempt={} textLength={} toolCalls={}",
                        attempt + 1, fullText.length(), toolCalls.size());
                return ModelCallResult.builder()
                        .events(events)
                        .fullText(fullText.toString())
                        .toolCalls(toolCalls)
                        .build();

            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e)) {
                    log.error("不可重试的模型调用错误: {}", e.getMessage());
                    log.error("异常完整链路: type={}, cause={}, causeMsg={}",
                            e.getClass().getName(),
                            e.getCause() != null ? e.getCause().getClass().getName() : "null",
                            e.getCause() != null ? e.getCause().getMessage() : "null");
                    // 递归打印所有嵌套异常
                    Throwable nested = e.getCause();
                    int depth = 0;
                    while (nested != null && depth < 10) {
                        log.error("  nested[{}]: {} — {}", depth,
                                nested.getClass().getName(), nested.getMessage());
                        nested = nested.getCause();
                        depth++;
                    }
                    break;
                }
                log.warn("可重试错误 (attempt {}/{}): {} — {}",
                        attempt + 1, MAX_RETRIES + 1,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }

        log.error("模型调用最终失败 (已重试{}次): {}", MAX_RETRIES,
                lastException != null ? lastException.getMessage() : "unknown");
        return ModelCallResult.error(
                lastException != null ? lastException.getMessage() : "模型调用失败");
    }

    /**
     * 判断异常是否可重试
     * Connection reset / broken pipe / timeout → 重试
     * 503 / 502 / 429 → 重试
     * 400 → 重试（非标准 API 瞬时错误）
     * 401 / 403 / 404 → 不重试
     */
    private boolean isRetryable(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // IO/网络层错误 → 重试
        if (e instanceof IOException) return true;
        if (e instanceof java.net.SocketException) return true;
        if (e instanceof java.util.concurrent.TimeoutException) return true;

        // SocketException 子类
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.SocketException) return true;
            if (cause instanceof java.io.IOException) return true;
            cause = cause.getCause();
        }

        // 消息中包含典型网络错误关键词
        if (msg.contains("connection reset")) return true;
        if (msg.contains("broken pipe")) return true;
        if (msg.contains("timeout")) return true;
        if (msg.contains("connect timed out")) return true;
        if (msg.contains("read timed out")) return true;

        // 503 / 502 / 429 → 重试
        if (msg.contains("503") || msg.contains("502") || msg.contains("429")) return true;

        // 401 / 403 / 404 → 不重试
        // 注意：400 对非标准 API（如 api.xiaomimimo.com）可能为瞬时错误（MCP 工具定义未就绪），纳入重试
        if (msg.contains("401") || msg.contains("403") || msg.contains("404"))
            return false;

        // 400 → 重试（非标准 API 可能在初始化阶段返回瞬时 400）
        if (msg.contains("400")) return true;

        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments JSON: {}", argumentsJson, e);
            return Map.of("raw", argumentsJson);
        }
    }

    @Data
    @Builder
    public static class ModelCallResult {
        private List<RuntimeEvent> events;
        private String fullText;
        private List<ToolCallDef> toolCalls;
        private String error;

        public boolean hasError() { return error != null; }
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }

        public static ModelCallResult error(String err) {
            return ModelCallResult.builder().error(err).build();
        }
    }

    @Data
    @Builder
    public static class ToolCallDef {
        private String id;
        private String name;
        private Map<String, Object> input;
    }
}

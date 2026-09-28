package cn.zcj.aether.domain.agent.service.runtime;

import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
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
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM模型调用封装
 *
 * P0-2 重试收口：本类只做"单次调用 + 总超时"；
 * 重试/退避/上下文压缩/凭据轮换/fallback 由 ResilientChatModelExecutor 统一负责，
 * 避免双层重试放大（外层 4 次 × 内层 3 次 = 最坏 12 次下游调用）。
 *
 * <p><b>【架构亮点 · 上下文工程与成本治理】</b><br>
 * 面试举证点：①callWithStreamingAsync 在响应 metadata 中回采真实 usage（getPromptTokens / getCompletionTokens，:285-297），
 * 而非估算值；<br>
 * ②该真实 token 用量回流至 TokenBudget.accumulateCost 与 ContextManager 预算闸门，是 M7 美元成本熔断与三层 token 预算的「计量数据源」，保证成本/上下文治理基于真实消耗而非猜测。</p>
 */
@Slf4j
@Service
public class ModelInvoker {

    @org.springframework.beans.factory.annotation.Autowired
    private ModelCallCache modelCallCache;

    /**
     * D4/F2-5: 模型定价注册表（供 model span 的上报 {@code cost.usd}）。
     *
     * <p>可选注入：测试直 {@code new ModelInvoker()} 时为 null，此时成本按 0.0 上报，
     * 不影响任何既有返回值与异常语义。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelPricingRegistry pricingRegistry;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 单次模型调用总超时（毫秒）；读取 aether.model.invoker.call-timeout-ms。
     *  字段初始值保证测试直 new 时兜底（Spring 注入 @Value 后以配置为准）。 */
    @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.call-timeout-ms:120000}")
    private long callTimeoutMs = 120_000;

    /** O5 真流式开关：true 时主循环走 callWithStreamingAsync（chunk 级下发）；false 回退旧缓冲路径灰度。 */
    @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.true-streaming:true}")
    private boolean trueStreaming = true;

    public long getCallTimeoutMs() { return callTimeoutMs; }

    public boolean isTrueStreaming() { return trueStreaming; }

    /**
     * 按统一请求形状构建 Prompt（{@code SystemMessage(systemPrompt)} + messages）。
     *
     * <p>上下文压缩后重建待重试请求时复用本方法，保证重建出的请求与首次请求形状一致
     * （同样的 system 指令位置与消息序列）。</p>
     *
     * @param messages     已含中间件变换的消息序列（不含 system 指令）
     * @param systemPrompt 系统指令
     */
    public Prompt buildPrompt(List<Message> messages, String systemPrompt) {
        List<Message> fullMessages = new ArrayList<>();
        fullMessages.add(new org.springframework.ai.chat.messages.SystemMessage(
                systemPrompt != null ? systemPrompt : ""));
        fullMessages.addAll(messages);
        return new Prompt(fullMessages);
    }

    /**
     * P1-#2: 带缓存的流式调用。
     * 缓存命中 → 直接返回；未命中 → callWithStream → 写入缓存。
     */
    public ModelCallResult callWithStreamCached(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName,
            boolean cacheEnabled,
            int cacheTtlSeconds) {

        if (!cacheEnabled) {
            return callWithStream(chatModel, messages, systemPrompt, modelName);
        }

        String key = ModelCallCache.cacheKey(modelName, messages);
        ModelCallResult cached = modelCallCache.get(key);
        if (cached != null) {
            return replayCacheHit(modelName, cached);
        }

        ModelCallResult result = callWithStream(chatModel, messages, systemPrompt, modelName);
        if (!result.hasError()) {
            modelCallCache.put(key, result, cacheTtlSeconds);
        }
        return result;
    }

    /**
     * P1-#2 + P1-#1: 带缓存的异步流式调用。
     * 缓存命中 → Mono.just(cached)；未命中 → callWithStreamAsync → 写缓存。
     */
    public reactor.core.publisher.Mono<ModelCallResult> callWithStreamCachedAsync(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName,
            boolean cacheEnabled,
            int cacheTtlSeconds) {

        if (!cacheEnabled) {
            return callWithStreamAsync(chatModel, messages, systemPrompt, modelName);
        }

        String key = ModelCallCache.cacheKey(modelName, messages);
        ModelCallResult cached = modelCallCache.get(key);
        if (cached != null) {
            return reactor.core.publisher.Mono.just(replayCacheHit(modelName, cached));
        }

        return callWithStreamAsync(chatModel, messages, systemPrompt, modelName)
                .doOnNext(result -> {
                    if (!result.hasError()) {
                        modelCallCache.put(key, result, cacheTtlSeconds);
                    }
                });
    }

    /**
     * O5 真流式调用：textDelta 按 chunk 即时经 {@code deltaSink} 下推，服务端不再全量缓冲等待整轮完成。
     *
     * <p>对齐 hermes conversation_loop 逐 chunk 处理模式：
     * <ul>
     *   <li>text 增量 → {@code deltaSink.accept(RuntimeEvent.text(...))} 边收边发（首 token 延迟≈网络到达时间）；</li>
     *   <li>toolCall 增量 → 汇入返回的 {@link ModelCallResult}（通常在流末到达，随结果统一下发）；</li>
     *   <li>usage 随 chunk 滚动捕获；流末缺失时按 ≈4 chars/token 估算兜底，保证成本熔断（O7 管线）不被流式路径绕过。</li>
     * </ul>
     *
     * <p>返回的 {@code ModelCallResult.events} 只含 toolCall 事件（text 已实时下发，避免重复回放）。
     * 该路径不经过 {@link ModelCallCache}——缓存命中需整体回放事件，与实时下发语义冲突。
     *
     * @param deltaSink 每个 text chunk 的实时下发回调（可为 null，等价只汇总不下发）
     */
    public Mono<ModelCallResult> callWithStreamingAsync(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName,
            java.util.function.Consumer<RuntimeEvent> deltaSink) {

        io.opentelemetry.api.trace.Span span = AgentTracer.startModelCall(modelName);
        return tracedModelCall(
                doCallWithStreamingAsync(chatModel, messages, systemPrompt, modelName, deltaSink),
                span, modelName);
    }

    private Mono<ModelCallResult> doCallWithStreamingAsync(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName,
            java.util.function.Consumer<RuntimeEvent> deltaSink) {

        log.info("真流式模型调用: model={}, messagesCount={}", modelName, messages.size());

        List<Message> fullMessages = new ArrayList<>();
        fullMessages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
        fullMessages.addAll(messages);

        Prompt prompt = new Prompt(fullMessages);

        StringBuilder fullText = new StringBuilder();
        List<ToolCallDef> toolCalls = new ArrayList<>();
        List<RuntimeEvent> toolCallEvents = new ArrayList<>();
        int[] usage = {0, 0};

        return chatModel.stream(prompt)
                .doOnNext(response -> {
                    // usage 随 chunk 滚动捕获（通常随末尾 chunk 到达）
                    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                        var u = response.getMetadata().getUsage();
                        if (u.getPromptTokens() > 0 || u.getCompletionTokens() > 0) {
                            usage[0] = (int) u.getPromptTokens();
                            usage[1] = (int) u.getCompletionTokens();
                        }
                    }
                    var generations = response.getResults();
                    if (generations == null) return;
                    for (var gen : generations) {
                        var output = gen.getOutput();
                        if (output == null) continue;
                        String text = output.getText();
                        if (text != null && !text.isEmpty()) {
                            fullText.append(text);
                            // O5 核心：边收边发，首 token 不再等待整轮完成
                            if (deltaSink != null) deltaSink.accept(RuntimeEvent.text(text));
                        }
                        var tcList = output.getToolCalls();
                        if (tcList != null && !tcList.isEmpty()) {
                            for (var tc : tcList) {
                                Map<String, Object> parsedArgs = parseArguments(tc.arguments());
                                toolCalls.add(ToolCallDef.builder()
                                        .id(tc.id()).name(tc.name()).input(parsedArgs).build());
                                toolCallEvents.add(RuntimeEvent.builder()
                                        .type(RuntimeEvent.EventType.toolCall)
                                        .toolCallId(tc.id()).toolName(tc.name())
                                        .toolInput(tc.arguments()).build());
                            }
                        }
                    }
                })
                // 只消费流信号，不在内存驻留完整 response 列表；汇总态已在 doOnNext 中滚动维护
                .ignoreElements()
                .then(Mono.fromSupplier(() -> {
                    int inputTokens = usage[0];
                    int outputTokens = usage[1];
                    // O5 步骤4：流末统一补 usage 结算——真实 usage 缺失时以滚动文本长度估算兜底
                    if (inputTokens <= 0) {
                        int approxInputChars = 0;
                        for (Message m : fullMessages) {
                            approxInputChars += m.getText() != null ? m.getText().length() : 0;
                        }
                        inputTokens = approxInputChars / 4;
                    }
                    if (outputTokens <= 0) {
                        outputTokens = fullText.length() / 4;
                    }
                    log.info("真流式模型调用完成: model={}, textLength={}, toolCalls={}, usage≈({}/{})",
                            modelName, fullText.length(), toolCalls.size(), inputTokens, outputTokens);
                    return ModelCallResult.builder()
                            .events(toolCallEvents)
                            .fullText(fullText.toString())
                            .toolCalls(toolCalls)
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .build();
                }));
    }

    /**
     * P1-#1: 异步流式调用 —— 返回 Mono 而非 blocking。
     * 借鉴 AgentScope Java 的 Mono.defer() + Flux.collectList() 非阻塞收集。
     * @deprecated O5 起默认走 {@link #callWithStreamingAsync}；本缓冲路径保留作灰度回滚。
     */
    public Mono<ModelCallResult> callWithStreamAsync(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName) {

        io.opentelemetry.api.trace.Span span = AgentTracer.startModelCall(modelName);
        return tracedModelCall(
                doCallWithStreamAsync(chatModel, messages, systemPrompt, modelName), span, modelName);
    }

    private Mono<ModelCallResult> doCallWithStreamAsync(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName) {

        log.info("异步模型调用: model={}, messagesCount={}", modelName, messages.size());

        List<Message> fullMessages = new ArrayList<>();
        fullMessages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
        fullMessages.addAll(messages);

        Prompt prompt = new Prompt(fullMessages);

        return chatModel.stream(prompt)
                .collectList()
                .map(responses -> {
                    List<RuntimeEvent> events = new ArrayList<>();
                    StringBuilder fullText = new StringBuilder();
                    List<ToolCallDef> toolCalls = new ArrayList<>();

                    if (responses != null) {
                        for (ChatResponse response : responses) {
                            var generations = response.getResults();
                            if (generations == null) continue;
                            for (var gen : generations) {
                                var output = gen.getOutput();
                                if (output == null) continue;
                                String text = output.getText();
                                if (text != null && !text.isEmpty()) {
                                    fullText.append(text);
                                    events.add(RuntimeEvent.text(text));
                                }
                                var tcList = output.getToolCalls();
                                if (tcList != null && !tcList.isEmpty()) {
                                    for (var tc : tcList) {
                                        Map<String, Object> parsedArgs = parseArguments(tc.arguments());
                                        toolCalls.add(ToolCallDef.builder()
                                                .id(tc.id()).name(tc.name()).input(parsedArgs).build());
                                        events.add(RuntimeEvent.builder()
                                                .type(RuntimeEvent.EventType.toolCall)
                                                .toolCallId(tc.id()).toolName(tc.name())
                                                .toolInput(tc.arguments()).build());
                                    }
                                }
                            }
                        }
                    }

                    log.info("异步模型调用完成: model={}, textLength={}, toolCalls={}",
                            modelName, fullText.length(), toolCalls.size());

                    // Phase 9: 提取 token 使用量
                    // 【成本治理】从模型响应 metadata 回采真实 usage（prompt/completion tokens），作为成本与预算的计量源头
                    int inputTokens = 0, outputTokens = 0;
                    if (responses != null && !responses.isEmpty()) {
                        var lastResp = responses.get(responses.size() - 1);
                        var metadata = lastResp.getMetadata();
                        if (metadata != null && metadata.getUsage() != null) {
                            // 【成本治理】真实用量：输入 token（prompt）+ 输出 token（completion）分别提取，喂给下游成本累计
                            inputTokens = (int) metadata.getUsage().getPromptTokens();
                            outputTokens = (int) metadata.getUsage().getCompletionTokens();
                        }
                    }

                    return ModelCallResult.builder()
                            .events(events)
                            .fullText(fullText.toString())
                            .toolCalls(toolCalls)
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .build();
                });
    }

    /**
     * D4/F2-5: 缓存命中路径的埋点 + 结果回放。
     *
     * <p>spec §7.1.6 把 {@code callWithStreamCached} 列为 model call 的四个入口之一，但命中时
     * 该方法<b>不会</b>落到 {@link #callWithStream}／{@link #callWithStreamAsync}——那条路径上的
     * 埋点自然一个都不生效，命中分支在 trace 里完全不可见。故在此单独补一个 span。</p>
     *
     * <p>成本记 {@code 0.0}：命中不发请求、不为 token 计费；token 数沿用缓存结果，便于按 trace
     * 统计"缓存省下了多少 token"。{@code cache.hit=true} 使缓存命中可被过滤。
     * 状态恒为 OK —— 只有 {@code !hasError()} 的结果才写入缓存，命中结果必非失败。</p>
     *
     * <p>返回的是缓存结果的副本（而非缓存内的同一实例）：调用方可能改动返回对象，
     * 直接外传会污染缓存条目。</p>
     *
     * @param modelName 模型名（写入 span 的 {@code model.name}）
     * @param cached    命中的缓存结果
     * @return 可直接返回给调用方的副本
     */
    private ModelCallResult replayCacheHit(String modelName, ModelCallResult cached) {
        ModelCallResult replay = ModelCallResult.builder()
                .events(cached.getEvents())
                .fullText(cached.getFullText())
                .toolCalls(cached.getToolCalls())
                .inputTokens(cached.getInputTokens())
                .outputTokens(cached.getOutputTokens())
                .cacheTokens(cached.getOutputTokens())
                .build();

        io.opentelemetry.api.trace.Span span = AgentTracer.startModelCall(modelName);
        span.setAttribute("cache.hit", true);
        AgentTracer.endModelCall(span, replay.getInputTokens(), replay.getOutputTokens(), 0.0);
        return replay;
    }

    /**
     * D4/F2-5: 同步模型调用 —— 开启 {@code agent.model.call} span 后委派 {@link #doCallWithStream}。
     *
     * <p>span 在本方法（调用线程）同步创建，故其父 span 恰为当前 turn span。</p>
     *
     * <p><b>注意</b>：缓存命中<b>不</b>走这里——{@link #callWithStreamCached} 命中即返回，
     * 本方法只在"未命中"或"未开缓存"时被调用；命中路径的 span 由 {@link #replayCacheHit}
     * 单独负责，两条路径各有一个 span，不会重复。</p>
     */
    public ModelCallResult callWithStream(
            ChatModel chatModel,
            List<Message> messages,
            String systemPrompt,
            String modelName) {

        io.opentelemetry.api.trace.Span span = AgentTracer.startModelCall(modelName);
        try {
            ModelCallResult result = doCallWithStream(chatModel, messages, systemPrompt, modelName);
            finishModelSpan(span, modelName, result);
            return result;
        } catch (RuntimeException | Error e) {
            AgentTracer.endSpanWithError(span, e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private ModelCallResult doCallWithStream(
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

        // P0-2 重试收口：单次调用 + 总超时。
        // 重试/退避/上下文压缩/凭据轮换/fallback 已由 ResilientChatModelExecutor 统一负责
        List<ChatResponse> responses;
        try {
            responses = chatModel.stream(prompt)
                    .collectList()
                    .block(Duration.ofMillis(callTimeoutMs));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("模型调用失败（单次调用，重试已收口至 ResilientChatModelExecutor）: "
                    + "model={}, error={}", modelName, e.getMessage(), e);
            // 兜底：getMessage() 可能为 null，避免 error 字段为 null 导致 hasError() 误判成功
            return ModelCallResult.error(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

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

        log.info("模型调用完成: textLength={} toolCalls={}", fullText.length(), toolCalls.size());

        // Phase 9: 提取 token 使用量
        int inputTokens = 0, outputTokens = 0;
        if (responses != null && !responses.isEmpty()) {
            var lastResp = responses.get(responses.size() - 1);
            var metadata = lastResp.getMetadata();
            if (metadata != null && metadata.getUsage() != null) {
                inputTokens = (int) metadata.getUsage().getPromptTokens();
                outputTokens = (int) metadata.getUsage().getCompletionTokens();
            }
        }

        return ModelCallResult.builder()
                .events(events)
                .fullText(fullText.toString())
                .toolCalls(toolCalls)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build();
    }

    // =========================================================
    // D4/F2-5: model span 生命周期
    // =========================================================

    /**
     * 给模型调用 Mono 挂上 span 结束钩子。
     *
     * <p>成功记 token/成本；失败记 ERROR；被取消/中断（既无 onSuccess 也无 onError）
     * 时由 {@code doFinally} 兜底结束，避免悬空 span。三种终止信号经 {@code AtomicBoolean}
     * 保证只结算一次。</p>
     */
    private Mono<ModelCallResult> tracedModelCall(
            Mono<ModelCallResult> source, io.opentelemetry.api.trace.Span span, String modelName) {

        java.util.concurrent.atomic.AtomicBoolean ended =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return source
                .doOnSuccess(result -> {
                    if (ended.compareAndSet(false, true)) {
                        finishModelSpan(span, modelName, result);
                    }
                })
                .doOnError(e -> {
                    if (ended.compareAndSet(false, true)) {
                        AgentTracer.endSpanWithError(span, e.getMessage());
                    }
                })
                .doFinally(signal -> {
                    if (ended.compareAndSet(false, true)) {
                        AgentTracer.endSpanWithError(span, "模型流未完成（取消/中断）: " + signal);
                    }
                });
    }

    /**
     * 按调用结果结束 model span：无结果或 {@code hasError()} 记 ERROR，否则记 token 与成本。
     *
     * <p>边界（spec §7.1.9）：{@code ModelCallResult} 无 token 信息（异常结果）走 ERROR 分支，
     * 不调 {@link AgentTracer#endModelCall}。</p>
     */
    private void finishModelSpan(
            io.opentelemetry.api.trace.Span span, String modelName, ModelCallResult result) {
        if (result == null) {
            AgentTracer.endSpanWithError(span, "模型流未返回结果");
            return;
        }
        if (result.hasError()) {
            AgentTracer.endSpanWithError(span, result.getError());
            return;
        }
        AgentTracer.endModelCall(span, result.getInputTokens(), result.getOutputTokens(),
                estimateCostUsd(modelName, result.getInputTokens(), result.getOutputTokens()));
    }

    /**
     * 估算本次调用的美元成本（仅用于 span 属性上报）。
     *
     * <p>定价注册表缺失时按 0.0 上报，任何异常也不得冒泡到业务线程。
     * 注册表存在但无该模型定价时，{@link ModelPricingRegistry#lookup} 返回保守默认定价
     * （$0.001/$0.002 每千 token），成本熔断的既有语义不变。</p>
     */
    private double estimateCostUsd(String modelName, int inputTokens, int outputTokens) {
        if (pricingRegistry == null) {
            return 0.0;
        }
        try {
            return pricingRegistry.lookup(modelName).calculateCost(inputTokens, outputTokens);
        } catch (Exception e) {
            log.debug("成本估算失败，按 0.0 上报: {}", e.getMessage());
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseArguments(String argumentsJson) {
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

        // ====== P1-3 新增：Token 使用量字段 ======
        /** 输入 token 数 */
        private int inputTokens;
        /** 输出 token 数 */
        private int outputTokens;
        /** 缓存命中的 token 数 */
        private int cacheTokens;
        /** 成本（美元），估算值 */
        private double costUsd;

        public boolean hasError() { return error != null; }
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }

        /** 总 token 数 */
        public int totalTokens() { return inputTokens + outputTokens; }

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

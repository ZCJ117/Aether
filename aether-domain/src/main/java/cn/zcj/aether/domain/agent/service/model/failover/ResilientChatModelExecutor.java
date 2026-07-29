package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 容错 ChatModel 装饰器 — 实现 {@link ChatModel} 接口，透明包装底层模型调用。
 *
 * <h3>提供的容错能力</h3>
 * <ol>
 *   <li>结构化错误分类 → 恢复动作（对齐 hermes error_classifier.py 分类管线）</li>
 *   <li>去相关抖动退避重试（移植 hermes retry_utils.py jittered_backoff）</li>
 *   <li>上下文溢出 → 压缩重试（shouldCompress → CompressCallback）</li>
 *   <li>主模型耗尽 → fallback 链切换（对齐 hermes conversation_loop.py L4040-4085）</li>
 *   <li>显式禁用 SDK 内建重试（对齐 hermes run_agent.py L4641 语义）</li>
 * </ol>
 *
 * <p>因为实现 {@link ChatModel}，可被 {@link cn.zcj.aether.domain.agent.service.runtime.ModelInvoker}
 * 和 {@link cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent} 透明使用，
 * 无需修改现有调用方代码。</p>
 */
@Slf4j
public class ResilientChatModelExecutor implements ChatModel {

    // ── 退避配置（对齐 hermes retry_utils.py: base=2s, max=30s, jitter_ratio=0.5）──
    private static final double BASE_DELAY_SEC = 2.0;
    private static final double MAX_DELAY_SEC = 30.0;
    private static final double JITTER_RATIO = 0.5;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_COMPRESSION_ATTEMPTS = 2;
    static final long FALLBACK_COOLDOWN_SEC = 60;
    private static final long EXHAUSTED_COOLDOWN_SEC = 30;

    // ── 线程安全计数器（对齐 hermes retry_utils.py _jitter_counter）──
    private static final AtomicInteger jitterCounter = new AtomicInteger(0);

    // ── 依赖 ──
    private final ModelProviderRegistry providerRegistry;
    private final ModelErrorClassifier errorClassifier;

    /** 当前活跃的 ChatModel */
    @Getter
    private ChatModel currentChatModel;

    /** 当前模型配置 */
    @Getter
    private ModelConfig currentModelConfig;

    /** 当前 Provider */
    @Getter
    private ModelProvider currentProvider;

    // ── Fallback 链状态 ──
    /** 有序的 fallback 路由列表 */
    private final List<ModelRoute> fallbackChain;
    /** Fallback 链当前位置 */
    private int fallbackIndex = 0;
    /** Fallback 是否已激活 */
    private boolean fallbackActivated = false;

    // ── 上下文压缩回调 ──
    /** 上下文压缩回调（由 ReActAgent 注入，对接 ContextManager） */
    private CompressCallback compressCallback;

    // ── AgentState 引用（供 call/stream 时使用）──
    /** Agent 状态（用于 fallback 冷却时间等元数据存储） */
    private AgentState agentState;

    public ResilientChatModelExecutor(
            ChatModel chatModel,
            ModelConfig modelConfig,
            ModelProvider provider,
            ModelProviderRegistry providerRegistry,
            ModelErrorClassifier errorClassifier,
            List<ModelRoute> fallbackChain) {
        this.currentChatModel = chatModel;
        this.currentModelConfig = modelConfig;
        this.currentProvider = provider;
        this.providerRegistry = providerRegistry;
        this.errorClassifier = errorClassifier;
        this.fallbackChain = new ArrayList<>(fallbackChain != null ? fallbackChain : List.of());
    }

    /**
     * 注册上下文压缩回调。
     */
    public void setCompressCallback(CompressCallback callback) {
        this.compressCallback = callback;
    }

    /**
     * 设置 Agent 状态引用（供 call/stream 时读取 fallback 冷却信息）。
     */
    public void setAgentState(AgentState state) {
        this.agentState = state;
    }

    // ── ChatModel 接口实现 ──

    /**
     * 带容错的同步模型调用（实现 ChatModel.call）。
     *
     * <p>当前 ModelInvoker 的流式调用路径最终通过 {@code flux.collectList().block()}
     * 转为同步结果，因此本方法实际承载了所有模型调用。</p>
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        int maxAttempts = currentModelConfig.getMaxAttempts() != null
                ? currentModelConfig.getMaxAttempts() : DEFAULT_MAX_ATTEMPTS;
        int retryCount = 0;
        int compressionAttempts = 0;

        while (true) {
            try {
                ChatResponse response = currentChatModel.call(prompt);
                // 成功 — 恢复 fallback 状态标记
                if (fallbackActivated && !isInFallbackCooldown()) {
                    log.info("Fallback 模型调用成功，下一轮将尝试恢复主模型");
                    fallbackActivated = false;
                    setStateAttr("resilient:fallbackActivated", Boolean.FALSE);
                }
                return response;

            } catch (Exception e) {
                ClassifiedError classified = classifyError(e);

                log.warn("模型调用失败 [attempt={}/{}] provider={} model={} reason={} status={}",
                        retryCount + 1, maxAttempts,
                        currentProvider.providerName(), currentModelConfig.getModelId(),
                        classified.reason(), classified.statusCode());

                // ── 上下文溢出 → 压缩 ──
                if (classified.shouldCompress() && compressCallback != null
                        && compressionAttempts < MAX_COMPRESSION_ATTEMPTS) {
                    compressionAttempts++;
                    log.info("触发上下文压缩 (attempt {}/{}) — reason={}",
                            compressionAttempts, MAX_COMPRESSION_ATTEMPTS, classified.reason());
                    try {
                        if (compressCallback.compress(agentState, classified.reason())) {
                            log.info("上下文压缩完成，重试模型调用");
                            continue;
                        }
                    } catch (Exception ce) {
                        log.warn("上下文压缩失败: {}", ce.getMessage());
                    }
                }

                // ── 不可恢复 → 立即上抛 ──
                if (classified.reason() == FailoverReason.AUTH_PERMANENT
                        || classified.reason() == FailoverReason.CONTENT_POLICY_BLOCKED) {
                    log.error("不可恢复的错误，终止请求: reason={}", classified.reason());
                    throw new ResilientCallException(
                            "不可恢复的模型调用错误: " + classified.reason(), classified, e);
                }

                // ── 可重试 → 抖动退避 ──
                if (classified.retryable() && retryCount < maxAttempts) {
                    retryCount++;
                    double waitSec = jitteredBackoff(retryCount);
                    log.info("退避重试 — 等待 {:.1f}s (attempt {}/{})",
                            waitSec, retryCount, maxAttempts);
                    sleepMs((long) (waitSec * 1000));
                    continue;
                }

                // ── 重试耗尽 → fallback ──
                boolean shouldTryFallback = classified.shouldFallback()
                        || retryCount >= maxAttempts
                        || !classified.retryable();

                if (shouldTryFallback && tryActivateFallback(classified.reason())) {
                    retryCount = 0;
                    compressionAttempts = 0;
                    log.info("Fallback 模型已激活: provider={} model={}",
                            currentProvider.providerName(), currentModelConfig.getModelId());
                    continue;
                }

                // ── 所有策略耗尽 → 上抛 ──
                log.error("所有容错策略耗尽: provider={} model={} reason={} retries={}",
                        currentProvider.providerName(), currentModelConfig.getModelId(),
                        classified.reason(), retryCount);
                throw new ResilientCallException(
                        "模型调用最终失败: " + classified.reason(), classified, e);
            }
        }
    }

    /**
     * 带容错的流式模型调用。
     *
     * <p>当前 ModelInvoker 的流式调用路径通过 {@code flux.collectList().block()} 转为同步结果，
     * 因此本方法委托给 {@link #call(Prompt)}，其内部有完整的 failover 循环。</p>
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            try {
                ChatResponse response = call(prompt);
                return Flux.just(response);
            } catch (Exception e) {
                return Flux.error(e);
            }
        });
    }

    // ── 分类 ──

    private ClassifiedError classifyError(Exception e) {
        try {
            ClassifiedError fromProvider = currentProvider.classifyError(
                    e, currentModelConfig.getModelId());
            if (fromProvider != null) return fromProvider;
        } catch (Exception ignored) {
            // Provider 分类失败，回退默认
        }
        return errorClassifier.classify(e,
                currentProvider.providerName(), currentModelConfig.getModelId());
    }

    // ── 退避计算 ──

    static double jitteredBackoff(int attempt) {
        int tick = jitterCounter.incrementAndGet();
        int exponent = Math.max(0, attempt - 1);

        double delay;
        if (exponent >= 63 || BASE_DELAY_SEC <= 0) {
            delay = MAX_DELAY_SEC;
        } else {
            delay = Math.min(BASE_DELAY_SEC * Math.pow(2, exponent), MAX_DELAY_SEC);
        }

        // 简化实现：在 [0, jitterRatio * delay] 范围内均匀随机
        double jitter = ThreadLocalRandom.current().nextDouble(0, JITTER_RATIO * delay);
        return delay + jitter;
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Fallback 链管理 ──

    private boolean tryActivateFallback(FailoverReason reason) {
        if (reason == FailoverReason.RATE_LIMIT
                || reason == FailoverReason.UPSTREAM_RATE_LIMIT
                || reason == FailoverReason.BILLING) {
            if (!fallbackActivated) {
                setStateAttr("resilient:fallbackCooldownUntil",
                        System.currentTimeMillis() + FALLBACK_COOLDOWN_SEC * 1000);
            }
        }

        if (fallbackIndex >= fallbackChain.size()) {
            log.warn("Fallback 链已耗尽 (index={}, chainSize={})", fallbackIndex, fallbackChain.size());
            if (!fallbackChain.isEmpty() && !isRateLimitReason(reason)) {
                long existing = getStateLong("resilient:fallbackCooldownUntil");
                setStateAttr("resilient:fallbackCooldownUntil",
                        Math.max(existing, System.currentTimeMillis() + EXHAUSTED_COOLDOWN_SEC * 1000));
            }
            return false;
        }

        ModelRoute fb = fallbackChain.get(fallbackIndex);
        fallbackIndex++;

        if (fb.isInCooldown()) {
            log.debug("Fallback 路由冷却中，跳过: model={}, remaining={}s",
                    fb.getModelId(), fb.cooldownRemainingSeconds());
            return tryActivateFallback(reason);
        }

        ModelProvider fbProvider;
        try {
            fbProvider = providerRegistry.resolve(fb.getModelId());
        } catch (Exception e) {
            log.warn("Fallback Provider 解析失败: model={}, {}", fb.getModelId(), e.getMessage());
            return tryActivateFallback(reason);
        }

        ModelConfig fbConfig = ModelConfig.builder()
                .modelId(fb.getModelId())
                .baseUrl(fb.getBaseUrl() != null ? fb.getBaseUrl() : currentModelConfig.getBaseUrl())
                .apiKey(fb.getApiKey() != null ? fb.getApiKey() : currentModelConfig.getApiKey())
                .completionsPath(fb.getCompletionsPath() != null
                        ? fb.getCompletionsPath() : currentModelConfig.getCompletionsPath())
                .build();

        try {
            this.currentChatModel = fbProvider.createChatModel(fbConfig);
            this.currentProvider = fbProvider;
            this.currentModelConfig = fbConfig;
            this.fallbackActivated = true;
            setStateAttr("resilient:fallbackActivated", Boolean.TRUE);
            log.info("Fallback 切换成功: {}:{} (index={}/{})",
                    fb.getProvider(), fb.getModelId(), fallbackIndex, fallbackChain.size());
            return true;
        } catch (Exception e) {
            log.warn("Fallback ChatModel 创建失败: model={}, {}", fb.getModelId(), e.getMessage());
            return tryActivateFallback(reason);
        }
    }

    private boolean isInFallbackCooldown() {
        return System.currentTimeMillis() < getStateLong("resilient:fallbackCooldownUntil");
    }

    private static boolean isRateLimitReason(FailoverReason reason) {
        return reason == FailoverReason.RATE_LIMIT
                || reason == FailoverReason.UPSTREAM_RATE_LIMIT
                || reason == FailoverReason.BILLING;
    }

    // ── AgentState 工具方法（空安全）──

    private void setStateAttr(String key, Object value) {
        if (agentState != null) agentState.setAttribute(key, value);
    }

    private long getStateLong(String key) {
        if (agentState == null) return 0L;
        Object v = agentState.getAttribute(key);
        return v instanceof Long l ? l : 0L;
    }

    // ── 回调接口 ──

    @FunctionalInterface
    public interface CompressCallback {
        boolean compress(AgentState state, FailoverReason reason);
    }

    // ── 异常类 ──

    public static class ResilientCallException extends RuntimeException {
        @Getter
        private final ClassifiedError classifiedError;

        public ResilientCallException(String message, ClassifiedError classified, Throwable cause) {
            super(message, cause);
            this.classifiedError = classified;
        }
    }
}

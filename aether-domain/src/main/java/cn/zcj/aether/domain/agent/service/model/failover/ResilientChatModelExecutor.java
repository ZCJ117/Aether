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
import java.util.Optional;

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
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final long FALLBACK_COOLDOWN_SEC = 60;
    private static final long EXHAUSTED_COOLDOWN_SEC = 30;

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

    // ── 凭据轮换池（可选，未注入则不轮换）──
    /** 凭据轮换池（对齐 hermes recover_with_credential_pool） */
    private CredentialPool credentialPool;

    /**
     * 注入凭据轮换池。未注入时 AUTH_TRANSIENT/BILLING 走 fallback 而非轮换。
     */
    public void setCredentialPool(CredentialPool pool) {
        this.credentialPool = pool;
    }

    // ── 退避等待器（默认真实 sleep；测试可替换为 no-op 以跳过退避等待）──
    /** 退避等待器：输入退避秒数并阻塞等待。默认真实 sleep，测试可替换。 */
    private java.util.function.Consumer<Double> backoffWaiter =
            sec -> sleepMs((long) (sec * 1000));

    /**
     * 替换退避等待器（仅测试用，避免真实 sleep 拖慢用例）。
     */
    void setBackoffWaiter(java.util.function.Consumer<Double> waiter) {
        this.backoffWaiter = waiter;
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
        // 每次调用 = 一个 turn，持有一个恢复分支账本
        TurnRetryState turnRetry = new TurnRetryState(maxAttempts, fallbackChain.size());

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

                log.warn("模型调用失败 provider={} model={} reason={} status={}",
                        currentProvider.providerName(), currentModelConfig.getModelId(),
                        classified.reason(), classified.statusCode());

                RecoveryDirective d = turnRetry.nextDirective(classified);
                log.info("恢复指令: branch={} reason={}", d.branch(), d.reason());

                switch (d.branch()) {
                    case JITTERED_BACKOFF, ADAPTIVE_RATE_LIMIT_BACKOFF -> {
                        turnRetry.markAttempted(d.branch());
                        backoffWaiter.accept(d.backoffSec());
                    }
                    case CONTEXT_COMPRESSION -> {
                        turnRetry.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);
                        tryCompress(classified);
                    }
                    case CREDENTIAL_ROTATION -> {
                        turnRetry.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
                        tryRotateCredential(classified);
                    }
                    case PROVIDER_FALLBACK -> {
                        turnRetry.markAttempted(RecoveryBranch.PROVIDER_FALLBACK);
                        if (tryActivateFallback(classified.reason())) {
                            turnRetry.reset(); // fallback 切换成功，重置本轮账本
                        }
                    }
                    case TIMEOUT_RECONNECT -> {
                        turnRetry.markAttempted(RecoveryBranch.TIMEOUT_RECONNECT);
                        backoffWaiter.accept(1.0); // 固定 1s 重建连接等待
                    }
                    case TERMINATE -> throw new ResilientCallException(
                            "所有恢复策略耗尽: " + classified.reason(), classified, e);
                }
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

    // ── 恢复动作执行 ──

    /**
     * 执行上下文压缩。失败仅记录，不阻断（下一轮指令会退化到 fallback/终止）。
     */
    private void tryCompress(ClassifiedError classified) {
        if (compressCallback == null) {
            log.debug("无压缩回调，跳过压缩");
            return;
        }
        try {
            if (compressCallback.compress(agentState, classified.reason())) {
                log.info("上下文压缩完成，重试模型调用");
            }
        } catch (Exception ce) {
            log.warn("上下文压缩失败: {}", ce.getMessage());
        }
    }

    /**
     * 执行凭据轮换。未注入池或池中无备选时，跳过（下一轮指令退化为 fallback）。
     */
    private void tryRotateCredential(ClassifiedError classified) {
        if (credentialPool == null) {
            log.debug("未注入凭据池，跳过轮换");
            return;
        }
        try {
            Optional<ModelConfig> rotated =
                    credentialPool.rotate(currentModelConfig, currentProvider.providerName());
            if (rotated.isEmpty()) {
                log.warn("凭据池无可用备选，跳过轮换");
                return;
            }
            this.currentChatModel = currentProvider.createChatModel(rotated.get());
            this.currentModelConfig = rotated.get();
            setStateAttr("resilient:credentialRotated", Boolean.TRUE);
            log.info("凭据已轮换: provider={} model={}",
                    currentProvider.providerName(), currentModelConfig.getModelId());
        } catch (Exception e) {
            log.warn("凭据轮换失败: {}", e.getMessage());
        }
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

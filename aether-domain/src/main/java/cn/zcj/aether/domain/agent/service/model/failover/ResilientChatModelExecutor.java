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
import java.util.concurrent.atomic.AtomicBoolean;

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

    // ── 压缩后的请求重建 ──
    /**
     * 压缩后的待重试请求重建器（由宿主注入）。
     *
     * <p>压缩改的是 {@code AgentState} 里的消息，而本类重试时复用的是入参 {@link Prompt}；
     * 两者是不同对象，故压缩后必须由宿主持有 system 指令与中间件变换的一方重建请求，
     * 否则重试发出去的仍是压缩前那份超长请求，必然再次溢出。</p>
     */
    @FunctionalInterface
    public interface PromptRebuilder {
        Prompt rebuild();
    }

    /** 未注入时重试复用入参 Prompt（改造前行为，靠下一轮生效）。 */
    private PromptRebuilder promptRebuilder;

    /**
     * 注入压缩后的请求重建器。
     */
    public void setPromptRebuilder(PromptRebuilder rebuilder) {
        this.promptRebuilder = rebuilder;
    }

    // ── 容错指标（可选，未注入则不记录；P0(1.5) 混沌压测观测用）──
    private FailoverMetrics failoverMetrics;

    public void setFailoverMetrics(FailoverMetrics failoverMetrics) {
        this.failoverMetrics = failoverMetrics;
    }

    private void recordBranch(RecoveryBranch branch) {
        if (failoverMetrics != null) {
            failoverMetrics.recordBranch(branch);
        }
    }

    private void recordFallbackSwitch() {
        if (failoverMetrics != null) {
            failoverMetrics.recordFallbackSwitch();
        }
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

    //NOTE 恢复决策错误 分类 + 退避/压缩/凭据轮换/fallback 链切换
    // 具体场景，假设 Agent 正在对话，底层 DeepSeek API 返回了 429 限流
    // 第 0 步：触发点 —— ResilientChatModelExecutor.call()
    @Override
    public ChatResponse call(Prompt prompt) {
        // 每次调用 = 一个 turn，持有一个恢复分支账本
        TurnRetryState turnRetry = new TurnRetryState(resolveMaxAttempts(), fallbackChain.size());

        //这里循环不是失败一次就拋，而是每轮失败都重新分类、重新决策、再试，直到成功或决策器说"放弃"（TERMINATE分支抛异常）
        // TurnRetryState 是每次 call() 新建的，所以一本"病历"只管这一次对话轮次
//
//       TurnRetryState 在其中的真实角色
//        它不是重试的执行者，是重试的裁判。它只负责两件事：
//        1. 记账：markAttempted() —— "限流退避这个疗法已经用过 1 次了"
//        2. 裁决：nextDirective() —— "还能再试吗？"（次数没超 → 给重试指令；超了 → 给 fallback 或 TERMINATE 指令）
        // 每轮重试实际发出的请求：压缩真正改变了上下文时会被重建，否则沿用入参
        Prompt activePrompt = prompt;
        while (true) {
            try {
                //要是再次执行 currentChatModel.call(prompt) → 成功，就直接返回
                ChatResponse response = currentChatModel.call(activePrompt);
                // 成功 — 恢复 fallback 状态标记
                if (fallbackActivated && !isInFallbackCooldown()) {
                    log.info("Fallback 模型调用成功，下一轮将尝试恢复主模型");
                    fallbackActivated = false;
                    setStateAttr("resilient:fallbackActivated", Boolean.FALSE);
                }
                return response;

            } catch (Exception e) {
                //markAttempted() 记录尝试过的分支，避免重复尝试
                //这里是调用classifyError()，也就是分类的入口
                ClassifiedError classified = classifyError(e);

                log.warn("模型调用失败 provider={} model={} reason={} status={}",
                        currentProvider.providerName(), currentModelConfig.getModelId(),
                        classified.reason(), classified.statusCode());

                RecoveryDirective d = turnRetry.nextDirective(classified);
                log.info("恢复指令: branch={} reason={}", d.branch(), d.reason());
                recordBranch(d.branch());

                //NOTE  第 3 步：执行 —— call() 的 switch，30 秒后又 429，60 秒后又 429,attempt=4>3 走exhaustedBranch()，走 TERMINATE 分支抛异常
                switch (d.branch()) {
                    // 去相关抖动退避重试
                    case JITTERED_BACKOFF, ADAPTIVE_RATE_LIMIT_BACKOFF -> {
                        turnRetry.markAttempted(d.branch());
                        backoffWaiter.accept(d.backoffSec());
                    }
                    case CONTEXT_COMPRESSION -> {
                        // 上下文溢出，回调压缩后重试
                        turnRetry.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);
                        // 只有压缩真的改变了上下文才重建请求：未改变时沿用原 Prompt，
                        // 避免用同一份超长上下文再发一次完全相同的请求
                        if (tryCompress(classified)) {
                            activePrompt = rebuildPrompt(activePrompt);
                        }
                    }
                    case CREDENTIAL_ROTATION -> {
                        // 凭据轮换后重试
                        turnRetry.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
                        tryRotateCredential(classified);
                    }
                    //NOTE 第 4 步：换模型 —— tryActivateFallback(),执行器收到 PROVIDER_FALLBACK 指令
                    case PROVIDER_FALLBACK -> {
                        // fallback 链切换后重试
                        turnRetry.markAttempted(RecoveryBranch.PROVIDER_FALLBACK);
                        if (tryActivateFallback(classified.reason())) {
                            turnRetry.resetPerModel(); // 新模型重新计退避，但保留 fallback 链位置
                        } else if (fallbackIndex >= fallbackChain.size()) {
                            // fallback 链已实际耗尽：把账本同步为已用尽，避免后续空转重试
                            turnRetry.markExhausted(RecoveryBranch.PROVIDER_FALLBACK, fallbackChain.size());
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
     * 带容错的流式模型调用 —— 逐帧透传，TTFT 符合流式语义。
     *
     * <p>每次尝试直接订阅 {@code currentChatModel.stream(prompt)}，chunk 到达即向下游转发；
     * failover 仅在首帧之前生效：失败时按 {@link TurnRetryState} 执行与 {@link #call(Prompt)}
     * 相同的恢复分支（退避/压缩/凭据轮换/fallback），然后重建流重试。</p>
     *
     * <p>首帧之后失败不重试——下游已收到部分帧，重放会导致内容重复，只能原样传播错误。</p>
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> streamWithRecovery(prompt, new TurnRetryState(
                resolveMaxAttempts(), fallbackChain.size())));
    }

    /** 单次流式尝试：逐帧透传 + 首帧前的 failover 重建。 */
    private Flux<ChatResponse> streamWithRecovery(Prompt prompt, TurnRetryState turnRetry) {
        return Flux.defer(() -> {
            AtomicBoolean frameReceived = new AtomicBoolean(false);
            return currentChatModel.stream(prompt)
                    .doOnNext(r -> frameReceived.set(true))
                    .doOnComplete(() -> {
                        // 成功 — 恢复 fallback 状态标记（与 call() 语义对齐）
                        if (fallbackActivated && !isInFallbackCooldown()) {
                            log.info("Fallback 模型流式调用成功，下一轮将尝试恢复主模型");
                            fallbackActivated = false;
                            setStateAttr("resilient:fallbackActivated", Boolean.FALSE);
                        }
                    })
                    .onErrorResume(e ->
                            handleStreamError(prompt, turnRetry, frameReceived.get(), e));
        });
    }

    /**
     * 流式失败处理：首帧前按恢复分支重建流；首帧后原样传播。
     */
    private Flux<ChatResponse> handleStreamError(Prompt prompt, TurnRetryState turnRetry,
            boolean frameReceived, Throwable t) {
        if (frameReceived) {
            // 首帧后失败：下游已收到部分帧，重放会重复下发，不重试
            return Flux.error(t);
        }
        Exception e = t instanceof Exception x ? x : new RuntimeException(t);
        ClassifiedError classified = classifyError(e);

        log.warn("流式模型调用失败 provider={} model={} reason={} status={}",
                currentProvider.providerName(), currentModelConfig.getModelId(),
                classified.reason(), classified.statusCode());

        RecoveryDirective d = turnRetry.nextDirective(classified);
        log.info("流式恢复指令: branch={} reason={}", d.branch(), d.reason());
        recordBranch(d.branch());

        return switch (d.branch()) {
            case JITTERED_BACKOFF, ADAPTIVE_RATE_LIMIT_BACKOFF -> {
                turnRetry.markAttempted(d.branch());
                backoffWaiter.accept(d.backoffSec());
                yield streamWithRecovery(prompt, turnRetry);
            }
            case CONTEXT_COMPRESSION -> {
                turnRetry.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);
                // 同同步路径：压缩真的改变了上下文才重建请求，用压缩后的上下文重建流
                yield tryCompress(classified)
                        ? streamWithRecovery(rebuildPrompt(prompt), turnRetry)
                        : streamWithRecovery(prompt, turnRetry);
            }
            case CREDENTIAL_ROTATION -> {
                turnRetry.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
                tryRotateCredential(classified);
                yield streamWithRecovery(prompt, turnRetry);
            }
            case PROVIDER_FALLBACK -> {
                turnRetry.markAttempted(RecoveryBranch.PROVIDER_FALLBACK);
                if (tryActivateFallback(classified.reason())) {
                    turnRetry.resetPerModel(); // 新模型重新计退避，但保留 fallback 链位置
                } else if (fallbackIndex >= fallbackChain.size()) {
                    turnRetry.markExhausted(RecoveryBranch.PROVIDER_FALLBACK, fallbackChain.size());
                }
                yield streamWithRecovery(prompt, turnRetry);
            }
            case TIMEOUT_RECONNECT -> {
                turnRetry.markAttempted(RecoveryBranch.TIMEOUT_RECONNECT);
                backoffWaiter.accept(1.0); // 固定 1s 重建连接等待
                yield streamWithRecovery(prompt, turnRetry);
            }
            case TERMINATE -> Flux.error(new ResilientCallException(
                    "所有恢复策略耗尽: " + classified.reason(), classified, e));
        };
    }

    // ── 分类 ──

    /** 单 turn 通用退避上限（ModelConfig.maxAttempts，缺省取默认值）——call/stream 共用。 */
    private int resolveMaxAttempts() {
        return currentModelConfig.getMaxAttempts() != null
                ? currentModelConfig.getMaxAttempts() : DEFAULT_MAX_ATTEMPTS;
    }

    //NOTE 第 1 步：分类 —— "这是什么错误？"
    // 这个是异常的分类入口
    private ClassifiedError classifyError(Exception e) {
        try {
            // 先问当前 Provider："你自己认识这个错误吗？"（OpenAI 的错误格式和 Anthropic 不同）
            ClassifiedError fromProvider = currentProvider.classifyError(
                    e, currentModelConfig.getModelId());
            if (fromProvider != null) return fromProvider;
        } catch (Exception ignored) {
            // Provider 分类失败，回退默认
        }
        // Provider 不认识 → 用通用分类器，就去调用 errorClassifier.classify()，这个是 hermes-agent error_classifier.py 的移植
        return errorClassifier.classify(e, currentProvider.providerName(), currentModelConfig.getModelId());
    }

    // ── 恢复动作执行 ──

    /**
     * 执行上下文压缩。失败仅记录，不阻断（下一轮指令会退化到 fallback/终止）。
     *
     * <p>四个出口分别打点，使 CONTEXT_COMPRESSION 分支不再无声 ——
     * 调用方（{@code call()} / {@code handleStreamError()}）仍忽略返回值，
     * 台账记账（{@code markAttempted}）保持在动作之前。</p>
     *
     * @return 是否真的改变了上下文（false = 回调缺失、无变化或失败）
     */
    private boolean tryCompress(ClassifiedError classified) {
        if (compressCallback == null) {
            log.warn("无压缩回调，跳过压缩（CONTEXT_COMPRESSION 分支空转，请检查装配）");
            recordCompressResult(FailoverMetrics.CompressOutcome.NOOP);
            return false;
        }
        try {
            if (compressCallback.compress(agentState, classified.reason())) {
                log.info("上下文压缩完成，重试模型调用");
                recordCompressResult(FailoverMetrics.CompressOutcome.SUCCESS);
                return true;
            }
            log.warn("上下文压缩未产生变化");
            recordCompressResult(FailoverMetrics.CompressOutcome.INEFFECTIVE);
            return false;
        } catch (Exception ce) {
            log.warn("上下文压缩失败: {}", ce.getMessage());
            recordCompressResult(FailoverMetrics.CompressOutcome.ERROR);
            return false;
        }
    }

    private void recordCompressResult(FailoverMetrics.CompressOutcome outcome) {
        if (failoverMetrics != null) {
            failoverMetrics.recordCompressResult(outcome);
        }
    }

    /**
     * 用宿主注入的重建器生成压缩后的待重试请求。
     *
     * <p>未注入重建器、重建返回 null 或重建抛异常时一律回退原请求 ——
     * 本方法在模型调用热路径上，**不得**抛异常，最坏情况退化为改造前行为
     * （重试复用原请求，压缩收益留给下一轮）。</p>
     */
    private Prompt rebuildPrompt(Prompt fallback) {
        if (promptRebuilder == null) {
            return fallback;
        }
        try {
            Prompt rebuilt = promptRebuilder.rebuild();
            return rebuilt != null ? rebuilt : fallback;
        } catch (Exception e) {
            log.warn("压缩后重建请求失败，重试将复用原请求: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * 执行凭据轮换。未注入池或池中无备选时，跳过（下一轮指令退化为 fallback）。
     *
     * <p>注意：{@code createChatModel} 重建 {@code currentChatModel} 时不会释放上一个实例
     * （资源释放不在本类职责内）。同时，凭据轮换仅在注入 {@link CredentialPool}
     * 且其已 seed 后才生效，未满足前该分支处于 dormant 状态——此作为后续跟进项追踪。
     * 本方法不做资源管理重构。</p>
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

    // 【容错】主备模型链切换：按序尝试 fallback 路由，尊重路由冷却，递归跳过失效/解析失败的路由
    //NOTE 这里tryActivateFallback()是执行器的核心，执行器收到 PROVIDER_FALLBACK 指令后就会调用这个方法,它做的：
    // 1. 因为原因是限流类 → 写 60 秒冷却期（防止刚切回来又立刻切走）
    //  2. 从链里取下一个 ModelRoute（glm-flash）
    //  3. 通过 providerRegistry.resolve() 找到对应 Provider
    //  4. 创建新的 ChatModel 替换掉 currentChatModel——注意不是"记住下次用"，而是就地换枪，接下来 while
    //  循环立刻用新模型重试同一个 prompt
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
            recordFallbackSwitch();
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

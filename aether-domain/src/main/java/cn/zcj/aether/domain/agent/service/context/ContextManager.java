package cn.zcj.aether.domain.agent.service.context;

import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.context.compaction.CompactionPipeline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文管理器 — 多层上下文维护
 *
 * <p>吸取项目B:</p>
 * <ul>
 *   <li>query.ts:376-394  applyToolResultBudget</li>
 *   <li>query.ts:412-426  microcompact</li>
 *   <li>autoCompact.ts:33 getEffectiveContextWindowSize</li>
 *   <li>MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20000 (来自 p99.99 生产数据)</li>
 * </ul>
 *
 * <p>P2 上下文压缩守卫（2026-07-28）：</p>
 * <ul>
 *   <li>{@link #alignToolPairBoundaries(List)} — 移植 autogen _head_and_tail 配对守卫</li>
 *   <li>{@link #trimMessages(List, int)} — 消息修剪下沉 + 配对对齐 + 占位消息</li>
 *   <li>autoCompact 切分点对齐 + 摘要防污染前缀</li>
 *   <li>连续压缩失败熔断器（MAX_CONSECUTIVE_COMPACT_FAILURES=3）</li>
 * </ul>
 *
 * <p><b>【架构亮点 · 上下文工程与成本治理】</b><br>
 * 面试举证点：①自动压缩双阈值触发（:356-357）+ 头尾保护 + tool_use/tool_result 配对边界对齐防孤儿消息（:386-412）；<br>
 * ②防污染前缀 SUMMARY_PREFIX（:92-93,457）防止模型把历史摘要当指令执行；<br>
 * ③摘要冷却 600s（:74-78）+ 连续失败 3 次熔断（:83-89,373-381）抑制徒劳 LLM 调用；<br>
 * ④trimMessages 占位提示（:192-228）、超长工具结果 50000 字符写盘（:239-258）、microCompact 去冗余写操作（:270-326）构成分层上下文治理，与 TokenBudget 三层预算、ModelInvoker 真实 usage 形成完整闭环。</p>
 */
@Slf4j
@Service
public class ContextManager {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** O7: 摘要 LLM 调用经由 ModelInvoker（超时/重试收口） */
    private final ModelInvoker modelInvoker;

    /** O7: 成本累计定价查询 */
    private final ModelPricingRegistry pricingRegistry;

    public ContextManager(ModelInvoker modelInvoker, ModelPricingRegistry pricingRegistry) {
        this.modelInvoker = modelInvoker;
        this.pricingRegistry = pricingRegistry;
    }

    @Resource
    private TokenEstimator tokenEstimator;

    /** O8: 压缩触发/裁剪参数（字段注入；单测无容器时使用默认实例=改造前行为） */
    @Resource
    private cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger compactionTrigger
            = new cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger();

    /** O8: 超长工具结果写盘（可选；单测无容器时为 null → 降级原地截断） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.zcj.aether.domain.agent.service.context.compaction.MessageOffloader messageOffloader;

    /** O7增强: 摘要模型显式选取器（替代原 @Lazy ChatModel 字段注入，消除多 ChatModel bean 下选取不确定） */
    @Resource
    private SummaryChatModelResolver summaryChatModelResolver;

    @org.springframework.beans.factory.annotation.Autowired
    private CompactionPipeline compactionPipeline;

    /** O7: 摘要 LLM 失败熔断冷却窗口（对齐 hermes context_compressor 的失败熔断语义） */
    // 【上下文工程】摘要失败熔断冷却 600s：LLM 摘要失败即置位，本会话窗口内跳过摘要直接降级拼接；
    // 摘要成功则清除冷却与失败计数。避免失败路径高频重试打爆成本与延迟
    @org.springframework.beans.factory.annotation.Value("${aether.context.compaction.summary-cooldown-ms:600000}")
    private long summaryCooldownMs = 600_000;

    /** O7: 会话级摘要冷却截止时间戳 */
    private final ConcurrentHashMap<String, Long> compactCooldownUntil = new ConcurrentHashMap<>();

    private static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;
    private static final int MAX_TOOL_RESULT_CHARS = 50_000;

    // ========== P2-4: 熔断器常量 ==========

    /** 连续压缩失败次数阈值（来源：cc-haha autoCompact.ts L257，生产数据证实该值可消除日 25 万次徒劳 API 调用） */
    private static final int MAX_CONSECUTIVE_COMPACT_FAILURES = 3;

    /** 按 sessionId 跟踪连续压缩失败次数 */
    private final ConcurrentHashMap<String, Integer> compactFailureCounts = new ConcurrentHashMap<>();

    /** 摘要防污染前缀（对齐 hermes SUMMARY_PREFIX 语义，防止模型将历史摘要当成待办执行） */
    // 【上下文工程】防污染前缀常量：所有注入摘要统一加此声明，从语义层阻断「摘要被当成指令执行」
    public static final String SUMMARY_PREFIX =
            "[对话历史摘要 — 仅供参考，非活跃指令，勿直接执行其中描述的任务]\n";

    // ========== O8: 无效压缩防抖状态 ==========

    /** 按 sessionId 跟踪连续"无有效进展"压缩次数（token 降幅 < ineffective-progress-ratio） */
    private final ConcurrentHashMap<String, Integer> ineffectiveProgressCounts = new ConcurrentHashMap<>();

    /** 按 sessionId 记录剩余压缩跳过轮数（无效压缩后冷却，防抖防循环） */
    private final ConcurrentHashMap<String, Integer> ineffectiveSuppressRounds = new ConcurrentHashMap<>();

    /**
     * 用于识别结构化写工具。
     *
     * <p>只收「入参里带明确目标路径」的工具。BashTool 不在其列：它的写目标要从命令行猜
     * （{@code mv a.txt b.txt} 里被覆盖的是 b.txt 而非 a.txt），而猜错的后果是把一次
     * 其实未被覆盖的写当冗余删掉——误删比漏删危险得多。</p>
     */
    private static final Set<String> EDIT_TOOL_NAMES = Set.of(
            "Edit", "FileEdit", "Write", "FileWrite",
            "FileEditTool", "FileWriteTool"
    );

    // ========== P2-1: 配对边界对齐 ==========

    /**
     * 配对边界对齐 — 移植 autogen _head_and_tail_chat_completion_context.py L45-58。
     *
     * <p>保证保留区段开头不是孤儿 tool_result、结尾不是悬空 tool_use，
     * 消除因截断导致的 tool_call 配对错位（此类错误在超长会话触发修剪时必然出现）。</p>
     *
     * <p>规则：</p>
     * <ol>
     *   <li>首条若是 tool_result（其 tool_use 已被裁掉）→ 丢弃，循环直到首条不是 tool_result</li>
     *   <li>末条若是 tool_use（其 tool_result 将被裁掉）→ 丢弃，循环直到末条不是 tool_use。
     *       tool_use 包括：独立 tool_use 消息（role=tool_use）和含 toolCalls 的 assistant 消息。</li>
     * </ol>
     *
     * <p>复杂度 O(n) — 仅遍历首尾边界。</p>
     *
     * @param messages 待对齐的消息列表（会被原地修改）
     * @return 对齐后的消息列表（同引用）
     */
    public List<TurnMessage> alignToolPairBoundaries(List<TurnMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        int removedHead = 0;
        int removedTail = 0;

        // 规则 1：丢弃开头连续的孤儿 tool_result
        while (!messages.isEmpty() && isOrphanToolResult(messages.get(0))) {
            messages.remove(0);
            removedHead++;
        }

        // 规则 2：丢弃末尾连续的悬空 tool_use
        while (!messages.isEmpty() && carriesToolCalls(messages.get(messages.size() - 1))) {
            messages.remove(messages.size() - 1);
            removedTail++;
        }

        if (removedHead > 0 || removedTail > 0) {
            log.info("alignToolPairBoundaries: 移除头部 {} 条孤儿 tool_result, 尾部 {} 条悬空 tool_use",
                    removedHead, removedTail);
        }

        return messages;
    }

    /**
     * 判断消息是否为孤儿 tool_result（其 tool_use 不在当前列表中）。
     * 由于我们无法追溯 toolCallId 对应关系，采用启发式：
     * 列表开头的 tool_result 极大概率为孤儿（其 tool_use 在截断点之前已被裁掉）。
     */
    private boolean isOrphanToolResult(TurnMessage msg) {
        return msg.isToolResult();
    }

    /**
     * 消息是否携带工具调用（即其 tool_result 缺失时该消息即「悬空」）。
     *
     * <p>两种形状：独立 tool_use 消息（{@code role=tool_use}）与含 toolCalls 的 assistant
     * 消息（生产形状）。判据只在此处表达一次——历史缺陷正是同一判据在不同方法里
     * 写法不一致导致的。</p>
     */
    private boolean carriesToolCalls(TurnMessage msg) {
        return msg.isToolUse() || msg.hasToolCalls();
    }

    // ========== P2-2: 消息修剪下沉 ==========

    /**
     * 消息修剪 — 下沉自 ReActAgent L148-165 原修剪块。
     *
     * <p>逻辑：保留首条 system 消息 + 最近 maxKeep 条 → 配对边界对齐 → 插入占位消息。</p>
     *
     * <p>对齐 autogen _head_and_tail L66 的 Skipped 占位，让模型感知历史缺失。</p>
     *
     * @param messages 当前完整消息列表（会被原地修改）
     * @param maxKeep  最多保留的最近消息条数（不含首条 system）
     * @return 修剪后的消息列表（同引用）
     */
    public List<TurnMessage> trimMessages(List<TurnMessage> messages, int maxKeep) {
        if (messages == null || messages.size() <= maxKeep + 1) {
            return messages;
        }

        int originalSize = messages.size();
        List<TurnMessage> kept = new ArrayList<>();

        // 保留首条（通常是 system initial）
        if (!messages.isEmpty()) {
            kept.add(messages.get(0));
        }

        // 保留最近 maxKeep 条
        int fromIndex = Math.max(1, originalSize - maxKeep);
        if (fromIndex < originalSize) {
            kept.addAll(messages.subList(fromIndex, originalSize));
        }

        // 配对边界对齐
        alignToolPairBoundaries(kept);

        // 插入占位消息（对齐 autogen L66 Skipped 占位）
        // 【上下文工程】占位提示：被裁剪的较早消息以 [系统提示] 占位告知模型，保留对话连续感而不泄露已省略内容
        int skipped = originalSize - kept.size();
        if (skipped > 0) {
            String placeholder = String.format(
                    "[系统提示] 已跳过 %d 条较早消息（上下文窗口管理，较早的对话历史已被省略）", skipped);
            // 插入到首条之后、保留段之前
            kept.add(1, TurnMessage.user(placeholder));
        }

        messages.clear();
        messages.addAll(kept);

        log.info("trimMessages: {} → {} 条 (跳过 {} 条)", originalSize, kept.size(), skipped);
        return messages;
    }

    // ========== 原有方法 ==========

    /**
     * 工具结果裁剪 — 超长结果（> {@value #MAX_TOOL_RESULT_CHARS} 字符）存盘并替换为路径引用。
     *
     * <p>O8: 完整内容经 {@code MessageOffloader.offloadToolResult} 写入
     * {@code <user.dir>/.aether/sessions/tool-overflow/}（按内容哈希命名，同内容不重复写盘），
     * 消息体内保留前 500 字符预览 + 存盘路径引用（注意：引用是**相对** {@code .aether/sessions/}
     * 的路径，回读需自行拼接基准）；
     * 写盘不可用（未装配/IO 失败）时降级为原地截断。</p>
     */
    public List<TurnMessage> applyToolResultBudget(List<TurnMessage> messages) {
        List<TurnMessage> result = new ArrayList<>(messages.size());
        for (TurnMessage msg : messages) {
            if (msg.isToolResult() && msg.content() != null
                    && msg.content().length() > MAX_TOOL_RESULT_CHARS) {
                // 【上下文工程】超长工具结果（>50000 字符）写盘：保留前 500 字符预览 + 路径引用，避免巨型输出撑爆上下文
                String preview = msg.content().substring(0, 500);
                String reference = messageOffloader != null
                        ? messageOffloader.offloadToolResult(msg.toolCallId(), msg.toolName(), msg.content())
                        : null;
                String replaced = reference != null
                        ? preview + "\n... [工具输出超过 " + MAX_TOOL_RESULT_CHARS + " 字符，已存盘，完整内容见文件: "
                                + reference + "]"
                        : preview + "\n... [工具输出已截断，完整内容 > " + MAX_TOOL_RESULT_CHARS + " 字符]";
                result.add(TurnMessage.toolResult(msg.toolCallId(), msg.toolName(), replaced));
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 微压缩 — 移除「已被后续写覆盖」的冗余写操作。
     *
     * <p>只处理生产形状：工具调用一律是 assistant 消息上的 toolCalls（见
     * {@code ReActAgent} 的构造点）。</p>
     *
     * <p>Pass 1: 建立 normalizedPath → 最后一次写所在的消息索引。</p>
     * <p>Pass 2: 判定可整条移除的消息；配对的 tool_result 按 toolCallId 精确移除。</p>
     * <p>Pass 3: 被移除处替换为 user 角色占位（配对中立），使「此处有一次被省略的写」对模型可见。</p>
     *
     * <p>保守规则（宁可少省 token，不可误删或产生孤儿消息）：一条消息可整条移除，
     * 当且仅当其全部 call 都是写工具、路径全部可提取、全部已被后续写覆盖、toolCallId 全部可解析。
     * 任一条不满足即整条保留。</p>
     */
    public List<TurnMessage> microCompact(List<TurnMessage> messages) {
        // 【上下文工程】微压缩：移除被后续写覆盖的冗余写操作，按 toolCallId 精确配对避免孤儿 tool_result
        int n = messages.size();

        // Pass 1: normalizedPath → 最后一次写所在的消息索引（顺序覆盖，剩下的即最后一次）
        Map<String, Integer> lastWriteIndex = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            for (WriteCall wc : extractWriteCalls(messages.get(i))) {
                if (wc.path() != null) {
                    lastWriteIndex.put(wc.path(), i);
                }
            }
        }
        if (lastWriteIndex.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // Pass 2: 判定可移除的消息及其配对的 tool_result
        Map<Integer, String> placeholders = new LinkedHashMap<>();
        Set<Integer> removeIndices = new HashSet<>();
        for (int i = 0; i < n; i++) {
            TurnMessage msg = messages.get(i);
            if (!msg.hasToolCalls()) continue;

            List<WriteCall> writes = extractWriteCalls(msg);
            // 含任一非写调用（如 Read）→ 整条保留，绝不重建消息
            if (writes.size() != msg.toolCalls().size()) continue;
            // 路径抠不出 → 无法证明其被覆盖；含该路径的最后一次写 → 不冗余。任一命中即整条保留
            boolean removable = true;
            for (WriteCall w : writes) {
                if (w.path() == null || lastWriteIndex.get(w.path()) == i) {
                    removable = false;
                    break;
                }
            }
            if (!removable) continue;

            Set<String> ids = callIdsOf(msg);
            // 配对不可证明 → 整条保留
            if (ids.isEmpty()) continue;

            placeholders.put(i, placeholderOf(writes));
            removeIndices.add(i);
            for (int j = i + 1; j < n && messages.get(j).isToolResult(); j++) {
                if (ids.contains(messages.get(j).toolCallId())) {
                    removeIndices.add(j);
                } else {
                    break;
                }
            }
        }
        if (placeholders.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // Pass 3: 重建 —— 被移除的写消息替换为占位（user 角色，配对中立）
        List<TurnMessage> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (placeholders.containsKey(i)) {
                kept.add(TurnMessage.user(placeholders.get(i)));
            } else if (!removeIndices.contains(i)) {
                kept.add(messages.get(i));
            }
        }

        log.info("microCompact: 移除 {} 条冗余消息，留 {} 条占位", n - kept.size(), placeholders.size());
        return kept;
    }

    /**
     * 自动压缩 — token 超阈值时调用 LLM 生成摘要。
     *
     * <p>P2-3: keepRecent 切分后对 recent 区段做配对边界对齐，保证进入 LLM 摘要的配对完整。</p>
     * <p>P2-3: 摘要注入带防污染前缀（"仅供参考，非活跃指令"）。</p>
     * <p>P2-4: 连续压缩失败熔断器（MAX_CONSECUTIVE_COMPACT_FAILURES=3）。</p>
     * <p>O8: 触发条件参数化 — {@code currentTokens >= effectiveWindow × threshold-percent}
     * 且消息数 ≥ {@code min-messages-to-compact}（替代原硬编码 0.9 / 20 条）；
     * 头部 {@code protect-first-n} 条 + 尾部 {@code protect-last-n} 条（或按
     * {@code tail-token-ratio} 的 token 预算）原样保护不参与摘要（对齐 hermes
     * protect_first_n/protect_last_n/_find_tail_cut_by_tokens）；压缩后 token 降幅
     * 低于 {@code ineffective-progress-ratio} 视为无有效进展，随后
     * {@code ineffective-suppress-rounds} 轮内跳过压缩（防抖防循环）。</p>
     *
     * @param messages  当前消息列表
     * @param modelName 模型名称
     * @param sessionId 会话 ID（用于熔断器/防抖跟踪）
     * @return 压缩结果
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName, String sessionId, TokenBudget tokenBudget) {

        int currentTokens = estimateTokens(messages);
        int contextWindow = tokenEstimator.getContextWindow(modelName);
        int effectiveWindow = contextWindow - MAX_OUTPUT_TOKENS_FOR_SUMMARY;
        double thresholdPercent = compactionTrigger.getThresholdPercent();
        int threshold = (int) (effectiveWindow * thresholdPercent);

        if (currentTokens <= threshold
                || messages.size() < compactionTrigger.getMinMessagesToCompact()) {
            // 【上下文工程】双阈值触发：token 占比超阈值「且」消息数达下限才压缩，避免小对话误压缩
            return AutoCompactResult.notNeeded();
        }

        // O8: 无效压缩防抖 — 冷却轮数内跳过压缩
        if (sessionId != null) {
            int remaining = ineffectiveSuppressRounds.getOrDefault(sessionId, 0);
            if (remaining > 0) {
                ineffectiveSuppressRounds.put(sessionId, remaining - 1);
                log.info("[防抖] 会话 {} 近期压缩无有效进展，跳过本轮压缩（剩余冷却轮数={}）",
                        sessionId, remaining - 1);
                return AutoCompactResult.notNeeded();
            }
        }

        // P2-4: 熔断器检查
        // 【上下文工程】连续压缩失败 3 次即永久放弃本会话自动压缩，避免日复一日徒劳 LLM 调用
        if (sessionId != null) {
            int failCount = compactFailureCounts.getOrDefault(sessionId, 0);
            if (failCount >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
                log.warn("[熔断] 会话 {} 连续压缩失败 {} 次，本会话永久放弃自动压缩。"
                        + "当前 tokens={}, threshold={}, messages={}",
                        sessionId, failCount, currentTokens, threshold, messages.size());
                return AutoCompactResult.notNeeded();
            }
        }

        log.info("触发自动压缩: currentTokens={} threshold={} ({}% of {}) messages={}",
                currentTokens, threshold, thresholdPercent * 100, effectiveWindow, messages.size());

        // O8: 头尾保护切分 — head 原样保留，tail 原样保留，中段进摘要
        // 【上下文工程】头尾保护 + 配对边界对齐：关键系统提示与最新上下文原样保留，切分点避开 tool 配对防孤儿消息
        int protectFirst = Math.max(0, Math.min(
                compactionTrigger.getProtectFirstN(), messages.size() / 2));
        int protectLast = computeProtectLast(messages, protectFirst, contextWindow);
        List<TurnMessage> head = new ArrayList<>(
                messages.subList(0, protectFirst));
        List<TurnMessage> recent = new ArrayList<>(
                messages.subList(messages.size() - protectLast, messages.size()));
        List<TurnMessage> toCompact = new ArrayList<>(
                messages.subList(protectFirst, messages.size() - protectLast));

        // P2-3: 切分点对齐 — 对 recent 区段开头做配对守卫
        // 若 recent 开头是孤儿 tool_result，将其移入 toCompact 一并摘要
        // 【上下文工程】配对边界对齐：把孤儿 tool_result 移入摘要段、悬空 tool_use 移回保留段，杜绝工具消息断裂
        int movedToCompact = 0;
        while (!recent.isEmpty() && isOrphanToolResult(recent.get(0))) {
            TurnMessage orphan = recent.remove(0);
            toCompact.add(orphan);
            movedToCompact++;
        }
        // 若 toCompact 末尾是悬空 tool_use，将其移入 recent（避免摘要内容配对不完整）
        while (!toCompact.isEmpty() && carriesToolCalls(toCompact.get(toCompact.size() - 1))) {
            TurnMessage dangling = toCompact.remove(toCompact.size() - 1);
            recent.add(0, dangling);
        }
        if (movedToCompact > 0) {
            log.info("切分点对齐: 将 {} 条孤儿 tool_result 从 recent 移入 toCompact", movedToCompact);
        }

        // C2 + O7: 摘要 LLM 调用（冷却窗口内跳过）
        boolean llmSuccess = false;
        String summary = null;
        long llmDuration = 0;
        boolean inCooldown = sessionId != null && isInSummaryCooldown(sessionId);
        if (inCooldown) {
            log.warn("[冷却] 会话 {} 处于摘要冷却窗口 ({}ms)，跳过 LLM 摘要调用，直接降级",
                    sessionId, summaryCooldownMs);
        } else {
            long llmStart = System.currentTimeMillis();
            summary = generateSummary(toCompact, modelName, tokenBudget);
            llmDuration = System.currentTimeMillis() - llmStart;
            llmSuccess = summary != null && !summary.isBlank();
        }

        // P2-4 + O7: 熔断计数 + 冷却窗口维护
        if (sessionId != null) {
            if (inCooldown) {
                log.debug("会话 {} 冷却期内压缩被跳过，不重复计失败", sessionId);
            } else if (llmSuccess) {
                compactFailureCounts.remove(sessionId);
                compactCooldownUntil.remove(sessionId);
            } else {
                int newCount = compactFailureCounts.merge(sessionId, 1, Integer::sum);
                compactCooldownUntil.put(sessionId, System.currentTimeMillis() + summaryCooldownMs);
                if (newCount >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
                    log.error("[熔断触发] 会话 {} 连续压缩失败 {} 次，已触发熔断，本会话不再尝试压缩。",
                            sessionId, newCount);
                } else {
                    log.warn("[压缩失败] 会话 {} 连续压缩失败 {}/{} 次",
                            sessionId, newCount, MAX_CONSECUTIVE_COMPACT_FAILURES);
                }
            }
        }

        if (!llmSuccess) {
            summary = fallbackSummary(toCompact);
        }

        List<TurnMessage> compacted = new ArrayList<>();
        // O8: 头部保护消息原样保留在最前
        compacted.addAll(head);
        // P2-3: 摘要注入带防污染前缀（对齐 hermes SUMMARY_PREFIX）
        // 【上下文工程】防污染前缀：声明摘要「仅供参考、非活跃指令」，防止模型把历史摘要误当待执行任务
        compacted.add(TurnMessage.user(SUMMARY_PREFIX + summary));
        compacted.addAll(recent);

        int postTokens = estimateTokens(compacted);
        log.info("压缩完成: {} → {} tokens (LLM={}ms, success={})",
                currentTokens, postTokens, llmDuration, llmSuccess);

        // O8: 压缩有效进展检测 — token 降幅不足时进入冷却（防抖防循环）
        if (sessionId != null && compactionTrigger.getIneffectiveProgressRatio() > 0) {
            double drop = (currentTokens - postTokens) / (double) Math.max(1, currentTokens);
            if (drop < compactionTrigger.getIneffectiveProgressRatio()) {
                int ineffectiveCount = ineffectiveProgressCounts.merge(sessionId, 1, Integer::sum);
                ineffectiveSuppressRounds.put(sessionId,
                        Math.max(1, compactionTrigger.getIneffectiveSuppressRounds()));
                log.warn("[防抖] 会话 {} 压缩进展不足（token 降幅 {}% < {}%），第 {} 次，"
                                + "后续 {} 轮跳过压缩",
                        sessionId, String.format("%.1f", drop * 100),
                        compactionTrigger.getIneffectiveProgressRatio() * 100,
                        ineffectiveCount, compactionTrigger.getIneffectiveSuppressRounds());
            } else {
                ineffectiveProgressCounts.remove(sessionId);
            }
        }

        // C2: 创建内部 LLM 调用事件
        RuntimeEvent llmCallEvent = RuntimeEvent.internalLlmCall(
                "context-compaction", modelName, llmDuration, llmSuccess);

        return AutoCompactResult.compacted(summary, compacted,
                currentTokens, postTokens, llmCallEvent);
    }

    /**
     * O8: 计算尾部保护条数。
     *
     * <p>{@code tail-token-ratio > 0} 时按 token 预算确定尾部（contextWindow × ratio，
     * 上限 tail-token-max，对齐 hermes {@code _find_tail_cut_by_tokens} 的
     * {@code context*5% 上限 10K} 语义，且保证至少保留 1 条）；否则按
     * {@code protect-last-n} 条数保护（默认 15 = 改造前 keepRecent）。</p>
     */
    private int computeProtectLast(List<TurnMessage> messages, int protectFirst, int contextWindow) {
        int byCount = Math.max(1, Math.min(
                compactionTrigger.getProtectLastN(), messages.size() - protectFirst));
        int tailBudget = compactionTrigger.tailTokenBudget(contextWindow);
        if (tailBudget <= 0) {
            return byCount;
        }
        int acc = 0;
        int count = 0;
        for (int i = messages.size() - 1; i >= protectFirst; i--) {
            int t = tokenEstimator.estimate(messages.get(i).content());
            if (count > 0 && acc + t > tailBudget) {
                break;
            }
            acc += t;
            count++;
        }
        return Math.max(count, 1);
    }

    /**
     * O8: 会话剩余压缩冷却轮数（监控/测试用）。
     */
    public int getIneffectiveSuppressRounds(String sessionId) {
        return ineffectiveSuppressRounds.getOrDefault(sessionId, 0);
    }

    /**
     * O8: 重置会话防抖状态（测试/手动恢复）。
     */
    public void resetIneffectiveSuppress(String sessionId) {
        ineffectiveProgressCounts.remove(sessionId);
        ineffectiveSuppressRounds.remove(sessionId);
    }

    /**
     * 向后兼容：无 TokenBudget 的自动压缩（熔断器/防抖跟踪仍生效）。
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName, String sessionId) {
        return autoCompactIfNeeded(messages, modelName, sessionId, null);
    }

    /**
     * 向后兼容：无 sessionId 的自动压缩（供不需要熔断器的调用方使用）。
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName) {
        return autoCompactIfNeeded(messages, modelName, null);
    }

    public boolean isAtBlockingLimit(List<TurnMessage> messages, String modelName) {
        return estimateTokens(messages) >= tokenEstimator.getContextWindow(modelName);
    }

    /**
     * 运行 CompactionPipeline 六步压缩管道。
     *
     * @param messages  当前完整消息列表
     * @param modelName 模型名称
     * @param sessionId 会话 ID
     * @param startTurn 起始回合号
     * @return 压缩结果
     */
    public CompactionPipeline.CompactionResult runCompactionPipeline(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn) {
        return runCompactionPipeline(messages, modelName, sessionId, startTurn, null);
    }

    public CompactionPipeline.CompactionResult runCompactionPipeline(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn, TokenBudget tokenBudget) {
        return compactionPipeline.compactIfNeeded(messages, modelName, sessionId, startTurn, tokenBudget);
    }

    /**
     * O7: 会话是否处于摘要冷却窗口。
     */
    public boolean isInSummaryCooldown(String sessionId) {
        Long until = compactCooldownUntil.get(sessionId);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * O7: 重置会话摘要冷却（测试/手动恢复）。
     */
    public void resetSummaryCooldown(String sessionId) {
        compactCooldownUntil.remove(sessionId);
    }

    // ============ private helpers ============

    /**
     * 调用 LLM 生成对话摘要 —— O7: 经 ModelInvoker.callWithStream（超时收口），
     * 成功后将 tokens 计入 TokenBudget 成本熔断；tokenBudget 为 null 时跳过累计（旧 3 参路径）。
     */
    private String generateSummary(List<TurnMessage> toCompact, String modelName, TokenBudget tokenBudget) {
        try {
            // O7增强: 摘要模型显式选取（配置 aether.context.compaction.summary-model 优先，回退 chatModel）
            ChatModel chatModel = summaryChatModelResolver != null
                    ? summaryChatModelResolver.resolve() : null;
            if (chatModel == null) {
                log.warn("LLM摘要: 无可用 ChatModel（SummaryChatModelResolver 未注入），降级为字符串拼接");
                return null;
            }

            StringBuilder conversation = new StringBuilder();
            for (TurnMessage msg : toCompact) {
                String content = msg.content() != null ? msg.content() : "";
                String preview = content.length() > 300
                        ? content.substring(0, 300) + "..."
                        : content;
                conversation.append("[").append(msg.role()).append("]: ")
                        .append(preview).append("\n");
            }

            String instruction = """
                    请将以下对话历史压缩为简洁的摘要（不超过500字）。
                    保留关键决策、重要结论、文件修改操作和未完成的任务。
                    使用中文输出。
                    """;

            ModelInvoker.ModelCallResult result = modelInvoker.callWithStream(
                    chatModel,
                    List.of(new UserMessage(conversation.toString())),
                    instruction,
                    modelName);

            if (result != null && !result.hasError()
                    && result.getFullText() != null && !result.getFullText().isBlank()) {
                if (tokenBudget != null && pricingRegistry != null) {
                    tokenBudget.accumulateCost(result.getInputTokens(), result.getOutputTokens(),
                            pricingRegistry.lookup(modelName));
                }
                return result.getFullText().trim();
            }
        } catch (Exception e) {
            log.warn("LLM摘要生成失败，降级为字符串拼接", e);
        }
        return null;
    }

    /**
     * 降级摘要 — 简单字符串拼接
     */
    private String fallbackSummary(List<TurnMessage> toCompact) {
        StringBuilder sb = new StringBuilder();
        sb.append("共压缩 ").append(toCompact.size()).append(" 条消息。\n");
        for (TurnMessage msg : toCompact) {
            String content = msg.content() != null ? msg.content() : "";
            String preview = content.length() > 100
                    ? content.substring(0, 100) + "..."
                    : content;
            sb.append("[").append(msg.role()).append("] ").append(preview).append("\n");
        }
        return sb.toString();
    }

    /**
     * P2-4: 获取会话的连续压缩失败次数（用于监控/测试）。
     */
    public int getCompactFailureCount(String sessionId) {
        return compactFailureCounts.getOrDefault(sessionId, 0);
    }

    /**
     * P2-4: 重置会话的压缩失败计数（用于测试/手动恢复）。
     */
    public void resetCompactFailureCount(String sessionId) {
        compactFailureCounts.remove(sessionId);
    }

    private int estimateTokens(List<TurnMessage> messages) {
        int total = 0;
        for (TurnMessage msg : messages) {
            total += tokenEstimator.estimate(msg.content());
        }
        return total;
    }

    private boolean isEditTool(String name) {
        return name != null && EDIT_TOOL_NAMES.contains(name);
    }

    /** 一次写调用：目标路径已归一化；路径不可提取时为 null。 */
    private record WriteCall(String path) {}

    /**
     * 提取消息中的写调用（仅 {@link #EDIT_TOOL_NAMES} 内的结构化写工具）。
     *
     * <p>只认生产形状：工具调用一律是 assistant 消息上的 toolCalls。独立 tool_use 消息
     * （{@code role=tool_use}）全项目没有任何产出方，不为它保留分支——为不存在的形状写代码
     * 正是本方法最初整体失效的原因。</p>
     */
    private List<WriteCall> extractWriteCalls(TurnMessage msg) {
        List<WriteCall> out = new ArrayList<>();
        if (!msg.hasToolCalls()) {
            return out;
        }
        for (Map<String, Object> tc : msg.toolCalls()) {
            Object name = tc.get("name");
            if (name == null || !isEditTool(String.valueOf(name))) continue;
            out.add(new WriteCall(normalizePathOrNull(pathFromInput(tc.get("input")))));
        }
        return out;
    }

    /** 该消息全部工具调用的 id；任一 call 缺 id 即视为「配对不可证明」，返回空集。 */
    private Set<String> callIdsOf(TurnMessage msg) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> tc : msg.toolCalls()) {
            Object id = tc.get("id");
            if (id == null || String.valueOf(id).isBlank()) {
                return Set.of();
            }
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    /**
     * 从工具入参中取文件路径：{@code Map} 走结构化键，{@code String} 走 {@link #extractPath(String)}。
     * 抠不出返回 null —— 调用方据此整条保留，绝不猜。
     */
    private String pathFromInput(Object input) {
        if (input instanceof Map<?, ?> map) {
            for (String key : List.of("file_path", "filePath", "path")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
            return null;
        }
        if (input instanceof String s) {
            return extractPath(s);
        }
        return null;
    }

    private String normalizePathOrNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return normalizePath(path);
    }

    /**
     * 被省略写操作的占位文本 —— 让模型看得见「此处有一次被省略的写」，
     * 而不是引用一个凭空消失的操作（悬空引用）。
     */
    private String placeholderOf(List<WriteCall> writes) {
        Set<String> names = new LinkedHashSet<>();
        for (WriteCall w : writes) {
            String path = w.path();
            int slash = path.lastIndexOf('/');
            names.add(slash >= 0 ? path.substring(slash + 1) : path);
        }
        return "[系统提示] 对 " + String.join("、", names) + " 的写入已被后续写入覆盖，已省略";
    }

    /**
     * 从 tool input 内容中提取文件路径
     *
     * tool input 内容格式可能是:
     *   - JSON: {"file_path":"src/main/App.java", ...}
     *   - JSON: {"path":"/tmp/foo.txt", ...}
     *   - 纯文本路径: /home/user/file.txt
     */
    private String extractPath(String content) {
        if (content == null || content.isBlank()) return null;

        // 尝试 JSON 解析
        if (content.trim().startsWith("{")) {
            try {
                Map<String, Object> map = objectMapper.readValue(content,
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                if (map.containsKey("file_path")) {
                    return String.valueOf(map.get("file_path"));
                }
                if (map.containsKey("filePath")) {
                    return String.valueOf(map.get("filePath"));
                }
                if (map.containsKey("path")) {
                    return String.valueOf(map.get("path"));
                }
            } catch (Exception ignored) {
                // 非JSON内容 → 尝试其他方式
            }
        }

        // 尝试从文本中提取类Unix路径
        return extractPathFromText(content);
    }

    /**
     * 从纯文本中提取文件路径
     */
    private String extractPathFromText(String content) {
        // 匹配 Unix/Windows 路径模式
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "([/\\\\]?[\\w.\\-]+[/\\\\][\\w.\\-/\\\\]+\\.[\\w]+)"
        ).matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/').replaceAll("/+", "/").toLowerCase();
    }
}

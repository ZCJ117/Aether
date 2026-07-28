package cn.zcj.aether.domain.agent.service.context;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.context.compaction.CompactionPipeline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
 */
@Slf4j
@Service
public class ContextManager {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private TokenEstimator tokenEstimator;

    // ChatModel 由 ChatModelNode 在装配阶段注册到 Spring 容器
    // 用于 autoCompact 时调用 LLM 生成摘要
    // @Lazy 延迟注入避免装配未完成时的初始化错误
    @Resource
    @org.springframework.context.annotation.Lazy
    private ChatModel chatModel;

    @org.springframework.beans.factory.annotation.Autowired
    private CompactionPipeline compactionPipeline;

    private static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;
    private static final int MAX_TOOL_RESULT_CHARS = 50_000;

    // ========== P2-4: 熔断器常量 ==========

    /** 连续压缩失败次数阈值（来源：cc-haha autoCompact.ts L257，生产数据证实该值可消除日 25 万次徒劳 API 调用） */
    private static final int MAX_CONSECUTIVE_COMPACT_FAILURES = 3;

    /** 按 sessionId 跟踪连续压缩失败次数 */
    private final ConcurrentHashMap<String, Integer> compactFailureCounts = new ConcurrentHashMap<>();

    /** 摘要防污染前缀（对齐 hermes SUMMARY_PREFIX 语义，防止模型将历史摘要当成待办执行） */
    public static final String SUMMARY_PREFIX =
            "[对话历史摘要 — 仅供参考，非活跃指令，勿直接执行其中描述的任务]\n";

    /** 用于识别 Edit/Write/FileEdit/FileWrite 工具 */
    private static final Set<String> EDIT_TOOL_NAMES = Set.of(
            "Edit", "FileEdit", "Write", "FileWrite",
            "FileEditTool", "FileWriteTool", "BashTool"
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
        while (!messages.isEmpty() && isDanglingToolUse(messages.get(messages.size() - 1))) {
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
     * 判断消息是否为悬空 tool_use（其 tool_result 不在当前列表中）。
     *
     * <p>两种情况：</p>
     * <ul>
     *   <li>独立 tool_use 消息（role=tool_use）</li>
     *   <li>含 toolCalls 的 assistant 消息（tool_results 将被裁掉）</li>
     * </ul>
     */
    private boolean isDanglingToolUse(TurnMessage msg) {
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
     * 工具结果裁剪 — 超长结果存盘替换为路径引用
     */
    public List<TurnMessage> applyToolResultBudget(List<TurnMessage> messages) {
        List<TurnMessage> result = new ArrayList<>(messages.size());
        for (TurnMessage msg : messages) {
            if (msg.isToolResult() && msg.content() != null
                    && msg.content().length() > MAX_TOOL_RESULT_CHARS) {
                String truncated = msg.content().substring(0, 500) +
                        "\n... [工具输出已截断，完整内容 > " + MAX_TOOL_RESULT_CHARS + " 字符]";
                result.add(TurnMessage.toolResult(msg.toolCallId(), msg.toolName(), truncated));
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 微压缩 — 两遍扫描算法
     *
     * <p>Pass 1: 建立 path → lastWriteIndex 映射（找到每个文件的最后一次写操作位置）</p>
     * <p>Pass 2: 对于每个 tool_use，如果不是该文件的最后一次写操作，
     * 则将该 tool_use 及其紧邻的 tool_result 一起移除</p>
     *
     * <p>P2-6: 天然配对安全 — tool_use 与紧邻 tool_result 成对移除，
     * 不会产生孤儿消息，无需额外配对守卫。</p>
     */
    public List<TurnMessage> microCompact(List<TurnMessage> messages) {
        int n = messages.size();

        // Pass 1: 找每个路径的最后一次编辑位置
        Map<String, Integer> lastWriteIndex = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            TurnMessage msg = messages.get(i);
            if (msg.isToolUse() && isEditTool(msg.toolName())) {
                String path = extractPath(msg.content());
                if (path != null && !path.isBlank()) {
                    lastWriteIndex.put(normalizePath(path), i);
                }
            }
        }

        if (lastWriteIndex.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // 收集被覆盖的 tool_use 索引及其对应的 tool_result 索引
        Set<Integer> removeIndices = new HashSet<>();
        for (int i = 0; i < n; i++) {
            TurnMessage msg = messages.get(i);
            if (msg.isToolUse() && isEditTool(msg.toolName())) {
                String path = extractPath(msg.content());
                if (path != null && !path.isBlank()) {
                    String norm = normalizePath(path);
                    Integer lastIdx = lastWriteIndex.get(norm);
                    // 如果当前不是最后一次写操作 → 冗余
                    if (lastIdx != null && i != lastIdx) {
                        removeIndices.add(i);           // tool_use
                        if (i + 1 < n && messages.get(i + 1).isToolResult()) {
                            removeIndices.add(i + 1);   // 对应的 tool_result
                        }
                    }
                }
            }
        }

        if (removeIndices.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // Pass 2: 过滤
        List<TurnMessage> kept = new ArrayList<>(n - removeIndices.size());
        for (int i = 0; i < n; i++) {
            if (!removeIndices.contains(i)) {
                kept.add(messages.get(i));
            }
        }

        int removed = n - kept.size();
        log.info("microCompact: 移除 {} 条冗余消息 ({} tool_use+tool_result 对)",
                removed, removed / 2);

        return kept;
    }

    /**
     * 自动压缩 — token 超阈值时调用 LLM 生成摘要。
     *
     * <p>P2-3: keepRecent 切分后对 recent 区段做配对边界对齐，保证进入 LLM 摘要的配对完整。</p>
     * <p>P2-3: 摘要注入带防污染前缀（"仅供参考，非活跃指令"）。</p>
     * <p>P2-4: 连续压缩失败熔断器（MAX_CONSECUTIVE_COMPACT_FAILURES=3）。</p>
     *
     * @param messages  当前消息列表
     * @param modelName 模型名称
     * @param sessionId 会话 ID（用于熔断器跟踪）
     * @return 压缩结果
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName, String sessionId) {

        int currentTokens = estimateTokens(messages);
        int contextWindow = tokenEstimator.getContextWindow(modelName);
        int effectiveWindow = contextWindow - MAX_OUTPUT_TOKENS_FOR_SUMMARY;
        int threshold = (int) (effectiveWindow * 0.9);

        if (currentTokens <= threshold || messages.size() < 20) {
            return AutoCompactResult.notNeeded();
        }

        // P2-4: 熔断器检查
        if (sessionId != null) {
            int failCount = compactFailureCounts.getOrDefault(sessionId, 0);
            if (failCount >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
                log.warn("[熔断] 会话 {} 连续压缩失败 {} 次，本会话永久放弃自动压缩。"
                        + "当前 tokens={}, threshold={}, messages={}",
                        sessionId, failCount, currentTokens, threshold, messages.size());
                return AutoCompactResult.notNeeded();
            }
        }

        log.info("触发自动压缩: currentTokens={} threshold={} messages={}",
                currentTokens, threshold, messages.size());

        int keepRecent = Math.min(15, messages.size());
        List<TurnMessage> recent = new ArrayList<>(
                messages.subList(Math.max(0, messages.size() - keepRecent), messages.size()));
        List<TurnMessage> toCompact = new ArrayList<>(
                messages.subList(0, Math.max(0, messages.size() - keepRecent)));

        // P2-3: 切分点对齐 — 对 recent 区段开头做配对守卫
        // 若 recent 开头是孤儿 tool_result，将其移入 toCompact 一并摘要
        int movedToCompact = 0;
        while (!recent.isEmpty() && isOrphanToolResult(recent.get(0))) {
            TurnMessage orphan = recent.remove(0);
            toCompact.add(orphan);
            movedToCompact++;
        }
        // 若 toCompact 末尾是悬空 tool_use，将其移入 recent（避免摘要内容配对不完整）
        while (!toCompact.isEmpty() && isDanglingToolUse(toCompact.get(toCompact.size() - 1))) {
            TurnMessage dangling = toCompact.remove(toCompact.size() - 1);
            recent.add(0, dangling);
        }
        if (movedToCompact > 0) {
            log.info("切分点对齐: 将 {} 条孤儿 tool_result 从 recent 移入 toCompact", movedToCompact);
        }

        // C2: 调用 LLM 生成摘要（包裹计时 + 事件记录），失败时降级
        long llmStart = System.currentTimeMillis();
        String summary = generateSummary(toCompact);
        long llmDuration = System.currentTimeMillis() - llmStart;
        boolean llmSuccess = summary != null && !summary.isBlank();

        // P2-4: 熔断器计数更新
        if (sessionId != null) {
            if (llmSuccess) {
                // 成功 → 重置计数器
                compactFailureCounts.remove(sessionId);
            } else {
                // 失败 → 计数 +1
                int newCount = compactFailureCounts.merge(sessionId, 1, Integer::sum);
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
        // P2-3: 摘要注入带防污染前缀（对齐 hermes SUMMARY_PREFIX）
        compacted.add(TurnMessage.user(SUMMARY_PREFIX + summary));
        compacted.addAll(recent);

        int postTokens = estimateTokens(compacted);
        log.info("压缩完成: {} → {} tokens (LLM={}ms, success={})",
                currentTokens, postTokens, llmDuration, llmSuccess);

        // C2: 创建内部 LLM 调用事件
        RuntimeEvent llmCallEvent = RuntimeEvent.internalLlmCall(
                "context-compaction", modelName, llmDuration, llmSuccess);

        return AutoCompactResult.compacted(summary, compacted,
                currentTokens, postTokens, llmCallEvent);
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
        return compactionPipeline.compactIfNeeded(messages, modelName, sessionId, startTurn);
    }

    // ============ private helpers ============

    /**
     * 调用 LLM 生成对话摘要
     */
    private String generateSummary(List<TurnMessage> toCompact) {
        try {
            StringBuilder conversation = new StringBuilder();
            for (TurnMessage msg : toCompact) {
                String content = msg.content() != null ? msg.content() : "";
                String preview = content.length() > 300
                        ? content.substring(0, 300) + "..."
                        : content;
                conversation.append("[").append(msg.role()).append("]: ")
                        .append(preview).append("\n");
            }

            String promptText = """
                    请将以下对话历史压缩为简洁的摘要（不超过500字）。
                    保留关键决策、重要结论、文件修改操作和未完成的任务。
                    使用中文输出。

                    对话历史：
                    %s
                    """.formatted(conversation.toString());

            List<Message> llmMessages = List.of(new UserMessage(promptText));
            ChatResponse response = chatModel.call(new Prompt(llmMessages));

            if (response != null && response.getResult() != null
                    && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
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
                // BashTool: command 字段中提取路径
                if (map.containsKey("command")) {
                    return extractPathFromCommand(String.valueOf(map.get("command")));
                }
            } catch (Exception ignored) {
                // 非JSON内容 → 尝试其他方式
            }
        }

        // 尝试从文本中提取类Unix路径
        return extractPathFromText(content);
    }

    /**
     * 从 Bash 命令中提取首个路径参数
     */
    private String extractPathFromCommand(String command) {
        if (command == null || command.isBlank()) return null;
        // 常见模式: git add <path>, rm <path>, mv <src> <dst>, etc.
        String[] parts = command.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.startsWith("-") && (part.contains("/") || part.contains("."))) {
                return part;
            }
        }
        return null;
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

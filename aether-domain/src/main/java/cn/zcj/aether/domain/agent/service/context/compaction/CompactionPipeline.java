package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 六步压缩管道 — 编排压缩全流程。
 *
 * <p>步骤：</p>
 * <ol>
 *   <li>checkTrigger — CompactionTrigger.shouldCompact() 判断是否需要压缩</li>
 *   <li>findCutoff — SafeCutoffFinder.findCutoff() 找到安全切点</li>
 *   <li>truncateArgs — 截断 prefix 中工具调用的 args 至 200 字符</li>
 *   <li>flushMemories — 预留钩子（当前为空操作）</li>
 *   <li>offloadMessages — MessageOffloader.offload() JSONL 泄流</li>
 *   <li>summarizePrefix — ChunkSummarizer.summarize() 生成摘要</li>
 * </ol>
 *
 * <p>压缩后的消息结构：{@code [summary_user_message] + [kept_suffix_messages]}</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompactionPipeline {

    private static final int MAX_TOOL_ARG_CHARS = 200;

    private final CompactionTrigger compactionTrigger;
    private final SafeCutoffFinder safeCutoffFinder;
    private final ChunkSummarizer chunkSummarizer;
    private final MessageOffloader messageOffloader;
    private final TokenEstimator tokenEstimator;

    @Resource
    @org.springframework.context.annotation.Lazy
    private ChatModel chatModel;

    /**
     * 压缩结果记录。
     */
    public record CompactionResult(
            boolean compacted,
            String summary,
            List<TurnMessage> messages,
            int preCompactTokens,
            int postCompactTokens) {

        public static CompactionResult notNeeded(List<TurnMessage> original) {
            return new CompactionResult(false, null, original, 0, 0);
        }

        public static CompactionResult compacted(
                String summary, List<TurnMessage> messages,
                int preTokens, int postTokens) {
            return new CompactionResult(true, summary, messages, preTokens, postTokens);
        }
    }

    /**
     * 条件触发压缩：若满足触发条件则执行六步管道。
     *
     * @param messages  当前完整消息列表
     * @param modelName 模型名称，用于 token 估算
     * @param sessionId 会话 ID，用于 MessageOffloader 泄流
     * @param startTurn 起始回合号
     * @return 压缩结果，包含（可能已变更的）消息列表
     */
    public CompactionResult compactIfNeeded(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn) {

        if (messages == null || messages.isEmpty()) {
            return CompactionResult.notNeeded(messages);
        }

        // Step 1: checkTrigger — 判断是否需要压缩
        int messageCount = messages.size();
        int tokenCount = estimateTokens(messages);

        if (!compactionTrigger.shouldCompact(messageCount, tokenCount)) {
            log.debug("CompactionPipeline: 未触发压缩 (messages={}, tokens={})",
                    messageCount, tokenCount);
            return CompactionResult.notNeeded(messages);
        }

        log.info("CompactionPipeline: 触发压缩 (messages={}, tokens={}, threshold_messages={}, threshold_tokens={})",
                messageCount, tokenCount,
                compactionTrigger.getTriggerMessages(), compactionTrigger.getTriggerTokens());

        int preCompactTokens = tokenCount;

        // Step 2: findCutoff — 找到安全切点
        int cutoffIndex = safeCutoffFinder.findCutoff(messages, compactionTrigger.getKeepTokens());
        if (cutoffIndex <= 0) {
            log.debug("CompactionPipeline: 切点为0，无消息需要压缩");
            return CompactionResult.notNeeded(messages);
        }
        if (cutoffIndex >= messages.size()) {
            log.debug("CompactionPipeline: 切点超出范围，无消息保留");
            return CompactionResult.notNeeded(messages);
        }

        List<TurnMessage> prefix = new ArrayList<>(messages.subList(0, cutoffIndex));
        List<TurnMessage> suffix = new ArrayList<>(messages.subList(cutoffIndex, messages.size()));

        log.info("CompactionPipeline: 切点={} prefix={} suffix={}",
                cutoffIndex, prefix.size(), suffix.size());

        // Step 3: truncateArgs — 截断 prefix 中工具调用的 args 至 200 字符
        prefix = truncateToolArgs(prefix);

        // Step 4: flushMemories — 预留钩子（当前为空操作）

        // Step 5: offloadMessages — JSONL 泄流被压缩的 prefix
        messageOffloader.offload(sessionId, prefix, startTurn);

        // Step 6: summarizePrefix — 生成摘要
        String summary;
        try {
            summary = chunkSummarizer.summarize(prefix);
        } catch (Exception e) {
            log.warn("CompactionPipeline: 摘要生成失败", e);
            summary = "[压缩摘要生成失败: " + e.getMessage() + "]";
        }

        // 组装压缩后的消息列表
        List<TurnMessage> compacted = new ArrayList<>();
        // P2-3: 摘要注入带防污染前缀（对齐 hermes SUMMARY_PREFIX，复用 ContextManager 常量）
        compacted.add(TurnMessage.user(
                cn.zcj.aether.domain.agent.service.context.ContextManager.SUMMARY_PREFIX + summary));
        compacted.addAll(suffix);

        int postCompactTokens = estimateTokens(compacted);

        log.info("CompactionPipeline: 压缩完成 {} -> {} tokens ({} -> {} messages)",
                preCompactTokens, postCompactTokens,
                messages.size(), compacted.size());

        return CompactionResult.compacted(summary, compacted, preCompactTokens, postCompactTokens);
    }

    // ============ private helpers ============

    /**
     * 截断 prefix 中 tool_use 消息的 args 文本至 MAX_TOOL_ARG_CHARS 字符。
     * tool_result 消息不做截断（可能在后续步骤处理）。
     */
    private List<TurnMessage> truncateToolArgs(List<TurnMessage> messages) {
        List<TurnMessage> result = new ArrayList<>(messages.size());
        for (TurnMessage msg : messages) {
            if (msg.isToolUse() && msg.content() != null
                    && msg.content().length() > MAX_TOOL_ARG_CHARS) {
                String truncated = msg.content().substring(0, MAX_TOOL_ARG_CHARS)
                        + "... [tool args truncated]";
                result.add(new TurnMessage(
                        msg.role(), truncated, msg.toolCallId(),
                        msg.toolName(), msg.toolCalls()));
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    private int estimateTokens(List<TurnMessage> messages) {
        int total = 0;
        for (TurnMessage msg : messages) {
            total += tokenEstimator.estimate(msg.content());
        }
        return total;
    }
}

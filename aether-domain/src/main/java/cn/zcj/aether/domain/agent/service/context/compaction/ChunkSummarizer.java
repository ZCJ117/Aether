package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 并发分块摘要器（CrewAI 模式）。
 * <p>
 * 将长对话前缀拆分为多个文本块，逐块调用 LLM 生成子摘要，
 * 然后合并子摘要。若合并结果仍超长，则递归摘要，直到收敛为单个摘要。
 * 若 LLM 调用失败，降级为简单字符串拼接。
 * </p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>格式化消息时使用角色标签：[USER], [ASSISTANT], [TOOL_RESULT(name)]</li>
 *   <li>工具结果截断至 500 字符后写入格式化文本</li>
 *   <li>按段落边界分块，每块最大约 7000 字符</li>
 *   <li>逐块顺序调用 LLM（非并行，避免限流）</li>
 *   <li>合并子摘要超过 7000 字符时递归摘要</li>
 *   <li>LLM 失败时降级为简单拼接</li>
 * </ul>
 */
@Slf4j
@Component
public class ChunkSummarizer {

    private static final int MAX_CHUNK_CHARS = 7_000;
    private static final int TOOL_RESULT_TRUNCATE_CHARS = 500;

    /** O8: 合并摘要字符上限（可配 aether.context.compaction.summary-ceiling-chars，默认 7000 = 原硬编码） */
    @org.springframework.beans.factory.annotation.Value("${aether.context.compaction.summary-ceiling-chars:7000}")
    private int maxMergedChars = 7_000;

    private static final String SUMMARIZE_SYSTEM =
            "将以下对话片段压缩为简洁摘要。保留关键决策、重要结论、文件修改操作和未完成的任务。使用中文输出，不超过500字。";

    @Resource
    private TokenEstimator tokenEstimator;

    /** O7增强: 摘要模型显式选取（替代原 @Lazy ChatModel 字段注入，消除多 bean 不确定性） */
    @Resource
    private cn.zcj.aether.domain.agent.service.context.SummaryChatModelResolver summaryChatModelResolver;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private ModelPricingRegistry pricingRegistry;

    /**
     * 对对话前缀生成分块摘要。
     *
     * @param prefix     需要摘要的消息前缀列表
     * @param modelName  模型名称，用于 LLM 调用与成本累计
     * @param tokenBudget 成本预算（可为 null，null 时跳过成本累计）
     * @return 摘要文本；若全部 LLM 调用均失败，返回降级拼接文本
     */
    public String summarize(List<TurnMessage> prefix, String modelName, TokenBudget tokenBudget) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }

        // Step 1: 将消息列表格式化为文本
        String formatted = formatMessages(prefix);

        // Step 2: 按段落边界分块
        List<String> chunks = splitIntoChunks(formatted);

        if (chunks.isEmpty()) {
            return "";
        }

        // Step 3: 逐块生成子摘要
        List<String> subSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String summary = summarizeChunk(chunks.get(i), i + 1, chunks.size(), modelName, tokenBudget);
            if (summary != null && !summary.isBlank()) {
                subSummaries.add(summary.trim());
            }
        }

        if (subSummaries.isEmpty()) {
            // 全部 LLM 调用失败 → 降级
            return fallbackSummary(formatted);
        }

        // Step 4: 合并子摘要
        String merged = String.join("\n\n---\n\n", subSummaries);

        // Step 5: 若合并结果仍超长，递归摘要
        if (merged.length() > maxMergedChars) {
            log.info("合并摘要长度 {} > {}，递归摘要", merged.length(), maxMergedChars);
            return callLlmWithFallback(merged, formatted, modelName, tokenBudget);
        }

        return merged;
    }

    // ============ private helpers ============

    /**
     * 将消息列表格式化为带角色标签的文本。
     * 工具结果截断至 500 字符。
     */
    private String formatMessages(List<TurnMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (TurnMessage msg : messages) {
            String content = msg.content() != null ? msg.content() : "";

            switch (msg.role()) {
                case "user" -> sb.append("[USER] ").append(content);
                case "assistant" -> {
                    if (msg.isToolUse()) {
                        sb.append("[ASSISTANT-TOOL_USE] ").append(content);
                    } else {
                        sb.append("[ASSISTANT] ").append(content);
                    }
                }
                case "tool_result" -> {
                    String truncated = content.length() > TOOL_RESULT_TRUNCATE_CHARS
                            ? content.substring(0, TOOL_RESULT_TRUNCATE_CHARS) + "..."
                            : content;
                    sb.append("[TOOL_RESULT(").append(msg.toolName()).append(")] ").append(truncated);
                }
                default -> sb.append("[").append(msg.role().toUpperCase()).append("] ").append(content);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 按段落边界分块，每块最大约 MAX_CHUNK_CHARS 字符。
     * 段落边界 = 连续两个换行或单个换行后跟大写单词。
     */
    List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        String[] paragraphs = text.split("\n\n");
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (current.length() + paragraph.length() + 2 > MAX_CHUNK_CHARS
                    && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        // 若某块仍超过限制（单个段落极长），在换行处切割
        List<String> finalChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() > MAX_CHUNK_CHARS) {
                finalChunks.addAll(splitLongChunk(chunk));
            } else {
                finalChunks.add(chunk);
            }
        }

        return finalChunks;
    }

    /**
     * 对超过 MAX_CHUNK_CHARS 的单个块在换行处进一步切割。
     */
    private List<String> splitLongChunk(String chunk) {
        List<String> result = new ArrayList<>();
        String[] lines = chunk.split("\n");
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (current.length() + line.length() + 1 > MAX_CHUNK_CHARS
                    && current.length() > 0) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(line);
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private String summarizeChunk(String chunk, int index, int total,
                                  String modelName, TokenBudget tokenBudget) {
        log.debug("ChunkSummarizer: 摘要块 {}/{} ({} chars)", index, total, chunk.length());
        return callLlm(chunk, modelName, tokenBudget);
    }

    /**
     * O7: 经 ModelInvoker.callWithStream 调用 LLM（模型经 SummaryChatModelResolver 显式选取）；
     * 成功累计成本；失败返回 null。
     */
    private String callLlm(String chunk, String modelName, TokenBudget tokenBudget) {
        try {
            ChatModel chatModel = summaryChatModelResolver != null
                    ? summaryChatModelResolver.resolve() : null;
            if (chatModel == null) {
                log.warn("ChunkSummarizer: 无可用 ChatModel（SummaryChatModelResolver 未注入或解析失败），降级");
                return null;
            }
            ModelInvoker.ModelCallResult result = modelInvoker.callWithStream(
                    chatModel,
                    List.of(new UserMessage(chunk)),
                    SUMMARIZE_SYSTEM,
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
            log.warn("ChunkSummarizer: LLM 调用失败", e);
        }
        return null;
    }

    private String callLlmWithFallback(String chunk, String fallbackText,
                                       String modelName, TokenBudget tokenBudget) {
        String result = callLlm(chunk, modelName, tokenBudget);
        if (result != null && !result.isBlank()) {
            return result;
        }
        return fallbackSummary(fallbackText);
    }

    /**
     * 降级摘要 — 截断前 500 字符。
     */
    private String fallbackSummary(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String preview = text.length() > 500
                ? text.substring(0, 500) + "..."
                : text;
        return "[降级摘要] " + preview;
    }
}

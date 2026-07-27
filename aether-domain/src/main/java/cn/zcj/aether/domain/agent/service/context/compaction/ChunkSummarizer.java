package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
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
    private static final int MAX_MERGED_CHARS = 7_000;

    private static final String SUMMARIZE_PROMPT =
            "将以下对话片段压缩为简洁摘要。保留关键决策、重要结论、文件修改操作和未完成的任务。使用中文输出，不超过500字。\n\n对话片段：\n%s";

    @Resource
    private TokenEstimator tokenEstimator;

    @Resource
    @org.springframework.context.annotation.Lazy
    private ChatModel chatModel;

    /**
     * 对对话前缀生成分块摘要。
     *
     * @param prefix 需要摘要的消息前缀列表
     * @return 摘要文本；若全部 LLM 调用均失败，返回降级拼接文本
     */
    public String summarize(List<TurnMessage> prefix) {
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
            String summary = summarizeChunk(chunks.get(i), i + 1, chunks.size());
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
        if (merged.length() > MAX_MERGED_CHARS) {
            log.info("合并摘要长度 {} > {}，递归摘要", merged.length(), MAX_MERGED_CHARS);
            String recursivePrompt = String.format(SUMMARIZE_PROMPT, merged);
            return callLlmWithFallback(recursivePrompt, formatted);
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

    /**
     * 对单个文本块调用 LLM 生成摘要。
     *
     * @param chunk  文本块内容
     * @param index  当前块序号（1-based）
     * @param total  总块数
     * @return 摘要文本，失败时返回 null
     */
    private String summarizeChunk(String chunk, int index, int total) {
        String prompt = String.format(SUMMARIZE_PROMPT, chunk);
        log.debug("ChunkSummarizer: 摘要块 {}/{} ({} chars)", index, total, chunk.length());
        return callLlm(prompt);
    }

    /**
     * 调用 LLM 并返回文本，失败时返回 null。
     */
    private String callLlm(String prompt) {
        try {
            ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
            if (response != null && response.getResult() != null
                    && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        } catch (Exception e) {
            log.warn("ChunkSummarizer: LLM 调用失败", e);
        }
        return null;
    }

    /**
     * 调用 LLM 并附带回退：失败时用降级方法。
     */
    private String callLlmWithFallback(String prompt, String fallbackText) {
        String result = callLlm(prompt);
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

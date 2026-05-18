package cn.zcj.aether.domain.agent.service.context;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
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

/**
 * 上下文管理器 — 多层上下文维护
 *
 * 吸取项目B:
 *   query.ts:376-394  applyToolResultBudget
 *   query.ts:412-426  microcompact
 *   autoCompact.ts:33 getEffectiveContextWindowSize
 *   MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20000 (来自 p99.99 生产数据)
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

    private static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;
    private static final int MAX_TOOL_RESULT_CHARS = 50_000;

    /** 用于识别 Edit/Write/FileEdit/FileWrite 工具 */
    private static final Set<String> EDIT_TOOL_NAMES = Set.of(
            "Edit", "FileEdit", "Write", "FileWrite",
            "FileEditTool", "FileWriteTool", "BashTool"
    );

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
     * Pass 1: 建立 path → lastWriteIndex 映射（找到每个文件的最后一次写操作位置）
     * Pass 2: 对于每个 tool_use，如果不是该文件的最后一次写操作，
     *         则将该 tool_use 及其紧邻的 tool_result 一起移除
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
     * 自动压缩 — token超阈值时调用 LLM 生成摘要
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName) {

        int currentTokens = estimateTokens(messages);
        int contextWindow = tokenEstimator.getContextWindow(modelName);
        int effectiveWindow = contextWindow - MAX_OUTPUT_TOKENS_FOR_SUMMARY;
        int threshold = (int) (effectiveWindow * 0.9);

        if (currentTokens <= threshold || messages.size() < 20) {
            return AutoCompactResult.notNeeded();
        }

        log.info("触发自动压缩: currentTokens={} threshold={} messages={}",
                currentTokens, threshold, messages.size());

        int keepRecent = Math.min(15, messages.size());
        List<TurnMessage> recent = new ArrayList<>(
                messages.subList(Math.max(0, messages.size() - keepRecent), messages.size()));
        List<TurnMessage> toCompact = new ArrayList<>(
                messages.subList(0, Math.max(0, messages.size() - keepRecent)));

        // 调用 LLM 生成摘要，失败时降级为字符串拼接
        String summary = generateSummary(toCompact);
        if (summary == null || summary.isBlank()) {
            summary = fallbackSummary(toCompact);
        }

        List<TurnMessage> compacted = new ArrayList<>();
        compacted.add(TurnMessage.user("[对话历史摘要]\n" + summary));
        compacted.addAll(recent);

        int postTokens = estimateTokens(compacted);
        log.info("压缩完成: {} tokens → {} tokens", currentTokens, postTokens);

        return AutoCompactResult.compacted(summary, compacted, currentTokens, postTokens);
    }

    public boolean isAtBlockingLimit(List<TurnMessage> messages, String modelName) {
        return estimateTokens(messages) >= tokenEstimator.getContextWindow(modelName);
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

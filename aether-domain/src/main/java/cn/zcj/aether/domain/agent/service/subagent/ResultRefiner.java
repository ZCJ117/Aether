package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子Agent结果提炼器。
 * 纯规则驱动（零LLM调用），从子Agent对话历史中提取结构化摘要。
 */
@Slf4j
@Component
public class ResultRefiner {
    private static final int MAX_RESULT_LENGTH = 2000;
    private static final int MAX_CONCLUSION_LENGTH = 400;
    private static final int MAX_FILE_PATHS = 10;
    private static final int FILE_DISPLAY_LIMIT = 5;
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("([/\\\\]?[\\w.\\-]+[/\\\\][\\w.\\-/\\\\]+\\.[\\w]+)");

    /**
     * @param taskDescription 原始子任务描述
     * @param messages 子Agent收集的对话消息
     * @return 结构化摘要结果
     */
    public SubAgentResult refine(String taskDescription, List<TurnMessage> messages) {
        String conclusion = extractFinalAssistant(messages);
        Map<String, Integer> toolStats = countToolCalls(messages);
        Set<String> files = extractFilePaths(messages);
        String status = determineStatus(messages);

        StringBuilder sb = new StringBuilder();
        sb.append("[子任务] ").append(taskDescription).append("\n");
        sb.append("[状态] ").append(status).append("\n");
        sb.append("[结论] ").append(truncate(conclusion, MAX_CONCLUSION_LENGTH)).append("\n");
        if (!files.isEmpty()) {
            sb.append("[涉及文件] ").append(files.stream().limit(FILE_DISPLAY_LIMIT)
                    .reduce((a, b) -> a + ", " + b).orElse("")).append("\n");
        }
        if (!toolStats.isEmpty()) {
            sb.append("[工具调用] ");
            toolStats.forEach((k, v) -> sb.append(k).append("×").append(v).append(" "));
            sb.append("\n");
        }
        String result = sb.toString();
        if (result.length() > MAX_RESULT_LENGTH) {
            result = result.substring(0, MAX_RESULT_LENGTH) + "...";
        }
        return new SubAgentResult(status, result, toolStats);
    }

    /** 提取最后一条无 tool_use 的 assistant 消息作为结论 */
    private String extractFinalAssistant(List<TurnMessage> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            TurnMessage m = msgs.get(i);
            if ("assistant".equals(m.role()) && !m.hasToolCalls()
                    && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return "[无文本结论]";
    }

    /** 统计工具调用次数 */
    private Map<String, Integer> countToolCalls(List<TurnMessage> msgs) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (TurnMessage m : msgs) {
            if (m.isToolResult() && m.toolName() != null) {
                stats.merge(m.toolName(), 1, Integer::sum);
            }
        }
        return stats;
    }

    /** 从工具结果中提取文件路径 */
    private Set<String> extractFilePaths(List<TurnMessage> msgs) {
        Set<String> paths = new LinkedHashSet<>();
        for (TurnMessage m : msgs) {
            if (!m.isToolResult() || m.content() == null) {
                continue;
            }
            Matcher matcher = FILE_PATH_PATTERN.matcher(m.content());
            while (matcher.find() && paths.size() < MAX_FILE_PATHS) {
                paths.add(matcher.group(1).replace('\\', '/'));
            }
        }
        return paths;
    }

    /** 判断执行状态 */
    private String determineStatus(List<TurnMessage> msgs) {
        for (TurnMessage m : msgs) {
            if (m.isToolResult() && m.content() != null
                    && m.content().contains("Tool timeout")) {
                return "超时";
            }
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            TurnMessage m = msgs.get(i);
            if ("assistant".equals(m.role()) && !m.hasToolCalls()) {
                return "成功";
            }
        }
        return "未完成";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /**
     * 子Agent结果记录。
     *
     * @param status   执行状态（成功/超时/未完成/失败）
     * @param summary  结构化摘要文本
     * @param toolStats 工具调用统计（工具名 -> 调用次数）
     */
    public record SubAgentResult(String status, String summary, Map<String, Integer> toolStats) {}
}

package cn.zcj.aether.domain.agent.service.curation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class ResultSummarizer {
    private static final Pattern ERROR_PATTERN = Pattern.compile("(?i)(error|exception|fail|warn|fatal)");
    private static final int MAX_CODE_LINES = 30;
    private static final int MAX_DOC_CHARS = 500;
    private static final int MAX_STRUCTURED_LINES = 5;
    private static final int MAX_UNSTRUCTURED_CHARS = 300;

    public String summarize(String rawContent, String toolName, int budgetTokens) {
        if (rawContent == null || rawContent.isEmpty()) {
            return "[空结果]";
        }
        ContentType type = classify(toolName);
        try {
            return switch (type) {
                case CODE -> summarizeCode(rawContent);
                case LOG -> summarizeLog(rawContent);
                case DOCUMENTATION -> summarizeDoc(rawContent);
                case STRUCTURED -> summarizeStructured(rawContent);
                case UNSTRUCTURED -> summarizeUnstructured(rawContent);
            };
        } catch (Exception e) {
            log.warn("ResultSummarizer 摘要失败，降级: toolName={}", toolName, e);
            return truncate(rawContent, 200);
        }
    }

    private ContentType classify(String toolName) {
        if (toolName == null) {
            return ContentType.UNSTRUCTURED;
        }
        return switch (toolName.toLowerCase()) {
            case "grep", "glob", "code_search", "file_read" -> ContentType.CODE;
            case "bash" -> ContentType.LOG;
            case "read", "webfetch", "doc_read" -> ContentType.DOCUMENTATION;
            case "session_search" -> ContentType.STRUCTURED;
            default -> ContentType.UNSTRUCTURED;
        };
    }

    private String summarizeCode(String content) {
        String[] lines = content.split("\n");
        if (lines.length <= MAX_CODE_LINES) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(10, lines.length); i++) {
            sb.append(lines[i]).append("\n");
        }
        int omitted = lines.length - 20;
        if (omitted > 0) {
            sb.append("... [省略 ").append(omitted).append(" 行] ...\n");
        }
        for (int i = Math.max(10, lines.length - 10); i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private String summarizeLog(String content) {
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (ERROR_PATTERN.matcher(line).find()) {
                sb.append(line).append("\n");
                count++;
                if (count >= 20) {
                    break;
                }
            }
        }
        if (count == 0) {
            for (int i = 0; i < Math.min(10, lines.length); i++) {
                sb.append(lines[i]).append("\n");
            }
        }
        if (count >= 20) {
            sb.append("... [更多错误行已省略]");
        }
        return sb.toString().isEmpty() ? "[无错误输出]" : sb.toString();
    }

    private String summarizeDoc(String content) {
        if (content.length() <= MAX_DOC_CHARS) {
            return content;
        }
        return content.substring(0, MAX_DOC_CHARS) + "\n... [文档已截断，原始长度 " + content.length() + " 字符]";
    }

    private String summarizeStructured(String content) {
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_STRUCTURED_LINES, lines.length); i++) {
            sb.append(lines[i]).append("\n");
        }
        if (lines.length > MAX_STRUCTURED_LINES) {
            sb.append("... [省略 ").append(lines.length - MAX_STRUCTURED_LINES).append(" 条]");
        }
        return sb.toString();
    }

    private String summarizeUnstructured(String content) {
        if (content.length() <= MAX_UNSTRUCTURED_CHARS) {
            return content;
        }
        return content.substring(0, 200) + "\n... [省略 " + (content.length() - 300) + " 字符] ...\n"
                + content.substring(Math.max(0, content.length() - 100));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    enum ContentType {CODE, LOG, DOCUMENTATION, STRUCTURED, UNSTRUCTURED}
}

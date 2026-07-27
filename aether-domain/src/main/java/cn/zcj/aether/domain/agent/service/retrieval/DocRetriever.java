package cn.zcj.aether.domain.agent.service.retrieval;

import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文档检索工具。
 * <p>
 * 在 Markdown 文件中按标题匹配查找小节内容。返回匹配的标题及其所属小节的全部文本。
 * 每次最多返回 2000 字符。
 * </p>
 */
@Slf4j
public final class DocRetriever implements Tool {

    private static final String TOOL_NAME = "doc_read";
    private static final String DESCRIPTION = "在 Markdown 文档中按标题匹配检索小节，返回匹配标题及其文本内容。";
    private static final int MAX_OUTPUT_CHARS = 2000;

    private final Path projectRoot;

    public DocRetriever() {
        this.projectRoot = Paths.get(System.getProperty("user.dir"));
    }

    DocRetriever(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> docPathProp = new LinkedHashMap<>();
        docPathProp.put("type", "string");
        docPathProp.put("description", "相对于项目根目录的 Markdown 文档路径（必填）");
        properties.put("doc_path", docPathProp);

        Map<String, Object> sectionProp = new LinkedHashMap<>();
        sectionProp.put("type", "string");
        sectionProp.put("description", "要匹配的标题文本或正则表达式（必填）。匹配时忽略大小写，部分匹配即可。");
        properties.put("section", sectionProp);

        schema.put("properties", properties);

        List<String> required = new ArrayList<>();
        required.add("doc_path");
        required.add("section");
        schema.put("required", required);

        return schema;
    }

    @Override
    public ToolResult call(Map<String, Object> input, ToolContext context) {
        String docPath = (String) input.get("doc_path");
        if (docPath == null || docPath.isEmpty()) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "缺少必填参数: doc_path");
        }

        String section = (String) input.get("section");
        if (section == null || section.isEmpty()) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "缺少必填参数: section");
        }

        Path resolved = projectRoot.resolve(docPath).normalize();
        if (!resolved.startsWith(projectRoot)) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "路径越界访问被拒绝: " + docPath);
        }

        if (!Files.isRegularFile(resolved)) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "文档不存在: " + docPath);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(resolved);
        } catch (IOException e) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "读取文档失败: " + e.getMessage());
        }

        // 查找匹配的标题行
        Pattern headingPattern;
        try {
            // 支持传入普通字符串或正则表达式
            headingPattern = Pattern.compile(section, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "无效的正则表达式: " + e.getMessage());
        }

        Pattern headingLine = Pattern.compile("^(#{1,6})\\s+(.+)$");
        List<HeadingSpan> spans = new ArrayList<>();

        // 第一遍：找出所有标题及其行号
        for (int i = 0; i < lines.size(); i++) {
            var matcher = headingLine.matcher(lines.get(i));
            if (matcher.matches()) {
                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();
                spans.add(new HeadingSpan(i, level, title));
            }
        }

        // 第二遍：匹配标题并提取小节内容
        StringBuilder sb = new StringBuilder(1024);
        int matchedCount = 0;

        for (int si = 0; si < spans.size(); si++) {
            HeadingSpan span = spans.get(si);
            if (!headingPattern.matcher(span.title).find()) {
                continue;
            }

            matchedCount++;
            if (sb.length() >= MAX_OUTPUT_CHARS) {
                sb.append("\n\n[已达到最大输出字符数 ").append(MAX_OUTPUT_CHARS).append("，截断后续内容]");
                break;
            }

            String prefix = "#".repeat(span.level);
            sb.append(prefix).append(' ').append(span.title).append('\n');

            // 收集该标题至下一个同等级或更高级标题之间的内容
            int startLine = span.line;
            int endLine = lines.size(); // 默认到文件末尾

            for (int ti = si + 1; ti < spans.size(); ti++) {
                HeadingSpan next = spans.get(ti);
                if (next.level <= span.level) {
                    endLine = next.line;
                    break;
                }
            }

            StringBuilder sectionContent = new StringBuilder();
            for (int li = startLine + 1; li < endLine; li++) {
                String line = lines.get(li);
                sectionContent.append(line).append('\n');
            }

            String content = sectionContent.toString().stripTrailing();
            if (!content.isEmpty()) {
                sb.append(content).append('\n');
            }

            sb.append('\n');
        }

        if (matchedCount == 0) {
            return ToolResult.success(context.toolCallId(), TOOL_NAME,
                    "未找到匹配标题 '" + section + "' 的小节");
        }

        String result = sb.toString().trim();
        if (result.length() > MAX_OUTPUT_CHARS) {
            result = result.substring(0, MAX_OUTPUT_CHARS) + "\n\n[内容已截断至 " + MAX_OUTPUT_CHARS + " 字符]";
        }

        return ToolResult.success(context.toolCallId(), TOOL_NAME, result);
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 标题跨度，记录标题行的行号、级别和标题文本。
     */
    private record HeadingSpan(int line, int level, String title) {}
}

package cn.zcj.aether.domain.agent.service.retrieval;

import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 代码搜索工具。
 * <p>
 * 使用正则表达式对项目文件进行模式匹配，返回匹配的文件路径及命中次数。
 * 每次最多扫描 500 个文件，最多返回 20 条结果。
 * </p>
 */
@Slf4j
public final class CodeExplorer implements Tool {

    private static final String TOOL_NAME = "code_search";
    private static final String DESCRIPTION = "使用正则表达式搜索项目源代码文件，返回匹配文件路径及命中次数。";
    private static final int MAX_FILES_SCANNED = 500;
    private static final int MAX_RESULTS = 20;

    private static final List<String> EXCLUDED_DIRS = List.of("target", "node_modules", ".git", ".aether");

    private final Path projectRoot;

    public CodeExplorer() {
        this.projectRoot = Paths.get(System.getProperty("user.dir"));
    }

    CodeExplorer(Path projectRoot) {
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

        Map<String, Object> patternProp = new LinkedHashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "正则表达式匹配模式（必填）");
        properties.put("pattern", patternProp);

        Map<String, Object> globProp = new LinkedHashMap<>();
        globProp.put("type", "string");
        globProp.put("description", "文件过滤 glob 模式，例如 *.java。默认为 *.java");
        properties.put("glob", globProp);

        schema.put("properties", properties);

        List<String> required = new ArrayList<>();
        required.add("pattern");
        schema.put("required", required);

        return schema;
    }

    @Override
    public ToolResult call(Map<String, Object> input, ToolContext context) {
        String patternStr = (String) input.get("pattern");
        if (patternStr == null || patternStr.isEmpty()) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "缺少必填参数: pattern");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "无效的正则表达式: " + e.getMessage());
        }

        String globStr = (String) input.getOrDefault("glob", "*.java");
        PathMatcher globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + globStr);

        List<MatchEntry> results = new ArrayList<>();
        int filesScanned = 0;

        Path srcDir = projectRoot.resolve("src");
        if (!Files.isDirectory(srcDir)) {
            srcDir = projectRoot;
        }

        try (Stream<Path> stream = Files.walk(srcDir, Integer.MAX_VALUE)) {
            var pathList = stream.filter(Files::isRegularFile)
                    .filter(this::shouldInclude)
                    .filter(globMatcher::matches)
                    .limit(MAX_FILES_SCANNED)
                    .toList();

            for (Path file : pathList) {
                filesScanned++;
                int matchCount;
                try {
                    matchCount = countMatches(file, pattern);
                } catch (IOException e) {
                    log.debug("读取文件失败 [{}]: {}", file, e.getMessage());
                    continue;
                }
                if (matchCount > 0) {
                    String relPath = projectRoot.relativize(file).toString().replace('\\', '/');
                    results.add(new MatchEntry(relPath, matchCount));
                    if (results.size() >= MAX_RESULTS) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("代码搜索扫描失败: {}", e.getMessage());
            return ToolResult.error(context.toolCallId(), TOOL_NAME, "文件扫描失败: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success(context.toolCallId(), TOOL_NAME,
                    "未找到匹配 '" + patternStr + "' 的文件（已扫描 " + filesScanned + " 个文件）");
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("找到 ").append(results.size()).append(" 个匹配文件");
        if (filesScanned >= MAX_FILES_SCANNED) {
            sb.append("（已达扫描上限 ").append(MAX_FILES_SCANNED).append("）");
        }
        sb.append(":\n");
        for (MatchEntry entry : results) {
            sb.append("  ").append(entry.path).append(" — ").append(entry.matchCount).append(" 处匹配\n");
        }

        return ToolResult.success(context.toolCallId(), TOOL_NAME, sb.toString().trim());
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    private int countMatches(Path file, Pattern pattern) throws IOException {
        int count = 0;
        try (Stream<String> lines = Files.lines(file)) {
            count = (int) lines.filter(line -> pattern.matcher(line).find()).count();
        }
        return count;
    }

    private boolean shouldInclude(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (EXCLUDED_DIRS.contains(path.getName(i).toString())) {
                return false;
            }
        }
        return true;
    }

    private record MatchEntry(String path, int matchCount) {}
}

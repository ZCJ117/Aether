package cn.zcj.aether.domain.agent.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话历史搜索工具。
 * 读取 .aether/sessions/{sessionId}.jsonl，按关键词搜索，返回匹配片段。
 */
@Slf4j
public class SessionSearchTool implements Tool {

    private static final String SESSIONS_DIR = ".aether/sessions";
    private static final int MAX_RESULTS = 10;
    private static final int PREVIEW_LENGTH = 200;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "session_search";
    }

    @Override
    public String description() {
        return "搜索历史会话记录。根据关键词查找之前的对话片段，返回匹配的回合和角色摘要。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索关键词，多个词用空格分隔");
        props.put("query", queryProp);
        schema.put("properties", props);
        List<String> required = List.of("query");
        schema.put("required", required);
        return schema;
    }

    @Override
    public ToolResult call(Map<String, Object> input, ToolContext context) {
        String query = input != null ? String.valueOf(input.getOrDefault("query", "")) : "";
        if (query.isBlank()) {
            return ToolResult.error(null, name(), "query 参数不能为空");
        }

        String sessionId = context != null ? context.sessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResult.error(null, name(), "缺少 sessionId");
        }

        try {
            String result = searchSessions(sessionId, query);
            return ToolResult.success(null, name(), result);
        } catch (Exception e) {
            log.warn("SessionSearchTool 执行失败: sessionId={} query={}", sessionId, query, e);
            return ToolResult.error(null, name(), "搜索失败: " + e.getMessage());
        }
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
     * 搜索指定会话的 JSONL 文件。
     */
    private String searchSessions(String sessionId, String query) throws IOException {
        String[] keywords = query.toLowerCase().split("\\s+");
        String safeId = sanitizeFileName(sessionId);
        Path jsonlFile = Paths.get(System.getProperty("user.dir"), SESSIONS_DIR, safeId + ".jsonl");

        if (!Files.exists(jsonlFile)) {
            return "未找到会话 " + sessionId + " 的历史记录文件。";
        }

        // 读取所有行并评分
        List<String> lines = Files.readAllLines(jsonlFile);
        List<ScoredLine> scoredLines = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            int score = 0;
            String lineLower = line.toLowerCase();
            for (String kw : keywords) {
                int idx = lineLower.indexOf(kw);
                while (idx >= 0) {
                    score++;
                    idx = lineLower.indexOf(kw, idx + kw.length());
                }
            }

            if (score > 0) {
                scoredLines.add(new ScoredLine(score, i, line));
            }
        }

        if (scoredLines.isEmpty()) {
            return "在会话 " + sessionId + " 中未找到与 \"" + query + "\" 匹配的记录。";
        }

        // 按得分降序排列，取前 MAX_RESULTS
        scoredLines.sort(Comparator.comparingInt(ScoredLine::score).reversed());
        int limit = Math.min(scoredLines.size(), MAX_RESULTS);

        StringBuilder sb = new StringBuilder();
        sb.append("在会话 ").append(sessionId).append(" 中找到 ")
                .append(scoredLines.size()).append(" 条匹配，展示前 ").append(limit).append(" 条：\n\n");

        for (int i = 0; i < limit; i++) {
            ScoredLine sl = scoredLines.get(i);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> record = objectMapper.readValue(sl.line, Map.class);
                int turn = record.containsKey("turn") ? ((Number) record.get("turn")).intValue() : 0;
                String role = String.valueOf(record.getOrDefault("role", "unknown"));
                String text = record.containsKey("text") ? String.valueOf(record.get("text")) : "";

                String preview = text.length() > PREVIEW_LENGTH
                        ? text.substring(0, PREVIEW_LENGTH) + "..."
                        : text;

                sb.append("--- 匹配 ").append(i + 1).append(" (得分:").append(sl.score)
                        .append(" 回合:").append(turn).append(" 角色:").append(role).append(") ---\n");
                sb.append(preview).append("\n\n");
            } catch (Exception e) {
                sb.append("--- 匹配 ").append(i + 1).append(" (得分:").append(sl.score).append(") ---\n");
                String preview = sl.line.length() > PREVIEW_LENGTH
                        ? sl.line.substring(0, PREVIEW_LENGTH) + "..."
                        : sl.line;
                sb.append(preview).append("\n\n");
            }
        }

        return sb.toString();
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private record ScoredLine(int score, int lineIndex, String line) {}
}

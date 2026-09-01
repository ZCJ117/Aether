package cn.zcj.aether.domain.agent.service.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionSearchTool 单测 — 通过临时 user.dir 驱动 JSONL 文件搜索全路径（覆盖率盲区补测 P0 2.1）。
 */
class SessionSearchToolTest {

    private final SessionSearchTool tool = new SessionSearchTool();

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        Files.createDirectories(tempDir.resolve(".aether/sessions"));
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private ToolContext ctx(String sessionId) {
        return new ToolContext("u1", sessionId, "call-1");
    }

    @Test
    void metadataDescribesReadOnlyConcurrentSearch() {
        assertEquals("session_search", tool.name());
        assertFalse(tool.description().isEmpty());
        assertEquals("object", tool.inputSchema().get("type"));
        assertEquals(List.of("query"), tool.inputSchema().get("required"));
        assertTrue(tool.isConcurrencySafe());
        assertTrue(tool.isReadOnly());
    }

    @Test
    void blankQueryFailsValidation() {
        ToolResult r = tool.call(Map.of("query", "  "), ctx("s1"));
        assertTrue(r.isError());
        assertTrue(r.getContent().contains("query 参数不能为空"));
    }

    @Test
    void missingSessionIdFailsValidation() {
        ToolResult r1 = tool.call(Map.of("query", "kw"), null);
        assertTrue(r1.isError());
        assertTrue(r1.getContent().contains("缺少 sessionId"));

        ToolResult r2 = tool.call(Map.of("query", "kw"), new ToolContext("u1", " ", "call-1"));
        assertTrue(r2.isError());
    }

    @Test
    void missingSessionFileReportsNotFound() {
        ToolResult r = tool.call(Map.of("query", "kw"), ctx("no-such-session"));
        assertFalse(r.isError());
        assertTrue(r.getContent().contains("未找到会话 no-such-session"));
    }

    @Test
    void keywordMatchReturnsScoredTurnAndRole() throws Exception {
        Path jsonl = tempDir.resolve(".aether/sessions/s1.jsonl");
        Files.write(jsonl, List.of(
                "{\"turn\":1,\"role\":\"user\",\"text\":\"how to deploy k8s cluster\"}",
                "{\"turn\":2,\"role\":\"assistant\",\"text\":\"unrelated content\"}",
                "{\"turn\":3,\"role\":\"user\",\"text\":\"k8s scaling k8s nodes\"}"));

        ToolResult r = tool.call(Map.of("query", "k8s"), ctx("s1"));

        assertFalse(r.isError());
        String out = r.getContent();
        assertTrue(out.contains("找到 2 条匹配"));
        assertTrue(out.contains("得分:1"));
        assertTrue(out.contains("得分:2"), "出现两次关键词的行得分更高");
        assertTrue(out.contains("回合:3") && out.contains("角色:user"));
    }

    @Test
    void noMatchReportsEmptyResult() throws Exception {
        Path jsonl = tempDir.resolve(".aether/sessions/s2.jsonl");
        Files.writeString(jsonl, "{\"turn\":1,\"role\":\"user\",\"text\":\"hello\"}");

        ToolResult r = tool.call(Map.of("query", "quantum"), ctx("s2"));

        assertFalse(r.isError());
        assertTrue(r.getContent().contains("未找到与 \"quantum\" 匹配的记录"));
    }

    @Test
    void malformedJsonLineFallsBackToRawPreview() throws Exception {
        Path jsonl = tempDir.resolve(".aether/sessions/s3.jsonl");
        Files.writeString(jsonl, "not-json but contains needle");

        ToolResult r = tool.call(Map.of("query", "needle"), ctx("s3"));

        assertFalse(r.isError());
        assertTrue(r.getContent().contains("not-json but contains needle"));
        assertTrue(r.getContent().contains("得分:1)"), "无法解析 JSON 时仅展示得分");
    }

    @Test
    void longMatchedTextTruncatedToPreview() throws Exception {
        Path jsonl = tempDir.resolve(".aether/sessions/s4.jsonl");
        Files.writeString(jsonl,
                "{\"turn\":9,\"role\":\"assistant\",\"text\":\"" + "z".repeat(300) + "\"}");

        ToolResult r = tool.call(Map.of("query", "zzz"), ctx("s4"));

        assertTrue(r.getContent().contains("..." + "\n"));
        assertTrue(r.getContent().contains("z".repeat(200)), "预览保留前 200 字符");
    }
}

package cn.zcj.aether.domain.agent.service.retrieval;

import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeExplorer 单测 — 正则代码搜索（覆盖率盲区补测 P0 2.1）。
 */
class CodeExplorerTest {

    @TempDir
    Path root;

    private final ToolContext ctx = new ToolContext("u1", "s1", "call-1");

    @Test
    void metadataDescribesReadOnlySearch() {
        CodeExplorer tool = new CodeExplorer(root);
        assertEquals("code_search", tool.name());
        assertTrue(tool.isConcurrencySafe());
        assertTrue(tool.isReadOnly());
        assertEquals(java.util.List.of("pattern"), tool.inputSchema().get("required"));
    }

    @Test
    void missingPatternFailsValidation() {
        ToolResult r = new CodeExplorer(root).call(Map.of(), ctx);
        assertTrue(r.isError());
        assertTrue(r.getContent().contains("缺少必填参数: pattern"));
    }

    @Test
    void invalidRegexFailsValidation() {
        ToolResult r = new CodeExplorer(root).call(Map.of("pattern", "[broken"), ctx);
        assertTrue(r.isError());
        assertTrue(r.getContent().contains("无效的正则表达式"));
    }

    @Test
    void matchesCountedPerFileWithDefaultGlob() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/A.java"), "int x = 1;\nint y = x + 1;\n");
        Files.writeString(root.resolve("src/main/java/B.java"), "float f = 1.0f;\n");

        ToolResult r = new CodeExplorer(root).call(Map.of("pattern", "\\bint\\b"), ctx);

        assertFalse(r.isError());
        String out = r.getContent();
        assertTrue(out.contains("找到 1 个匹配文件"));
        assertTrue(out.contains("src/main/java/A.java — 2 处匹配"));
        assertTrue(!out.contains("B.java"));
    }

    @Test
    void globFilterRestrictsScanScope() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "needle\n");
        Files.writeString(root.resolve("src/A.md"), "needle\n");

        ToolResult r = new CodeExplorer(root).call(Map.of("pattern", "needle"), ctx);

        assertTrue(r.getContent().contains("src/A.java"));
        assertTrue(!r.getContent().contains("A.md"));
    }

    @Test
    void excludedDirsSkippedAndNoMatchMessageIncludesScanCount() throws Exception {
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/T.java"), "needle\n");

        ToolResult r = new CodeExplorer(root).call(Map.of("pattern", "needle"), ctx);

        assertFalse(r.isError());
        assertTrue(r.getContent().contains("未找到匹配 'needle' 的文件"));
        assertTrue(r.getContent().contains("已扫描 0 个文件"));
    }

    @Test
    void noSrcDirFallsBackToProjectRoot() throws Exception {
        Files.writeString(root.resolve("Root.java"), "uniqueToken\n");

        ToolResult r = new CodeExplorer(root).call(Map.of("pattern", "uniqueToken"), ctx);

        assertTrue(r.getContent().contains("Root.java — 1 处匹配"));
    }
}

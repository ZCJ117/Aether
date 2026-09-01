package cn.zcj.aether.domain.agent.service.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdentifierRegistry 单测 — 源文件/文档索引扫描（覆盖率盲区补测 P0 2.1）。
 */
class IdentifierRegistryTest {

    @TempDir
    Path root;

    @Test
    void emptyProjectYieldsEmptyContext() {
        String ctx = new IdentifierRegistry(root).buildIdentifierContext();

        assertTrue(ctx.startsWith("<project-context>"));
        assertTrue(ctx.endsWith("</project-context>"));
        assertTrue(ctx.contains("项目源文件 (0 个)"));
    }

    @Test
    void scansJavaFilesUnderSrcPreferringSrcRoot() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createFile(root.resolve("src/main/java/App.java"));
        Files.createDirectories(root.resolve("docs"));
        Files.createFile(root.resolve("docs/guide.md"));

        List<String> files = new IdentifierRegistry(root).scanSourceFiles();

        assertEquals(1, files.size());
        assertEquals("src/main/java/App.java", files.get(0).replace('\\', '/'));
    }

    @Test
    void fallsBackToRootWalkAndExcludesBuildDirs() throws Exception {
        // 无 src 目录 → 从项目根扫描；target/node_modules/.git/.aether 内的文件被过滤
        Files.createDirectories(root.resolve("lib"));
        Files.createFile(root.resolve("lib/Lib.java"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.createFile(root.resolve("target/classes/Ignored.java"));

        List<String> files = new IdentifierRegistry(root).scanSourceFiles();

        assertEquals(1, files.size());
        assertTrue(files.get(0).replace('\\', '/').endsWith("lib/Lib.java"));
    }

    @Test
    void capsFileListAtTwoHundredEntries() throws Exception {
        Files.createDirectories(root.resolve("src"));
        for (int i = 0; i < 205; i++) {
            Files.createFile(root.resolve("src/F" + i + ".java"));
        }
        List<String> files = new IdentifierRegistry(root).scanSourceFiles();
        assertEquals(200, files.size(), "最多返回 200 个路径");
    }

    @Test
    void docIndexExtractsFirstHeading() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/a.md"), "# 主标题\n\n正文\n");
        // 实现为提取首个以 # 开头的行（任意级别）
        Files.writeString(root.resolve("docs/b.md"), "无标题文档\n## 二级\n");
        Files.writeString(root.resolve("docs/c.md"), "完全无标题\n");

        Map<String, String> index = new IdentifierRegistry(root).scanDocHeadings();

        assertEquals("主标题", index.get("docs/a.md"));
        assertEquals("二级", index.get("docs/b.md"));
        assertEquals("", index.get("docs/c.md"), "无任何标题时值为空串");
    }

    @Test
    void contextIncludesDocIndexSection() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/arch.md"), "# 架构说明\n");

        String ctx = new IdentifierRegistry(root).buildIdentifierContext();

        assertTrue(ctx.contains("文档索引:"));
        assertTrue(ctx.contains("docs/arch.md — 架构说明"));
    }
}

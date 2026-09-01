package cn.zcj.aether.domain.agent.service.retrieval;

import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocRetriever 单测 — 按标题检索 Markdown 小节（覆盖率盲区补测 P0 2.1）。
 */
class DocRetrieverTest {

    @TempDir
    Path root;

    private final ToolContext ctx = new ToolContext("u1", "s1", "call-1");

    private ToolResult call(String docPath, String section) {
        return new DocRetriever(root).call(Map.of("doc_path", docPath, "section", section), ctx);
    }

    @Test
    void metadataDescribesReadOnlySearch() {
        DocRetriever tool = new DocRetriever(root);
        assertEquals("doc_read", tool.name());
        assertTrue(tool.isConcurrencySafe());
        assertTrue(tool.isReadOnly());
        assertEquals(List.of("doc_path", "section"), tool.inputSchema().get("required"));
    }

    @Test
    void missingRequiredParamsFailValidation() {
        ToolResult r1 = new DocRetriever(root).call(Map.of("section", "x"), ctx);
        assertTrue(r1.isError());
        assertTrue(r1.getContent().contains("doc_path"));

        ToolResult r2 = new DocRetriever(root).call(Map.of("doc_path", "a.md"), ctx);
        assertTrue(r2.isError());
        assertTrue(r2.getContent().contains("section"));
    }

    @Test
    void pathEscapeRejected() {
        ToolResult r = call("../outside.md", "标题");
        assertTrue(r.isError());
        assertTrue(r.getContent().contains("路径越界访问被拒绝"));
    }

    @Test
    void missingDocumentRejected() {
        ToolResult r = call("docs/nope.md", "标题");
        assertTrue(r.isError());
        assertTrue(r.getContent().contains("文档不存在"));
    }

    @Test
    void invalidRegexRejected() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/a.md"), "# 标题\n");
        ToolResult r = call("docs/a.md", "[invalid");
        assertTrue(r.isError());
        assertTrue(r.getContent().contains("无效的正则表达式"));
    }

    @Test
    void sectionMatchReturnsHeadingAndSubsectionContent() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/api.md"), """
                # 总览
                顶部内容
                ## 部署
                部署步骤一
                部署步骤二
                ### 回滚
                回滚命令
                ## 监控
                监控面板
                """);

        ToolResult r = call("docs/api.md", "部署");

        assertTrue(!r.isError());
        String out = r.getContent();
        assertTrue(out.contains("## 部署"));
        assertTrue(out.contains("部署步骤一") && out.contains("部署步骤二"));
        // ### 子小节内容归属父标题，但 ## 监控（同级）不归属
        assertTrue(out.contains("回滚命令"));
        assertTrue(!out.contains("监控面板"));
    }

    @Test
    void caseInsensitivePartialMatchAndNoMatchMessage() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/a.md"), "# Deployment Guide\nstep1\n");

        assertTrue(call("docs/a.md", "deployment").getContent().contains("step1"));
        assertTrue(call("docs/a.md", "不存在标题").getContent()
                .contains("未找到匹配标题 '不存在标题' 的小节"));
    }

    @Test
    void oversizedSectionContentTruncated() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/big.md"), "# 大节\n" + "行内容xxxxx\n".repeat(500));

        ToolResult r = call("docs/big.md", "大节");
        String out = r.getContent();
        assertTrue(out.length() <= 2000 + 40, "输出被截断至上限附近");
        assertTrue(out.contains("[内容已截至") || out.contains("[内容已截断至"));
    }
}

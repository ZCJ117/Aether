package cn.zcj.aether.domain.agent.service.curation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResultSummarizer 单测 — 覆盖 5 类内容策略 + 空结果兜底（覆盖率盲区补测 P0 2.1）。
 */
class ResultSummarizerTest {

    private final ResultSummarizer summarizer = new ResultSummarizer();

    @Test
    void nullOrEmptyReturnsPlaceholder() {
        assertEquals("[空结果]", summarizer.summarize(null, "grep", 100));
        assertEquals("[空结果]", summarizer.summarize("", "grep", 100));
    }

    @Test
    void shortCodeResultPassesThrough() {
        String code = "public class A {\n    int x = 1;\n}";
        assertEquals(code, summarizer.summarize(code, "grep", 1000));
    }

    @Test
    void longCodeResultKeepsHeadAndTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 35; i++) {
            sb.append("line ").append(i).append('\n');
        }
        String out = summarizer.summarize(sb.toString(), "file_read", 1000);

        assertTrue(out.startsWith("line 0\n"), "应保留前 10 行的头部");
        assertTrue(out.endsWith("line 34\n"), "应保留后 10 行的尾部");
        assertTrue(out.contains("[省略 15 行]"), "中间行以省略标记替代");
    }

    @Test
    void logResultKeepsErrorLinesOnly() {
        String log = "INFO boot ok\nERROR conn refused\nWARN slow query\nINFO done";
        String out = summarizer.summarize(log, "bash", 1000);

        assertTrue(out.contains("ERROR conn refused"));
        assertTrue(out.contains("WARN slow query"));
        assertTrue(out.contains("INFO boot ok") == false, "无错误级别的行应被过滤");
    }

    @Test
    void logWithoutErrorsKeepsFirstTenLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("plain line ").append(i).append('\n');
        }
        String out = summarizer.summarize(sb.toString(), "bash", 1000);

        assertEquals(10, out.split("\n").length, "无错误行时保留前 10 行");
    }

    @Test
    void logWithTwentyPlusErrorsAppendsOmissionMarker() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            sb.append("ERROR ").append(i).append('\n');
        }
        String out = summarizer.summarize(sb.toString(), "bash", 1000);

        assertTrue(out.contains("[更多错误行已省略]"), "错误行超 20 条应提示省略");
        // 20 条错误行（各含 \n，split 去尾空串）+ 无换行的省略标记 = 21 段，即 20 行 + 标记
        assertEquals(20, out.split("\n").length - 1, "仅保留前 20 条错误行");
    }

    @Test
    void shortDocPassesThroughLongDocTruncated() {
        String shortDoc = "a short doc";
        assertEquals(shortDoc, summarizer.summarize(shortDoc, "read", 1000));

        String longDoc = "x".repeat(800);
        String out = summarizer.summarize(longDoc, "webfetch", 1000);
        assertTrue(out.startsWith("x".repeat(500)));
        assertTrue(out.contains("[文档已截断，原始长度 800 字符]"));
    }

    @Test
    void structuredResultKeepsFirstFiveRecords() {
        String structured = "r1\nr2\nr3\nr4\nr5\nr6\nr7";
        String out = summarizer.summarize(structured, "session_search", 1000);

        assertTrue(out.startsWith("r1\nr2\nr3\nr4\nr5\n"));
        assertTrue(out.contains("[省略 2 条]"));
    }

    @Test
    void unstructuredResultKeepsHeadAndTail() {
        String longText = "a".repeat(500) + "b".repeat(500);
        String out = summarizer.summarize(longText, "custom_tool", 1000);

        assertTrue(out.startsWith("a".repeat(200)));
        assertTrue(out.endsWith("b".repeat(100)));
        assertTrue(out.contains("[省略 700 字符]"));
    }

    @Test
    void unknownToolWithoutNameFallsBackToUnstructured() {
        String longText = "c".repeat(450);
        String out = summarizer.summarize(longText, null, 1000);
        assertTrue(out.startsWith("c".repeat(200)), "toolName=null 走 UNSTRUCTURED 截断");
    }
}

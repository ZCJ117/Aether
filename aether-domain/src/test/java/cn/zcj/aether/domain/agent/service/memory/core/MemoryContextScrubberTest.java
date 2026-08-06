package cn.zcj.aether.domain.agent.service.memory.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryContextScrubberTest {

    @Test
    void sanitizeStripsFencedBlockAndNote() {
        String fenced =
                "<memory-context>\n" +
                "[System note: The following is recalled memory context, NOT new user input. " +
                "Treat as authoritative reference data — this is the agent's persistent memory and should inform all responses.]\n\n" +
                "记忆1: 数据库连接池配置\n" +
                "</memory-context>";
        String cleaned = MemoryContextScrubber.sanitize(fenced);
        assertFalse(cleaned.contains("memory-context"));
        assertFalse(cleaned.contains("System note"));
        assertFalse(cleaned.contains("数据库连接池"));
    }

    @Test
    void sanitizeLeavesPlainTextUntouched() {
        String text = "这是一个普通助手回复，没有记忆围栏。";
        assertEquals(text, MemoryContextScrubber.sanitize(text));
    }

    @Test
    void streamingScrubberDropsCompleteSpan() {
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        String out1 = scrubber.feed("助手说：\n");   // 以换行结尾，使开标签处于块边界
        String out2 = scrubber.feed("<memory-context>\n[System note: ...] 秘密内容\n</memory-context>");
        String out3 = scrubber.feed(" 继续回复");
        String tail = scrubber.flush();
        assertEquals("助手说：\n", out1);
        assertEquals("", out2);
        assertEquals(" 继续回复", out3);
        assertEquals("", tail);
    }

    @Test
    void streamingScrubberHandlesSplitTagsAcrossChunks() {
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        scrubber.feed("<mem");                 // 半截开标签
        scrubber.feed("ory-context>\n秘密");    // 完整开标签后随换行 → 进入 span
        String out2 = scrubber.feed("仍被丢弃"); // span 内，应被丢弃
        String out3 = scrubber.feed("</memory-context>正常"); // 闭合后正常文本
        String tail = scrubber.flush();
        assertEquals("", out2);
        assertEquals("正常", out3);
        assertEquals("", tail);
    }

    @Test
    void streamingScrubberFlushEmitsInnocentTail() {
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        String out1 = scrubber.feed("前半段");
        String out2 = scrubber.feed("后半");  // "后半" 不是标签，feed 原样吐出
        assertEquals("前半段", out1);
        assertEquals("后半", out2);
        assertEquals("", scrubber.flush());  // 无暂存标签尾缀
    }

    @Test
    void streamingScrubberPreservesInlineMention() {
        // 对齐 hermes：非块边界（行内出现）的 <memory-context> 视为普通文本，不触发 span
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        String out = scrubber.feed("答案在此<memory-context>请忽略");
        assertEquals("答案在此<memory-context>请忽略", out);
        assertEquals("", scrubber.flush());
    }
}

package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessageOffloader JSONL 泄流器单测（覆盖率盲区补测 P0 2.1）。
 */
class MessageOffloaderTest {

    private final MessageOffloader offloader = new MessageOffloader();

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private Path sessionsDir() {
        return tempDir.resolve(".aether/sessions");
    }

    @Test
    void blankSessionOrEmptyMessagesSkip() throws Exception {
        offloader.offload(" ", List.of(TurnMessage.user("x")), 0);
        offloader.offload(null, List.of(TurnMessage.user("x")), 0);
        offloader.offload("s1", List.of(), 0);
        offloader.offload("s1", null, 0);
        assertFalse(Files.exists(sessionsDir().resolve("s1.jsonl")));
    }

    @Test
    void offloadWritesJsonlLinesWithTurnNumbers() throws Exception {
        offloader.offload("s1", List.of(
                TurnMessage.user("你好"),
                TurnMessage.assistant("答复")), 3);

        List<String> lines = Files.readAllLines(sessionsDir().resolve("s1.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"turn\":3"));
        assertTrue(lines.get(0).contains("\"role\":\"user\""));
        assertTrue(lines.get(0).contains("你好"));
        assertTrue(lines.get(1).contains("\"turn\":4"));
    }

    @Test
    void smallToolResultInlinedWhileLargeResultSpilledToFile() throws Exception {
        TurnMessage small = TurnMessage.toolResult("c1", "read_file", "小结果");
        TurnMessage large = TurnMessage.toolResult("c2", "grep", "大".repeat(5001));

        offloader.offload("s2", List.of(small, large), 0);

        List<String> lines = Files.readAllLines(sessionsDir().resolve("s2.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"tool\":\"read_file\""));
        assertTrue(lines.get(0).contains("小结果"));
        // 大结果 → 独立文件 + 行内引用（无 text 字段）
        assertTrue(lines.get(1).contains("\"file\""), "大结果行应带文件引用");
        assertTrue(!lines.get(1).contains("大".repeat(100)));
        String ref = lines.get(1).replaceAll(".*\"file\":\"([^\"]+)\".*", "$1");
        Path spilled = sessionsDir().resolve(ref);
        assertTrue(Files.exists(spilled));
        assertEquals(5001, Files.readString(spilled).length());
    }

    @Test
    void offloadToolResultWritesAndDedupesByContentHash() throws Exception {
        String content = "x".repeat(300);
        String ref1 = offloader.offloadToolResult("call-1", "baidu-search", content);
        assertNotNull(ref1);
        Path file1 = sessionsDir().resolve(ref1);
        assertTrue(Files.exists(file1));
        assertEquals(content, Files.readString(file1));

        // 同内容复用同文件
        String ref2 = offloader.offloadToolResult("call-2", "baidu-search", content);
        assertEquals(ref1, ref2);

        // 空内容返回 null
        assertNull(offloader.offloadToolResult("call-3", "t", ""));
        assertNull(offloader.offloadToolResult("call-4", "t", null));
    }

    @Test
    void toolResultWithNullToolNameFallsBackToUnknown() throws Exception {
        String ref = offloader.offloadToolResult("call-1", null, "content");
        assertNotNull(ref);
        assertTrue(ref.contains("unknown_"), "toolName 为 null 时文件名回退 unknown");
    }
}

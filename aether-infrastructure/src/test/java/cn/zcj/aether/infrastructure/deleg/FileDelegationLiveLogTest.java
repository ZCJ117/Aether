package cn.zcj.aether.infrastructure.deleg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileDelegationLiveLogTest {

    @TempDir
    Path tempDir;

    private FileDelegationLiveLog newLog() {
        return new FileDelegationLiveLog(tempDir);
    }

    @Test
    void appendThenReadBackContainsHeaderAndLines() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "do the thing");
        log.append("deleg_abc", "assistant", "hello world");
        log.append("deleg_abc", "result", "ok");
        log.close("deleg_abc", "done");

        List<String> lines = log.tail("deleg_abc", 100);
        assertTrue(lines.stream().anyMatch(l -> l.contains("do the thing")), "header 应含 goal");
        assertTrue(lines.stream().anyMatch(l -> l.contains("hello world")), "应含 assistant 行");
        assertTrue(lines.stream().anyMatch(l -> l.contains("done")), "应含终态摘要行");
    }

    @Test
    void tailReturnsLastNInOrder() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "g");
        for (int i = 0; i < 10; i++) {
            log.append("deleg_abc", "assistant", "line-" + i);
        }
        List<String> tail = log.tail("deleg_abc", 3);
        assertEquals(3, tail.size());
        assertTrue(tail.get(tail.size() - 1).contains("line-9"));
    }

    @Test
    void directoriesAreIsolatedPerDelegation() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_a", "goal A");
        log.append("deleg_a", "assistant", "A-line");
        log.open("deleg_b", "goal B");
        log.append("deleg_b", "assistant", "B-line");

        assertTrue(log.tail("deleg_a", 10).stream().anyMatch(l -> l.contains("A-line")));
        assertFalse(log.tail("deleg_a", 10).stream().anyMatch(l -> l.contains("B-line")));
        assertTrue(log.tail("deleg_b", 10).stream().anyMatch(l -> l.contains("B-line")));
    }

    @Test
    void writerDegradesOnOpenFailureAndNeverRaises() throws Exception {
        Path blocker = tempDir.resolve("blocker.txt");
        Files.writeString(blocker, "x");
        FileDelegationLiveLog log = new FileDelegationLiveLog(blocker.resolve("sub").toString());
        assertDoesNotThrow(() -> {
            log.open("deleg_x", "g");
            log.append("deleg_x", "assistant", "line");
        });
        assertTrue(log.tail("deleg_x", 10).isEmpty(), "写入失败后应降级为空");
    }

    @Test
    void closeIsIdempotent() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "g");
        log.append("deleg_abc", "assistant", "one");
        log.close("deleg_abc", "final1");
        log.close("deleg_abc", "final2");
        long finals = log.tail("deleg_abc", 100).stream().filter(l -> l.contains("final1")).count();
        assertEquals(1, finals, "close 应幂等，不重复追加终态行");
    }

    @Test
    void pruneRemovesOldDirectories() throws Exception {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_old", "old");
        log.close("deleg_old", "x");
        Path oldDir = tempDir.resolve("deleg_old");
        long past = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000;
        Files.setLastModifiedTime(oldDir, FileTime.fromMillis(past));

        int removed = log.prune(7);
        assertEquals(1, removed);
        assertFalse(Files.exists(oldDir));
    }

    @Test
    void longLineIsTruncated() {
        FileDelegationLiveLog log = newLog();
        log.open("deleg_abc", "g");
        String longLine = "x".repeat(1000);
        log.append("deleg_abc", "assistant", longLine);
        List<String> lines = log.tail("deleg_abc", 10);
        assertTrue(lines.stream().anyMatch(l -> l.contains("(+")));
    }
}

package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileCheckpointCollector / CheckpointData / ListHashUtil 单测（覆盖率盲区补测 P0 2.1）。
 */
class CheckpointPersistenceTest {

    private FileCheckpointCollector collector;

    @TempDir
    Path tempDir;

        @BeforeEach
    void setUp() {
        collector = new FileCheckpointCollector(tempDir.resolve(".claude/checkpoints"));
    }

    private CheckpointData ckpt(String sessionId, int turn) {
        return CheckpointData.create(sessionId, "a1", turn,
                Map.of("currentTurn", turn, "status", "IDLE"), turn * 2);
    }

    @Test
    void saveAndLoadLatestRoundtrip() throws Exception {
        collector.save(ckpt("s1", 1));
        collector.save(ckpt("s1", 2));

        List<String> names;
        try (var s = Files.list(tempDir.resolve(".claude/checkpoints/s1"))) {
            names = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("ckpt-0001.json", "ckpt-0002.json"), names, "目录应只有两个检查点");

        Optional<CheckpointData> latest = collector.loadLatest("s1");
        assertTrue(latest.isPresent());
        assertEquals(2, latest.get().getTurnNumber());
        assertEquals(4, latest.get().getMessageCount());
        assertEquals("IDLE", latest.get().getAgentState().get("status"));
    }

    @Test
    void loadByTurnAndMissingSessionYieldEmpty() {
        collector.save(ckpt("s1", 3));

        assertTrue(collector.load("s1", 3).isPresent());
        assertTrue(collector.load("s1", 9).isEmpty());
        assertTrue(collector.loadLatest("no-such-session").isEmpty());
        assertEquals(List.of(), collector.listCheckpoints("no-such-session"));
    }

    @Test
    void listCheckpointsReturnsDescendingOrder() {
        collector.save(ckpt("s1", 1));
        collector.save(ckpt("s1", 2));
        collector.save(ckpt("s1", 3));

        List<Integer> turns = collector.listCheckpoints("s1").stream()
                .map(CheckpointData::getTurnNumber).toList();
        assertEquals(List.of(3, 2, 1), turns, "按文件名倒序");
    }

    @Test
    void corruptCheckpointFileFilteredOut() throws Exception {
        collector.save(ckpt("s1", 1));
        Path dir = tempDir.resolve(".claude/checkpoints/s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ckpt-0002.json"), "{corrupt");

        Optional<CheckpointData> latest = collector.loadLatest("s1");
        // 倒序首个是损坏文件 → readCheckpoint 返回 null → Optional.empty
        assertTrue(latest.isEmpty());
        // 列表过滤损坏文件，仅剩合法检查点
        assertEquals(1, collector.listCheckpoints("s1").size());
    }

    @Test
    void checkpointDataJsonRoundtrip() {
        CheckpointData data = CheckpointData.create("s9", "a1", 7,
                Map.of("currentTurn", 7), 14);
        data.setTimestamp(Instant.parse("2026-01-01T00:00:00Z"));

        CheckpointData parsed = CheckpointData.fromJson(data.toJson());
        assertEquals("s9", parsed.getSessionId());
        assertEquals("a1", parsed.getAgentId());
        assertEquals(7, parsed.getTurnNumber());
        assertEquals(14, parsed.getMessageCount());
        assertEquals(7, parsed.getAgentState().get("currentTurn"));
    }

    @Test
    void listHashPrefixSamplingStableForAppendOnly() {
        var a = new Object();
        var b = new Object();
        var c = new Object();

        assertEquals("empty:0", ListHashUtil.computeHash(null));
        assertEquals("empty:0", ListHashUtil.computeHash(List.of()));

        String h2 = ListHashUtil.computeHash(List.of(a, b));
        String h2again = ListHashUtil.computeHash(List.of(a, b));
        assertEquals(h2, h2again, "同一列表实例哈希稳定");

        String h3 = ListHashUtil.computeHash(List.of(a, b, c));
        assertTrue(ListHashUtil.needsFullRewrite(List.of(a, b, c), h2, 2) == false,
                "前缀未变 → 仅追加增量");

        // 前缀对象被替换（新增头部元素导致采样点错位）→ 全量重写
        assertTrue(ListHashUtil.needsFullRewrite(List.of(c, a, b), h2, 2),
                "头部插入改变前缀采样点 → 全量重写");

        // 收缩 → 全量重写
        assertTrue(ListHashUtil.needsFullRewrite(List.of(a), h2, 2));
        // 无已存哈希且无已持久化数据 → 仅追加
        assertFalse(ListHashUtil.needsFullRewrite(List.of(a, b), null, 0));
        // 已存条目数超过当前列表（收缩）→ 全量重写
        assertTrue(ListHashUtil.needsFullRewrite(List.of(a), h2, 5));
        // 前缀一致 → 仅追加
        assertFalse(ListHashUtil.needsFullRewrite(List.of(a, b), h2, 2));
    }
}

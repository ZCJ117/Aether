package cn.zcj.aether.domain.agent.service.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O11: MemoryStore scope 分片存储测试。
 *
 * <p>覆盖：分片目录落盘、scope 隔离（兄弟/跨用户不可见、祖先/后代可见）、
 * JSON scope 字段解析、旧平铺文件迁移与兼容读取、deleteByScope 落到路径。</p>
 */
class MemoryStoreScopeShardTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        ReflectionTestUtils.setField(store, "configuredMemoryDir", tempDir.toString());
    }

    private MemoryRecord record(String id, String content, MemoryScope scope) {
        return MemoryRecord.builder()
            .id(id)
            .content(content)
            .scope(scope)
            .importance(0.5f)
            .source("test")
            .build();
    }

    @Test
    void upsertWritesIntoScopeShardDirectory() {
        store.upsert("g1", new float[]{1f, 0f}, record("g1", "全局记忆", MemoryScope.global())).join();
        store.upsert("a1", new float[]{0f, 1f}, record("a1", "Alice 记忆", new MemoryScope("user/alice", true))).join();

        assertTrue(Files.exists(tempDir.resolve("global").resolve("g1.json")),
            "global scope 应落盘到 global/ 分片");
        assertTrue(Files.exists(tempDir.resolve("user").resolve("alice").resolve("a1.json")),
            "user/alice scope 应落盘到 user/alice/ 分片");
    }

    @Test
    void upsertWritesProperEscapedJsonWithScope() throws Exception {
        String tricky = "包含\"引号\"和\\反斜杠\\与\n换行";
        store.upsert("t1", null, record("t1", tricky, MemoryScope.global())).join();

        String json = Files.readString(tempDir.resolve("global").resolve("t1.json"));
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals(tricky, node.get("content").asText(), "ObjectMapper 序列化应正确转义特殊字符");
        assertEquals("", node.get("scope").asText(), "JSON 应携带 scope 字段");
    }

    @Test
    void searchIsolatesSiblingScopes() {
        store.upsert("alice", new float[]{1f, 0f}, record("alice", "Alice 私有", new MemoryScope("user/alice", true))).join();
        store.upsert("bob", new float[]{1f, 0f}, record("bob", "Bob 私有", new MemoryScope("user/bob", true))).join();

        List<MemorySearchResult> aliceView = store.search(
            new float[]{1f, 0f}, 10, List.of(new MemoryScope("user/alice", true))).join();
        List<MemorySearchResult> bobView = store.search(
            new float[]{1f, 0f}, 10, List.of(new MemoryScope("user/bob", true))).join();

        assertTrue(aliceView.stream().anyMatch(r -> r.getRecord().getId().equals("alice")));
        assertFalse(aliceView.stream().anyMatch(r -> r.getRecord().getId().equals("bob")),
            "user/alice 查询不应命中 user/bob 分片（兄弟隔离）");
        assertTrue(bobView.stream().anyMatch(r -> r.getRecord().getId().equals("bob")));
        assertFalse(bobView.stream().anyMatch(r -> r.getRecord().getId().equals("alice")));
    }

    @Test
    void searchSeesAncestorAndDescendantScopes() {
        store.upsert("global1", new float[]{1f, 0f}, record("global1", "全局", MemoryScope.global())).join();
        store.upsert("userLevel", new float[]{1f, 0f}, record("userLevel", "用户层", new MemoryScope("user", false))).join();
        store.upsert("alice1", new float[]{1f, 0f}, record("alice1", "Alice 层", new MemoryScope("user/alice", true))).join();
        store.upsert("agent1", new float[]{1f, 0f}, record("agent1", "Agent 层", new MemoryScope("agent/analyst", false))).join();

        List<MemorySearchResult> aliceView = store.search(
            new float[]{1f, 0f}, 10, List.of(new MemoryScope("user/alice", true))).join();
        List<String> ids = aliceView.stream().map(r -> r.getRecord().getId()).toList();

        assertTrue(ids.contains("global1"), "global（祖先）对 user/alice 可见");
        assertTrue(ids.contains("userLevel"), "user（祖先）对 user/alice 可见");
        assertTrue(ids.contains("alice1"), "自身 scope 可见");
        assertFalse(ids.contains("agent1"), "兄弟分片 agent/analyst 对 user/alice 不可见");
    }

    @Test
    void emptyScopesSearchReturnsAllShards() {
        store.upsert("a", new float[]{1f, 0f}, record("a", "A", MemoryScope.global())).join();
        store.upsert("b", new float[]{1f, 0f}, record("b", "B", new MemoryScope("user/alice", true))).join();

        List<MemorySearchResult> all = store.search(new float[]{1f, 0f}, 10, List.of()).join();
        assertEquals(2, all.size(), "scopes 为空（兼容语义）应可检索全部分片");
    }

    @Test
    void parseMemoryRecordReadsScopeFromJson() throws Exception {
        // 手工构造带 scope 字段的记录文件（模拟写入后解析回读）
        Path shard = tempDir.resolve("user").resolve("carol");
        Files.createDirectories(shard);
        Files.writeString(shard.resolve("c1.json"),
            "{\"id\":\"c1\",\"content\":\"Carol 记忆\",\"embedding\":\"null\","
                + "\"scope\":\"user/carol\",\"scopePrivate\":true,\"importance\":0.7}");

        List<MemorySearchResult> results = store.search(
            null, 10, List.of(new MemoryScope("user/carol", true))).join();
        assertEquals(1, results.size());
        assertEquals("user/carol", results.get(0).getRecord().getScope().path(),
            "scope 应从 JSON 字段读取而非恒 global");
        assertTrue(results.get(0).getRecord().isPrivate());
    }

    @Test
    void migrateLegacyFlatFilesMovesIntoShards() throws Exception {
        // 旧版平铺命名：<scopePath>-<id>.json，JSON 内带 scope 字段
        Files.writeString(tempDir.resolve("user-alice-old1.json"),
            "{\"id\":\"old1\",\"content\":\"旧平铺记录\",\"embedding\":\"null\",\"scope\":\"user/alice\"}");
        Files.writeString(tempDir.resolve("global-old2.json"),
            "{\"id\":\"old2\",\"content\":\"旧全局记录\",\"embedding\":\"null\"}"); // 无 scope → global

        store.migrateLegacyFlatFiles();

        assertTrue(Files.exists(tempDir.resolve("user").resolve("alice").resolve("user-alice-old1.json")),
            "带 scope 的旧文件应迁入对应分片");
        assertTrue(Files.exists(tempDir.resolve("global").resolve("global-old2.json")),
            "无 scope 的旧文件应迁入 global 分片");
        assertFalse(Files.exists(tempDir.resolve("user-alice-old1.json")), "原平铺文件应被移走");

        // 迁移后可被 scope 检索命中（null 向量走 recency 降级分支，记录 embedding 为 null）
        List<MemorySearchResult> aliceView = store.search(
            null, 10, List.of(new MemoryScope("user/alice", true))).join();
        assertTrue(aliceView.stream().anyMatch(r -> r.getRecord().getId().equals("old1")),
            "迁移后的记录应在目标 scope 可检索");
    }

    @Test
    void legacyFlatFileReadableBeforeMigration() throws Exception {
        // 不调用迁移 — 平铺兼容读取分支应兜底（JSON scope 字段可见性复核）；
        // null 查询向量走 recency 降级分支（该记录 embedding 为 null）
        Files.writeString(tempDir.resolve("user-alice-legacy.json"),
            "{\"id\":\"legacy1\",\"content\":\"未迁移记录\",\"embedding\":\"null\",\"scope\":\"user/alice\"}");

        List<MemorySearchResult> aliceView = store.search(
            null, 10, List.of(new MemoryScope("user/alice", true))).join();
        assertTrue(aliceView.stream().anyMatch(r -> r.getRecord().getId().equals("legacy1")),
            "迁移前的旧平铺文件应通过兼容分支可检索");

        List<MemorySearchResult> bobView = store.search(
            null, 10, List.of(new MemoryScope("user/bob", true))).join();
        assertFalse(bobView.stream().anyMatch(r -> r.getRecord().getId().equals("legacy1")),
            "旧平铺文件的 scope 隔离仍应生效");
    }

    @Test
    void deleteByScopeRemovesShardSubtreeOnly() {
        store.upsert("a1", new float[]{1f, 0f}, record("a1", "Alice", new MemoryScope("user/alice", true))).join();
        store.upsert("a2", new float[]{1f, 0f}, record("a2", "Alice 子域", new MemoryScope("user/alice/notes", true))).join();
        store.upsert("b1", new float[]{1f, 0f}, record("b1", "Bob", new MemoryScope("user/bob", true))).join();
        store.upsert("g1", new float[]{1f, 0f}, record("g1", "全局", MemoryScope.global())).join();

        store.deleteByScope(new MemoryScope("user/alice", true)).join();

        assertFalse(Files.exists(tempDir.resolve("user").resolve("alice")),
            "user/alice 分片子树应被整体删除");
        assertTrue(Files.exists(tempDir.resolve("user").resolve("bob").resolve("b1.json")),
            "兄弟分片不应受影响");
        assertTrue(Files.exists(tempDir.resolve("global").resolve("g1.json")),
            "global 分片不应受影响");
    }

    @Test
    void deleteRemovesRecordAcrossShards() {
        store.upsert("d1", new float[]{1f, 0f}, record("d1", "待删除", new MemoryScope("agent/analyst", false))).join();
        store.delete("d1").join();
        assertFalse(Files.exists(tempDir.resolve("agent").resolve("analyst").resolve("d1.json")),
            "删除应命中分片目录内的记录");
    }

    @Test
    void findMissingEmbeddingsScansAllShards() {
        store.upsert("m1", null, record("m1", "无向量A", MemoryScope.global())).join();
        store.upsert("m2", null, record("m2", "无向量B", new MemoryScope("user/dave", true))).join();
        store.upsert("m3", new float[]{1f, 0f}, record("m3", "有向量", MemoryScope.global())).join();

        List<MemoryRecord> missing = store.findMissingEmbeddings(10).join();
        assertEquals(2, missing.size(), "回填扫描应覆盖全部分片");
    }

    @Test
    void scopePathSanitizationRejectsTraversal() {
        store.upsert("evil", new float[]{1f, 0f},
                record("evil", "路径穿越", new MemoryScope("../../etc", false))).join();

        // ".." 段应被剔除，落盘在 memoryDir 内的 etc/ 分片，而非逃逸出 memoryDir
        assertTrue(Files.exists(tempDir.resolve("etc").resolve("evil.json")),
            "scope 路径穿越段应被清除");
        assertFalse(Files.exists(tempDir.getParent().resolve("etc")),
            "不得逃逸出记忆根目录");
    }
}

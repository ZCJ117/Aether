package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.3): 记忆生命周期集成测试（真实 PG/pgvector，-Pintegration）。
 *
 * <p>验证归档→召回不可见→再次写入复活 的完整闭环，
 * 以及归档列自愈（archived 列在旧库上自动补齐）。</p>
 */
@Tag("integration")
class PgMemoryLifecycleIT extends AbstractPgIT {

    private PgvectorVectorStore newStore() {
        return new PgvectorVectorStore(dataSource(), Executors.newFixedThreadPool(2));
    }

    private PgMemoryDecayStore newDecayStore() {
        return new PgMemoryDecayStore(dataSource());
    }

    @Test
    void archiveHideThenRevive() {
        PgvectorVectorStore store = newStore();
        PgMemoryDecayStore decay = newDecayStore();
        String scope = "it/decay-" + System.nanoTime();
        MemoryScope ms = new MemoryScope(scope, false);

        // 旧记忆（last_accessed_at 回拨 400 天，importance 0.1）→ 保留分必然极低
        jdbc().update(
            "INSERT INTO aether_memories (id, content, scope_path, importance, created_at, last_accessed_at, access_count) " +
            "VALUES ('decay-old', '很久以前的琐碎记忆', ?, 0.1, NOW() - INTERVAL '400 days', NOW() - INTERVAL '400 days', 0)",
            scope);
        // 新记忆（刚访问，importance 1.0）→ 保留分 0.7
        jdbc().update(
            "INSERT INTO aether_memories (id, content, scope_path, importance, created_at, last_accessed_at, access_count) " +
            "VALUES ('decay-new', '最近的重要偏好', ?, 1.0, NOW(), NOW(), 5)",
            scope);

        // ① 衰减前两条都在召回中（recency 路径，null 向量）
        var before = store.search(new float[1024], 10, List.of(ms)).join();
        assertEquals(2, before.size());

        // ② 衰减：阈值 0.5 → 旧记忆（retention≈0.1）归档，新记忆（0.7）保留
        int archived = decay.archiveStale(30.0, 0.5, 100);
        assertTrue(archived >= 1);

        // ③ 归档记忆从召回中消失
        var after = store.search(new float[1024], 10, List.of(ms)).join();
        assertEquals(1, after.size(), "归档记忆不应再被召回");
        assertEquals("decay-new", after.get(0).getRecord().getId());
        assertEquals(0, jdbc().queryForObject(
            "SELECT COUNT(*) FROM aether_memories WHERE id='decay-old' AND archived=false", Integer.class));

        // ④ 复活：用户再次提及同一记忆 → upsert 清 archived
        store.upsert("decay-old", new float[1024],
            cn.zcj.aether.domain.agent.service.memory.MemoryRecord.builder()
                .id("decay-old").content("很久以前的琐碎记忆")
                .scope(ms).importance(0.5f)
                .createdAt(java.time.Instant.now()).lastAccessedAt(java.time.Instant.now())
                .accessCount(1).build()).join();

        var revived = store.search(new float[1024], 10, List.of(ms)).join();
        assertEquals(2, revived.size(), "复活后的记忆应重新可见");
    }

    @Test
    void touchIncrementsAccessCount() {
        PgvectorVectorStore store = newStore();
        String scope = "it/touch-" + System.nanoTime();
        MemoryScope ms = new MemoryScope(scope, false);
        jdbc().update(
            "INSERT INTO aether_memories (id, content, scope_path, importance, access_count) " +
            "VALUES ('touch-1', '访问回填', ?, 0.8, 0)", scope);

        store.search(new float[1024], 10, List.of(ms)).join();

        Integer count = jdbc().queryForObject(
            "SELECT access_count FROM aether_memories WHERE id='touch-1'", Integer.class);
        assertTrue(count >= 1, "命中后 access_count 应回填递增: " + count);
    }
}

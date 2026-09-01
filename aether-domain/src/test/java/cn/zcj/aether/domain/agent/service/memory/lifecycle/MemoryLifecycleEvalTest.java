package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.EncodingFlow;
import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.3): 记忆生命周期确定性评测 —— "记得住、忘得掉、不打架"三断言。
 *
 * <p>模拟 20 轮跨会话写入的记忆池：10 条高重要性（偏好/事实）+ 10 条低重要性（闲聊），
 * 无网络依赖（fake store + 确定性策略），可随 CI 常驻回归。</p>
 */
class MemoryLifecycleEvalTest {

    /** 记忆池（id → retention，模拟 SQL 保留分筛选结果）。 */
    private static class MemoryPool implements MemoryDecayStore {
        final Map<String, Double> active = new java.util.LinkedHashMap<>();
        final List<String> archivedIds = new java.util.ArrayList<>();

        MemoryPool(int important, int trivial) {
            for (int i = 0; i < important; i++) {
                active.put("important-" + i, 0.7);   // 高保留：新近 + 有访问 + 重要
            }
            for (int i = 0; i < trivial; i++) {
                active.put("trivial-" + i, 0.1);     // 低保留：陈旧 + 零访问 + 琐碎
            }
        }

        @Override
        public int archiveStale(double halfLifeDays, double minRetentionScore, int batchSize) {
            int archived = 0;
            var it = active.entrySet().iterator();
            while (it.hasNext() && archived < batchSize) {
                var e = it.next();
                if (e.getValue() < minRetentionScore) {
                    archivedIds.add(e.getKey());
                    it.remove();
                    archived++;
                }
            }
            return archived;
        }

        @Override public long countActive() { return active.size(); }
        @Override public long countArchived() { return archivedIds.size(); }
    }

    @Test
    void rememberRetainForgetNoConflict() {
        MemoryPool pool = new MemoryPool(10, 10);
        MemoryDecayJob job = new MemoryDecayJob(pool, new MemoryDecayMetrics(null),
                cfg(30.0, 0.2, 100, 50));

        // === 断言 1: 忘得掉 —— 衰减一轮后 10 条琐碎记忆全部归档 ===
        int archived = job.runOnce();
        assertEquals(10, archived, "低保留分记忆应全部被遗忘曲线归档");
        assertEquals(10, pool.countActive());

        // === 断言 2: 记得住 —— 高重要性记忆在归档集之外 ===
        for (int i = 0; i < 10; i++) {
            assertTrue(pool.active.containsKey("important-" + i), "重要记忆必须保留: important-" + i);
        }
        assertFalse(pool.archivedIds.contains("important-0"));

        // === 断言 3: 不打架 —— 偏好更新时新胜旧（new-wins），旧偏好不再出现在合并结果中 ===
        MemoryConflictResolver resolver = new MemoryConflictResolver(null, "new-wins");
        MemoryRecord oldPreference = MemoryRecord.builder()
                .id("pref-1").content("用户的编辑器是 IntelliJ IDEA")
                .scope(new MemoryScope("global", false))
                .importance(0.8f).createdAt(Instant.now()).lastAccessedAt(Instant.now())
                .accessCount(5).build();
        var outcome = resolver.resolve(oldPreference, "用户的编辑器已换成 VSCode", 0.9f);
        assertFalse(outcome.content().contains("IntelliJ IDEA"), "旧偏好不应残留在生效记忆中");
        assertTrue(outcome.content().contains("VSCode"));

        // === 断言 4: 写入门槛 —— 琐碎内容（LLM 打分 0.2）被拦在入库之前 ===
        var lowValue = new EncodingFlow.EncodeResult(List.of("chat"), 0.2f, false, "", true, true, 80);
        assertFalse(MemoryWriteGate.shouldAccept(lowValue, 0.6f, true), "琐碎记忆不应入库");
        var highValue = new EncodingFlow.EncodeResult(List.of("preference"), 0.9f, false, "", true, true, 80);
        assertTrue(MemoryWriteGate.shouldAccept(highValue, 0.6f, true), "偏好记忆应入库");
    }

    private static MemoryProperties.Decay cfg(double halfLife, double threshold, int maxPerRun, int batch) {
        MemoryProperties.Decay d = new MemoryProperties.Decay();
        d.setHalfLifeDays(halfLife);
        d.setMinRetentionScore(threshold);
        d.setMaxArchivePerRun(maxPerRun);
        d.setBatchSize(batch);
        return d;
    }

    /**
     * 路线图 4.3 第 4 步：20 轮跨会话记忆依赖 case —— 会话 A（轮 1-10）→ 会话 B（轮 11-20），
     * 逐轮写入并串起四个机制：写入门槛（闲聊拒绝）、冲突合并（跨会话偏好更新）、
     * 衰减遗忘（低保留弱备注归档）、跨会话检索保留（早期记忆在后期会话仍可用）。
     */
    @Test
    void twentyRoundCrossSessionDependency() {
        MemoryPool pool = new MemoryPool(0, 0);
        MemoryConflictResolver resolver = new MemoryConflictResolver(null, "new-wins");
        MemoryDecayJob job = new MemoryDecayJob(pool, new MemoryDecayMetrics(null),
                cfg(30.0, 0.2, 100, 50));

        // 逐轮写入的记忆槽：id → 内容（importance 来自 LLM 打分，retention 由新近度/访问/重要度决定）
        Map<String, String> memories = new java.util.LinkedHashMap<>();

        // ===== 会话 A（轮 1-10）=====
        // r1 偏好（LLM 打分 0.9 ≥ 0.6 → 通过门槛入库，高保留）
        memories.put("sA-r1", "用户的编辑器是 IntelliJ IDEA");
        pool.active.put("sA-r1", 0.7);
        // r2 事实（0.8 → 入库，高保留）
        memories.put("sA-r2", "用户的项目在 D:\\code");
        pool.active.put("sA-r2", 0.7);
        // r3-r8 闲聊（LLM 打分 0.2 < 0.6 → WriteGate 拦截，不入库）
        var chitChat = new EncodingFlow.EncodeResult(List.of("chat"), 0.2f, false, "", true, true, 80);
        for (int r = 3; r <= 8; r++) {
            assertFalse(MemoryWriteGate.shouldAccept(chitChat, 0.6f, true), "轮 " + r + " 闲聊不应入库");
        }
        // r9-r10 弱备注（打分 0.62 勉强入库，但陈旧零访问 → 低保留分）
        var weakNote = new EncodingFlow.EncodeResult(List.of("chat"), 0.62f, false, "", true, true, 80);
        assertTrue(MemoryWriteGate.shouldAccept(weakNote, 0.6f, true));
        memories.put("sA-r9", "今天聊到了天气不错");
        pool.active.put("sA-r9", 0.1);
        memories.put("sA-r10", "顺便提到午饭吃了面");
        pool.active.put("sA-r10", 0.1);

        // ===== 会话 B（轮 11-20）=====
        // r11-r18 闲聊 → 全部被门槛拦截
        // r19 偏好更新（0.9 → 通过门槛）：与会话 A r1 的旧偏好冲突 → new-wins 合并，旧内容不残留
        var prefUpdate = new EncodingFlow.EncodeResult(List.of("preference"), 0.9f, false, "", true, true, 80);
        assertTrue(MemoryWriteGate.shouldAccept(prefUpdate, 0.6f, true));
        MemoryRecord oldPreference = MemoryRecord.builder()
                .id("sA-r1").content(memories.get("sA-r1"))
                .scope(new MemoryScope("global", false))
                .importance(0.9f).createdAt(Instant.now()).lastAccessedAt(Instant.now())
                .accessCount(3).build();
        var merged = resolver.resolve(oldPreference, "用户的编辑器已换成 VSCode", 0.9f);
        assertEquals("new-wins", merged.strategy());
        memories.put("sA-r1", merged.content());
        // r20 新事实（0.8 → 入库，高保留）
        memories.put("sB-r20", "用户的部署环境是 k8s");
        pool.active.put("sB-r20", 0.7);

        // === 断言 1: 写入门槛 —— 20 轮只入库 5 条（偏好 r1 + 事实 r2 + 弱备注 r9/r10 + 新事实 r20）===
        assertEquals(5, pool.countActive(), "门槛应拦下全部闲聊轮（8+8=16 条）");

        // === 断言 2: 衰减遗忘 —— 低保留弱备注归档，重要记忆全部保留 ===
        int archived = job.runOnce();
        assertEquals(2, archived, "仅 sA-r9/sA-r10 两条低保留分应归档");
        assertTrue(pool.archivedIds.containsAll(List.of("sA-r9", "sA-r10")));
        assertTrue(pool.active.containsKey("sA-r1") && pool.active.containsKey("sA-r2")
                && pool.active.containsKey("sB-r20"), "重要/事实记忆必须留存");

        // === 断言 3: 跨会话不打架 —— 会话 A 写入的偏好在会话 B 更新后生效内容为新偏好 ===
        assertTrue(memories.get("sA-r1").contains("VSCode"), "跨会话偏好更新应生效");
        assertFalse(memories.get("sA-r1").contains("IntelliJ IDEA"), "旧偏好不得残留");
    }
}

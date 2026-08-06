package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 测试双：内存版 MemoryFacade，基于字符袋向量做真实余弦相似度检索。
 * 使核心验收测试（写 10 条 → 精确检索）无需 Postgres/pgvector 即可运行。
 */
class FakeMemoryFacade implements MemoryFacade {

    static final int DIM = 32;

    final Map<String, MemoryRecord> records = new LinkedHashMap<>();

    @Override
    public CompletableFuture<MemoryRecord> remember(String content, MemoryScope scope, StoreOptions options) {
        String id = UUID.randomUUID().toString();
        MemoryRecord r = MemoryRecord.builder()
                .id(id)
                .content(content)
                .embedding(embed(content))
                .scope(scope)
                .categories(List.of())
                .importance(0.5f)
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now())
                .accessCount(1)
                .isPrivate(scope != null && scope.isPrivate())
                .source("test")
                .build();
        records.put(id, r);
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public void rememberMany(List<MemoryEntry> entries, MemoryScope scope) {
        for (MemoryEntry e : entries) {
            remember(e.content(), scope, new StoreOptions());
        }
    }

    @Override
    public CompletableFuture<List<MemorySearchResult>> recall(String query, RecallOptions options) {
        return CompletableFuture.completedFuture(
                searchByVec(embed(query), options.scopes(), options.maxResults()));
    }

    @Override
    public List<MemorySearchResult> search(String query, MemoryScope scope, int maxResults) {
        return searchByVec(embed(query), List.of(scope), maxResults);
    }

    @Override
    public void drain() {
    }

    @Override
    public CompletableFuture<Void> clear(MemoryScope scope) {
        records.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public MemoryStats stats() {
        return new MemoryStats(records.size(), 0, 0.5);
    }

    private List<MemorySearchResult> searchByVec(float[] q, List<MemoryScope> scopes, int max) {
        List<MemorySearchResult> out = new ArrayList<>();
        for (MemoryRecord r : records.values()) {
            if (scopes != null && !scopes.isEmpty()
                    && scopes.stream().noneMatch(s -> s.contains(r.getScope()))) {
                continue;
            }
            out.add(new MemorySearchResult(r, cosine(q, r.getEmbedding())));
        }
        out.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return out.subList(0, Math.min(out.size(), max));
    }

    static float[] embed(String text) {
        float[] v = new float[DIM];
        for (char c : text.toLowerCase().toCharArray()) {
            v[Math.floorMod(c, DIM)] += 1.0f;
        }
        return v;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}

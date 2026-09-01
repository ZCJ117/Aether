package cn.zcj.aether.domain.agent.service.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCallCacheTest {

    private static ModelInvoker.ModelCallResult result(String text) {
        return ModelInvoker.ModelCallResult.builder()
                .fullText(text)
                .events(List.of(RuntimeEvent.text(text)))
                .toolCalls(List.of(ModelInvoker.ToolCallDef.builder()
                        .id("tool-1").name("search").input(java.util.Map.of("q", "redis")).build()))
                .inputTokens(12)
                .outputTokens(34)
                .cacheTokens(0)
                .costUsd(0.001)
                .build();
    }

    @Test
    void getReturnsNothingWithoutSecondaryStore() {
        ModelCallCache cache = new ModelCallCache(null, (ModelCacheStore) null);
        assertThat(cache.get("model:key")).isNull();
    }

    @Test
    void secondaryStoreServesL2MissAndIsPromotedToL1() {
        ModelInvoker.ModelCallResult original = result("cached answer");
        List<ModelCacheSnapshot> stored = new java.util.ArrayList<>();
        ModelCacheStore store = new ModelCacheStore() {
            @Override
            public Optional<ModelCacheSnapshot> get(String key) {
                return stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(0));
            }

            @Override
            public void put(String key, ModelCacheSnapshot snapshot, Duration ttl) {
                stored.add(snapshot);
            }
        };
        ModelCallCache cache = new ModelCallCache(null, store);

        cache.put("model:key", original, 30);
        cache.clear();
        ModelInvoker.ModelCallResult first = cache.get("model:key");
        ModelInvoker.ModelCallResult second = cache.get("model:key");

        assertThat(first).isNotNull();
        assertThat(first.getFullText()).isEqualTo("cached answer");
        assertThat(first.getInputTokens()).isEqualTo(12);
        assertThat(first.getOutputTokens()).isEqualTo(34);
        assertThat(first.getToolCalls()).hasSize(1);
        assertThat(first.getToolCalls().get(0).getName()).isEqualTo("search");
        assertThat(second).isSameAs(first);
    }

    @Test
    void secondaryFailureDegradesToMissWithoutThrowing() {
        ModelCacheStore broken = new ModelCacheStore() {
            @Override
            public Optional<ModelCacheSnapshot> get(String key) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void put(String key, ModelCacheSnapshot snapshot, Duration ttl) {
                throw new IllegalStateException("redis down");
            }
        };
        ModelCallCache cache = new ModelCallCache(null, broken);

        cache.put("model:key", result("answer"), 30);
        // L1 remains usable even when L2 cannot accept the write.
        assertThat(cache.get("model:key")).isNotNull();

        cache.clear();
        assertThat(cache.get("model:key")).isNull();
    }
}

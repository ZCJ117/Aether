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
    void cacheKeyIsStableForSameInput() {
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new org.springframework.ai.chat.messages.UserMessage("你好"));
        assertThat(ModelCallCache.cacheKey("gpt-4o", messages))
                .isEqualTo(ModelCallCache.cacheKey("gpt-4o", messages));
    }

    @Test
    void cacheKeyDistinguishesDifferentModelAndText() {
        List<org.springframework.ai.chat.messages.Message> messages =
                List.of(new org.springframework.ai.chat.messages.UserMessage("你好"));
        List<org.springframework.ai.chat.messages.Message> other =
                List.of(new org.springframework.ai.chat.messages.UserMessage("再见"));
        String key = ModelCallCache.cacheKey("gpt-4o", messages);
        assertThat(key).isNotEqualTo(ModelCallCache.cacheKey("gpt-4o", other));
        assertThat(key).isNotEqualTo(ModelCallCache.cacheKey("claude-sonnet", messages));
    }

    @Test
    void cacheKeyDoesNotCollideOnLegacy32BitHash() {
        // "Aa" 与 "BB" 的 String.hashCode 相同（2112），旧 Objects.hash 实现下两段对话必碰撞；
        // SHA-256 摘要必须能区分它们
        List<org.springframework.ai.chat.messages.Message> aa =
                List.of(new org.springframework.ai.chat.messages.UserMessage("Aa"));
        List<org.springframework.ai.chat.messages.Message> bb =
                List.of(new org.springframework.ai.chat.messages.UserMessage("BB"));
        assertThat(ModelCallCache.cacheKey("gpt-4o", aa))
                .isNotEqualTo(ModelCallCache.cacheKey("gpt-4o", bb));
    }

    @Test
    void cacheKeyDistinguishesRoleWithSameText() {
        List<org.springframework.ai.chat.messages.Message> asUser =
                List.of(new org.springframework.ai.chat.messages.UserMessage("同样的话"));
        List<org.springframework.ai.chat.messages.Message> asTool =
                List.of(new org.springframework.ai.chat.messages.ToolResponseMessage(
                        List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                                "call-1", "search", "同样的话"))));
        assertThat(ModelCallCache.cacheKey("gpt-4o", asUser))
                .isNotEqualTo(ModelCallCache.cacheKey("gpt-4o", asTool));
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

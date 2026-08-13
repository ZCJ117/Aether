package cn.zcj.aether.infrastructure.credential;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.failover.CredentialPool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotatingCredentialPoolTest {

    @Test
    void rotatesToNextCredential() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null),
                new CredentialPool.CredentialEntry("key2", "https://b", null)));

        ModelConfig current = ModelConfig.builder()
                .modelId("gpt-4o").apiKey("key1").baseUrl("https://a").build();

        ModelConfig next = pool.rotate(current, "openai").orElseThrow();
        assertEquals("key2", next.getApiKey());
        assertEquals("gpt-4o", next.getModelId());
        assertEquals("https://b", next.getBaseUrl());
    }

    @Test
    void emptyWhenOnlyOneCredential() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();
        assertTrue(pool.rotate(current, "openai").isEmpty());
    }

    @Test
    void emptyWhenKeyUnknown() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("unknown").build();
        assertTrue(pool.rotate(current, "openai").isEmpty());
    }

    @Test
    void emptyWhenProviderUnknown() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();
        assertTrue(pool.rotate(current, "unknown-provider").isEmpty());
    }

    @Test
    void seedOverwritesPreviousPool() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null),
                new CredentialPool.CredentialEntry("key2", "https://b", null)));
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key3", "https://c", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key3").build();
        assertTrue(pool.rotate(current, "openai").isEmpty()); // 重播种后只剩 1 组，无法轮换
    }
}

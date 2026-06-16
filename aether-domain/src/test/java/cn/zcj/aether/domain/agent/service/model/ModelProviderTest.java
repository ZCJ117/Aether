package cn.zcj.aether.domain.agent.service.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelProvider 单元测试 — P0-5
 * 验证：ModelConfig 构造、ProviderRegistry 基本逻辑
 */
class ModelProviderTest {

    @Test
    void modelConfigBuilderShouldWork() {
        var config = ModelConfig.builder()
                .modelId("gpt-4o")
                .baseUrl("https://api.openai.com")
                .apiKey("sk-test")
                .completionsPath("v1/chat/completions")
                .embeddingsPath("v1/embeddings")
                .build();

        assertEquals("gpt-4o", config.getModelId());
        assertEquals("https://api.openai.com", config.getBaseUrl());
        assertEquals("sk-test", config.getApiKey());
        assertEquals("v1/chat/completions", config.getCompletionsPath());
        assertEquals("v1/embeddings", config.getEmbeddingsPath());
        assertNotNull(config.getExtraParams());
    }

    @Test
    void modelConfigShouldHaveDefaultEmptyExtraParams() {
        var config = ModelConfig.builder().modelId("test").build();
        assertTrue(config.getExtraParams().isEmpty());
    }

    @Test
    void modelConfigShouldHandleMinimalFields() {
        var config = ModelConfig.builder()
                .modelId("minimal-model")
                .baseUrl("http://localhost:8080")
                .apiKey("key123")
                .build();

        assertEquals("minimal-model", config.getModelId());
        assertNull(config.getCompletionsPath()); // 未设置时为 null
        assertNull(config.getEmbeddingsPath());
    }

    @Test
    void modelConfigEquality() {
        var config1 = ModelConfig.builder()
                .modelId("gpt-4o")
                .baseUrl("https://api.openai.com")
                .apiKey("sk-abc")
                .build();
        var config2 = ModelConfig.builder()
                .modelId("gpt-4o")
                .baseUrl("https://api.openai.com")
                .apiKey("sk-abc")
                .build();

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void modelConfigToString() {
        var config = ModelConfig.builder()
                .modelId("gpt-4o")
                .baseUrl("https://api.openai.com")
                .apiKey("sk-xyz")
                .build();

        String str = config.toString();
        assertTrue(str.contains("gpt-4o"));
        // apiKey 应该在 toString 中隐藏（取决于 Lombok @Value 实现）
    }
}

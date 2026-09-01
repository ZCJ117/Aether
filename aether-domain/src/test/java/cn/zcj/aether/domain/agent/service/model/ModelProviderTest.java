package cn.zcj.aether.domain.agent.service.model;

import cn.zcj.aether.domain.agent.service.model.impl.AnthropicProvider;
import cn.zcj.aether.domain.agent.service.model.impl.DashScopeProvider;
import cn.zcj.aether.domain.agent.service.model.impl.OpenAIProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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

    private ModelConfig sampleConfig() {
        return ModelConfig.builder()
                .modelId("claude-sonnet-4-6")
                .baseUrl("https://api.anthropic.com")
                .apiKey("sk-test")
                .build();
    }

    @Test
    void httpRequestFactorySeamAppliesTimeouts() throws Exception {
        OpenAIProvider provider = new OpenAIProvider(1111, 2222);
        SimpleClientHttpRequestFactory f = provider.httpRequestFactory(1111, 2222);
        assertEquals(1111, intField(f, "connectTimeout"));
        assertEquals(2222, intField(f, "readTimeout"));
    }

    /** 读取 SimpleClientHttpRequestFactory 私有 int 字段（Spring 6.2 无 getter） */
    private static int intField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    @Test
    void buildOpenAiApiWithTimeoutsReturnsApi() {
        OpenAIProvider provider = new OpenAIProvider(1111, 2222);
        OpenAiApi api = provider.buildOpenAiApi(sampleConfig(), 1111, 2222);
        assertNotNull(api);
    }

    @Test
    void anthropicProviderUsesConfiguredTimeouts() {
        AnthropicProvider p = new AnthropicProvider(1111, 2222);
        assertEquals(1111, p.connectTimeoutMs());
        assertEquals(2222, p.readTimeoutMs());
        assertNotNull(p.createChatModel(sampleConfig()));
    }

    @Test
    void dashScopeProviderUsesConfiguredTimeouts() {
        DashScopeProvider p = new DashScopeProvider(1111, 2222);
        assertEquals(1111, p.connectTimeoutMs());
        assertEquals(2222, p.readTimeoutMs());
        assertNotNull(p.createChatModel(sampleConfig()));
    }

    @Test
    void openAiProviderUsesConfiguredTimeouts() {
        OpenAIProvider p = new OpenAIProvider(1111, 2222);
        assertEquals(1111, p.connectTimeoutMs());
        assertEquals(2222, p.readTimeoutMs());
        assertNotNull(p.createChatModel(sampleConfig()));
    }
}

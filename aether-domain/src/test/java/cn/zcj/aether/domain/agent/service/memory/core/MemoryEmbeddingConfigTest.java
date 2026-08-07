package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.ConditionContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryEmbeddingConfigTest {

    @Test
    void conditionRequiresNonBlankBaseUrl() {
        ConditionContext ctx = mock(ConditionContext.class);
        Environment env = mock(Environment.class);
        when(ctx.getEnvironment()).thenReturn(env);
        var cond = new MemoryEmbeddingConfig.MemoryEmbeddingConfiguredCondition();

        when(env.getProperty("aether.memory.embedding.base-url")).thenReturn(null);
        assertFalse(cond.matches(ctx, null));
        when(env.getProperty("aether.memory.embedding.base-url")).thenReturn("   ");
        assertFalse(cond.matches(ctx, null));
        when(env.getProperty("aether.memory.embedding.base-url")).thenReturn("http://localhost:11434");
        assertTrue(cond.matches(ctx, null));
    }

    @Test
    void buildsOpenAiEmbeddingModelFromConfig() {
        MemoryProperties props = new MemoryProperties();
        props.getEmbedding().setBaseUrl("http://localhost:11434");
        props.getEmbedding().setApiKey("key");
        props.getEmbedding().setPath("v1/embeddings");
        props.getEmbedding().setModel("bge-m3");

        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("openai");
        // 测试类路径存在 wrench fat-jar 遮蔽 Jackson2ObjectMapperBuilder.yaml()，
        // 构造真实 OpenAiApi 会 NoSuchMethodError；用 mock 代替，只验证配置透传。
        when(provider.buildOpenAiApi(any(ModelConfig.class))).thenReturn(mock(OpenAiApi.class));
        ModelProviderRegistry registry = mock(ModelProviderRegistry.class);
        when(registry.getAllProviders()).thenReturn(List.of(provider));

        EmbeddingModel model = new MemoryEmbeddingConfig().memoryEmbeddingModel(props, registry);

        assertNotNull(model);
        ArgumentCaptor<ModelConfig> captor = ArgumentCaptor.forClass(ModelConfig.class);
        verify(provider).buildOpenAiApi(captor.capture());
        assertEquals("http://localhost:11434", captor.getValue().getBaseUrl());
        assertEquals("key", captor.getValue().getApiKey());
    }
}

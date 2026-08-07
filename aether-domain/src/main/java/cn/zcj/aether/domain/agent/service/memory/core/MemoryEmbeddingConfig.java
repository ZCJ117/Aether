package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.lang.Nullable;

/**
 * 记忆 Embedding 模型装配。
 *
 * <p>复用 {@link ModelProvider#buildOpenAiApi} 的超时双通道约定；
 * 未配置 {@code aether.memory.embedding.base-url} 时不注册 bean（语义检索降级为时间排序）。</p>
 */
@Slf4j
@Configuration
public class MemoryEmbeddingConfig {

    @Bean(name = "memoryEmbeddingModel")
    @Conditional(MemoryEmbeddingConfiguredCondition.class)
    @Nullable
    public EmbeddingModel memoryEmbeddingModel(MemoryProperties props, ModelProviderRegistry registry) {
        var cfg = props.getEmbedding();
        ModelConfig modelConfig = ModelConfig.builder()
            .baseUrl(cfg.getBaseUrl())
            .apiKey(cfg.getApiKey())
            .embeddingsPath(cfg.getPath() == null || cfg.getPath().isBlank() ? "v1/embeddings" : cfg.getPath())
            .build();

        ModelProvider provider = registry.getAllProviders().stream()
            .filter(p -> "openai".equals(p.providerName()))
            .findFirst()
            .orElseGet(() -> registry.getAllProviders().isEmpty() ? null : registry.getAllProviders().get(0));
        if (provider == null) {
            log.warn("无可用 ModelProvider，记忆 embedding 不可用（语义检索降级）");
            return null;
        }
        OpenAiApi api = provider.buildOpenAiApi(modelConfig);
        String model = cfg.getModel() == null || cfg.getModel().isBlank() ? "bge-m3" : cfg.getModel();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().model(model).build();
        log.info("记忆 Embedding 装配完成: baseUrl={}, model={}, dim={}",
            cfg.getBaseUrl(), model, cfg.getDimension());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    /** 条件：aether.memory.embedding.base-url 非空才装配 EmbeddingModel */
    public static class MemoryEmbeddingConfiguredCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String baseUrl = context.getEnvironment().getProperty("aether.memory.embedding.base-url");
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}

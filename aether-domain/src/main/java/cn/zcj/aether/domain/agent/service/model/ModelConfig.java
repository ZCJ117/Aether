package cn.zcj.aether.domain.agent.service.model;

import lombok.Builder;
import lombok.Value;

/**
 * 模型配置，从 YAML 的 ai-api + chat-model 节点映射。
 */
@Value
@Builder
public class ModelConfig {
    /** 模型 ID（如 "gpt-4o"、"claude-sonnet-4-6"、"deepseek-chat"） */
    String modelId;

    /** API Base URL */
    String baseUrl;

    /** API Key */
    String apiKey;

    /** Completions 路径（可选，覆盖 Provider 默认值） */
    String completionsPath;

    /** Embeddings 路径（可选） */
    String embeddingsPath;

    /** 额外的 Provider 特定参数 */
    @Builder.Default
    java.util.Map<String, Object> extraParams = java.util.Map.of();
}

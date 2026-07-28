package cn.zcj.aether.domain.agent.service.model;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型配置，从 YAML 的 ai-api + chat-model 节点映射。
 *
 * P1 扩展：新增 maxAttempts / initialBackoff / maxBackoff / fallbackModels 字段，
 * 对齐 hermes/AgentScope 的分层重试 + fallback 配置。
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

    /** P1: 最大重试次数（默认 3） */
    @Builder.Default
    Integer maxAttempts = 3;

    /** P1: 初始退避时间（默认 2s） */
    @Builder.Default
    Duration initialBackoff = Duration.ofSeconds(2);

    /** P1: 最大退避时间（默认 30s） */
    @Builder.Default
    Duration maxBackoff = Duration.ofSeconds(30);

    /** P1: 有序 fallback 模型链，元素为 modelId */
    @Builder.Default
    List<String> fallbackModels = List.of();

    /** 额外的 Provider 特定参数 */
    @Builder.Default
    Map<String, Object> extraParams = Map.of();
}

package cn.zcj.aether.domain.agent.service.model.failover;

import lombok.Builder;
import lombok.Value;

/**
 * 模型路由 —— 描述一个可用的模型端点。
 *
 * 在 YAML 中 fallbackModels 列表的每个元素解析为一个 ModelRoute。
 * ResilientChatModelExecutor 在 fallback 切换时按序尝试链上的每个路由。
 */
@Value
@Builder
public class ModelRoute {

    /** 模型 ID（如 "deepseek-chat"、"gpt-4o"） */
    String modelId;

    /** Provider 名称（如 "openai"、"anthropic"、"dashscope"） */
    String provider;

    /** API Base URL */
    String baseUrl;

    /** API Key */
    String apiKey;

    /** Completions 路径 */
    String completionsPath;

    /** 此路由的独立冷却时间戳（epoch millis），限流切换后 60s 内禁止切回 */
    // 【容错】独立冷却：限流触发后一段时间内禁止切回该路由，避免对已限流端点空转重试
    long cooldownUntil;

    /** 标记此路由已冷却 */
    public ModelRoute withCooldown(long cooldownDurationMs) {
        return ModelRoute.builder()
                .modelId(this.modelId)
                .provider(this.provider)
                .baseUrl(this.baseUrl)
                .apiKey(this.apiKey)
                .completionsPath(this.completionsPath)
                .cooldownUntil(System.currentTimeMillis() + cooldownDurationMs)
                .build();
    }

    /** 是否处于冷却期 */
    public boolean isInCooldown() {
        return System.currentTimeMillis() < cooldownUntil;
    }

    /** 冷却剩余秒数 */
    public long cooldownRemainingSeconds() {
        long remaining = cooldownUntil - System.currentTimeMillis();
        return remaining > 0 ? remaining / 1000 : 0;
    }
}

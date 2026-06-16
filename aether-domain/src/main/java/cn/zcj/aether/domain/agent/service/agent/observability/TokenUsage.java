package cn.zcj.aether.domain.agent.service.agent.observability;

import lombok.Builder;
import lombok.Value;

/**
 * Token 使用记录。
 */
@Value
@Builder
public class TokenUsage {
    int inputTokens;
    int outputTokens;
    int cacheTokens;          // 缓存命中的 token 数
    double costUsd;           // 成本（美元）

    public int totalTokens() { return inputTokens + outputTokens; }

    public static TokenUsage empty() {
        return TokenUsage.builder().inputTokens(0).outputTokens(0).cacheTokens(0).costUsd(0).build();
    }
}

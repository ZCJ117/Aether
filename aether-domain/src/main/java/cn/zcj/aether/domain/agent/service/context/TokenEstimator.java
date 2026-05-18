package cn.zcj.aether.domain.agent.service.context;

import org.springframework.stereotype.Service;

/**
 * Token估算器
 * 简单估算: ~3.5 chars ≈ 1 token (中英文混合)
 */
@Service
public class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5;

    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /** 获取模型上下文窗口大小 */
    public int getContextWindow(String modelName) {
        if (modelName == null) return 128_000;
        return switch (modelName) {
            case "claude-sonnet-4-6", "claude-opus-4-7" -> 200_000;
            case "claude-haiku-4-5" -> 200_000;
            default -> 128_000;
        };
    }
}

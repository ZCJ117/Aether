package cn.zcj.aether.domain.agent.service.context;

import org.springframework.stereotype.Service;

/**
 * Token 估算器。
 * 分角色字符比估算（不做真正的 tokenizer，保持简单）。
 *
 * 英文普通文本: ~3.0 chars/token
 * 代码/JSON 偏多: ~2.5 chars/token
 * 中文偏多: ~4.0 chars/token
 */
@Service
public class TokenEstimator {

    private static final double CHARS_PER_TOKEN_DEFAULT = 3.5;
    private static final double CHARS_PER_TOKEN_CODE = 2.5;
    private static final double CHARS_PER_TOKEN_ENGLISH = 3.0;
    private static final double CHARS_PER_TOKEN_CHINESE = 4.0;

    /** 简单估算（向后兼容，默认 3.5 chars/token） */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN_DEFAULT);
    }

    /** 按消息角色估算 */
    public int estimate(String text, String role) {
        if (text == null || text.isEmpty()) return 0;
        double ratio = switch (role) {
            case "system", "assistant" -> CHARS_PER_TOKEN_ENGLISH;
            case "tool_result" -> CHARS_PER_TOKEN_CODE;
            case "user" -> CHARS_PER_TOKEN_CHINESE;
            default -> CHARS_PER_TOKEN_DEFAULT;
        };
        return (int) Math.ceil(text.length() / ratio);
    }

    /** 获取模型上下文窗口大小 */
    public int getContextWindow(String modelName) {
        if (modelName == null) return 128_000;
        String lower = modelName.toLowerCase();
        if (lower.contains("claude")) return 200_000;
        if (lower.contains("gpt-4")) return 128_000;
        if (lower.contains("gpt-3.5")) return 16_000;
        if (lower.contains("deepseek")) return 128_000;
        return 128_000;
    }
}

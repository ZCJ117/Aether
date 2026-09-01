package cn.zcj.aether.domain.agent.service.context;

import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * Token 估算器。
 * 分角色字符比估算（不做真正的 tokenizer，保持简单）。
 *
 * <p>估算系数通过 {@link ModelContextWindowRegistry} 外置可配，
 * 上下文窗口查询同样委托给 Registry（精确匹配 → 关键字包含 → 默认 128k）。</p>
 *
 * <p>英文普通文本: ~3.0 chars/token<br>
 * 代码/JSON 偏多: ~2.5 chars/token<br>
 * 中文偏多: ~4.0 chars/token<br>
 * 默认: ~3.5 chars/token</p>
 */
@Service
public class TokenEstimator {

    @Resource
    private ModelContextWindowRegistry windowRegistry;

    /** 简单估算（向后兼容，使用 Registry 默认系数） */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double ratio = windowRegistry != null
                ? windowRegistry.getCharsPerTokenDefault()
                : 3.5;
        return (int) Math.ceil(text.length() / ratio);
    }

    /** 按消息角色估算 */
    public int estimate(String text, String role) {
        if (text == null || text.isEmpty()) return 0;
        if (windowRegistry == null) {
            return (int) Math.ceil(text.length() / 3.5);
        }
        double ratio = switch (role) {
            case "system", "assistant" -> windowRegistry.getCharsPerTokenEnglish();
            case "tool_result" -> windowRegistry.getCharsPerTokenCode();
            case "user" -> windowRegistry.getCharsPerTokenChinese();
            default -> windowRegistry.getCharsPerTokenDefault();
        };
        return (int) Math.ceil(text.length() / ratio);
    }

    /**
     * 获取模型上下文窗口大小。
     *
     * <p>O8: 统一走 {@link ModelContextWindowRegistry}（精确匹配 → 关键字包含 → 默认 128k），
     * 删除本类的兜底硬编码窗口表，消除双份窗口表漂移；Registry 未注入时回退默认常量。</p>
     */
    public int getContextWindow(String modelName) {
        if (windowRegistry != null) {
            return windowRegistry.getContextWindow(modelName);
        }
        return ModelContextWindowRegistry.DEFAULT_CONTEXT_WINDOW;
    }
}

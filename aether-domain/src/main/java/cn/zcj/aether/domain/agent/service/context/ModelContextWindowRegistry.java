package cn.zcj.aether.domain.agent.service.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型上下文窗口注册表 — YAML 可配 + 关键字回退 + 默认 128k。
 *
 * <p>查找顺序：</p>
 * <ol>
 *   <li>精确 modelId 匹配（通过 {@link #register(String, int)} 注册或 YAML 装配）</li>
 *   <li>关键字包含匹配（如 "gpt-4" 包含子串匹配 → 128k）</li>
 *   <li>默认 128,000 tokens</li>
 * </ol>
 *
 * <p>字符比估算系数同样可外置配置，默认值与 TokenEstimator 现有硬编码一致。</p>
 *
 * <p>来源：autogen TokenLimited / cc-haha getEffectiveContextWindowSize 的模式，
 * 消除 TokenEstimator.getContextWindow() 的硬编码问题。</p>
 *
 * <p><b>【架构亮点 · 上下文工程与成本治理】</b><br>
 * 面试举证点：①YAML 可配 + 关键字回退 + 默认 128k 三级查找（:60-82）消除硬编码窗口；<br>
 * ②默认 128,000 tokens（:29），未知模型不再误判窗口；<br>
 * ③关键字表 "claude"→200k 等（:46-49）对模型名做子串匹配，无需精确注册即可正确估算上下文容量，是 TokenBudget 三层预算与 ContextManager 压缩触发的前提。</p>
 */
@Slf4j
@Service
public class ModelContextWindowRegistry {

    /** 默认上下文窗口：128k tokens（当前行业主流模型的默认值） */
    // 【上下文工程】未知模型统一兜底 128k，避免窗口误判导致预算/压缩阈值失真
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /** 精确 modelId → contextWindow 映射 */
    private final Map<String, Integer> exactMap = new ConcurrentHashMap<>();

    /** 关键字包含 → contextWindow 映射（兜底匹配） */
    private final Map<String, Integer> keywordMap = new ConcurrentHashMap<>();

    // ========== 字符比估算系数（可外置配置） ==========

    private double charsPerTokenDefault = 3.5;
    private double charsPerTokenCode = 2.5;
    private double charsPerTokenEnglish = 3.0;
    private double charsPerTokenChinese = 4.0;

    public ModelContextWindowRegistry() {
        // 初始化关键字兜底表（对齐原 TokenEstimator.getContextWindow() 硬编码表）
        keywordMap.put("claude", 200_000);
        keywordMap.put("gpt-4", 128_000);
        keywordMap.put("gpt-3.5", 16_000);
        keywordMap.put("deepseek", 128_000);
    }

    // ========== 上下文窗口查询 ==========

    /**
     * 获取指定模型的上下文窗口大小。
     *
     * @param modelName 模型名称（可能为 null）
     * @return 上下文窗口 token 数
     */
    public int getContextWindow(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return DEFAULT_CONTEXT_WINDOW;
        }

        // 1. 精确匹配
        String lower = modelName.toLowerCase();
        Integer exact = exactMap.get(lower);
        if (exact != null) {
            return exact;
        }

        // 2. 关键字包含匹配
        // 【上下文工程】关键字兜底：模型名含 "claude" 即 200k、含 "gpt-4" 即 128k，无需精确注册
        for (Map.Entry<String, Integer> entry : keywordMap.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 3. 默认
        log.debug("未匹配模型窗口配置: modelName={}, 使用默认 {}k", modelName, DEFAULT_CONTEXT_WINDOW / 1000);
        return DEFAULT_CONTEXT_WINDOW;
    }

    // ========== 注册方法（供 YAML 装配或代码注册） ==========

    /**
     * 注册精确模型 ID 的上下文窗口。
     *
     * @param modelId       精确模型 ID（如 "deepseek-v4-flash"）
     * @param contextWindow 上下文窗口大小（tokens）
     */
    public void register(String modelId, int contextWindow) {
        if (modelId == null || modelId.isBlank()) return;
        String key = modelId.toLowerCase();
        exactMap.put(key, contextWindow);
        log.info("注册模型上下文窗口: modelId={}, window={}k", modelId, contextWindow / 1000);
    }

    /**
     * 注册关键字匹配的上下文窗口（兜底规则）。
     *
     * @param keyword       关键字（如 "claude"）
     * @param contextWindow 上下文窗口大小（tokens）
     */
    public void registerKeyword(String keyword, int contextWindow) {
        if (keyword == null || keyword.isBlank()) return;
        keywordMap.put(keyword.toLowerCase(), contextWindow);
    }

    /**
     * 批量注册精确模型 ID（供 YAML 装配用）。
     */
    public void registerAll(Map<String, Integer> modelWindows) {
        if (modelWindows == null) return;
        modelWindows.forEach(this::register);
    }

    // ========== 字符比系数（Getter/Setter） ==========

    public double getCharsPerTokenDefault() { return charsPerTokenDefault; }
    public void setCharsPerTokenDefault(double v) { this.charsPerTokenDefault = v; }

    public double getCharsPerTokenCode() { return charsPerTokenCode; }
    public void setCharsPerTokenCode(double v) { this.charsPerTokenCode = v; }

    public double getCharsPerTokenEnglish() { return charsPerTokenEnglish; }
    public void setCharsPerTokenEnglish(double v) { this.charsPerTokenEnglish = v; }

    public double getCharsPerTokenChinese() { return charsPerTokenChinese; }
    public void setCharsPerTokenChinese(double v) { this.charsPerTokenChinese = v; }
}

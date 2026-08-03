package cn.zcj.aether.domain.agent.service.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型定价注册表 — M7 成本熔断基础组件。
 *
 * <p>支持精确匹配和通配符匹配（"deepseek-*" 匹配所有 deepseek 系列模型）。
 * 定价数据通过 {@link PricingProperties} 从 YAML 装配注入。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>精确 modelId 匹配</li>
 *   <li>通配符模式匹配（如 "gpt-*" → "gpt-4o"、"gpt-4o-mini"）</li>
 *   <li>默认定价（$0.001/1K input, $0.002/1K output — 保守高估）</li>
 * </ol>
 */
@Slf4j
@Component
public class ModelPricingRegistry {

    /** 精确匹配映射 */
    private final Map<String, ModelPricing> exactMap = new ConcurrentHashMap<>();

    /** 通配符模式列表（按注册顺序匹配，先注册先匹配） */
    private final Map<String, ModelPricing> wildcardMap = new ConcurrentHashMap<>();

    public ModelPricingRegistry() {
        // 默认定价（保守高估），避免未配置模型时成本估计为零
    }

    /**
     * 注册精确模型定价。
     */
    public void register(String modelId, double inputCostPer1k, double outputCostPer1k) {
        if (modelId == null || modelId.isBlank()) return;
        exactMap.put(modelId.toLowerCase(), new ModelPricing(modelId, inputCostPer1k, outputCostPer1k));
    }

    /**
     * 注册通配符模型定价（如 "deepseek-*": $0.14/1M input）。
     */
    public void registerWildcard(String pattern, double inputCostPer1k, double outputCostPer1k) {
        if (pattern == null || pattern.isBlank()) return;
        String key = pattern.toLowerCase();
        if (key.endsWith("*")) {
            key = key.substring(0, key.length() - 1);
        }
        wildcardMap.put(key, new ModelPricing(pattern, inputCostPer1k, outputCostPer1k));
    }

    /**
     * 批量注册（从 YAML PricingProperties 注入）。
     */
    public void registerAll(List<PricingProperties.ModelPrice> prices) {
        if (prices == null) return;
        for (PricingProperties.ModelPrice p : prices) {
            if (p.getModelId() == null) continue;
            if (p.getModelId().contains("*")) {
                registerWildcard(p.getModelId(), p.getInputCostPer1k(), p.getOutputCostPer1k());
            } else {
                register(p.getModelId(), p.getInputCostPer1k(), p.getOutputCostPer1k());
            }
        }
        log.info("ModelPricingRegistry: 已注册 {} 个模型定价", prices.size());
    }

    /**
     * 查找模型的定价信息。
     *
     * @param modelId 模型 ID
     * @return 匹配的定价，未找到则返回默认保守定价
     */
    public ModelPricing lookup(String modelId) {
        if (modelId == null) return defaultPricing();

        String lower = modelId.toLowerCase();

        // 1. 精确匹配
        ModelPricing exact = exactMap.get(lower);
        if (exact != null) return exact;

        // 2. 通配符匹配（"deepseek-*" → "deepseek-chat"）
        for (Map.Entry<String, ModelPricing> entry : wildcardMap.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 3. 默认保守定价
        return defaultPricing();
    }

    private ModelPricing defaultPricing() {
        return new ModelPricing("default", 0.001, 0.002);
    }
}

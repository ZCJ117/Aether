package cn.zcj.aether.domain.agent.service.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型定价配置属性 — M7 成本熔断 YAML 绑定。
 *
 * <p>YAML 配置示例：
 * <pre>{@code
 * aether:
 *   pricing:
 *     models:
 *       - model-id: "deepseek-*"
 *         input-cost-per-1k: 0.00014
 *         output-cost-per-1k: 0.00028
 *       - model-id: "gpt-4o"
 *         input-cost-per-1k: 0.0025
 *         output-cost-per-1k: 0.01
 * }</pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aether.pricing")
public class PricingProperties {

    /** 模型定价列表 */
    private List<ModelPrice> models = new ArrayList<>();

    @Data
    public static class ModelPrice {
        /** 模型 ID（支持 "*" 通配符） */
        private String modelId;
        /** 每千输入 token 美元成本 */
        private double inputCostPer1k;
        /** 每千输出 token 美元成本 */
        private double outputCostPer1k;
    }
}

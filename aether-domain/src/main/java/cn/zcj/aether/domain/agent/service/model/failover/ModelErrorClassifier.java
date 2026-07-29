package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * 模型错误分类器 SPI（domain 层端口）。
 *
 * 对齐 hermes-agent error_classifier.py classify_api_error() 优先级管线：
 * ① 消息模式匹配（content_policy / context_length 等关键词最高优先级）
 * ② HTTP 状态码分类
 * ③ 传输异常分类
 * ④ UNKNOWN 兜底（retryable=true，保守策略）
 *
 * infrastructure 层提供 {@code DefaultModelErrorClassifier} 实现。
 * 各 ModelProvider 可通过重写 {@link cn.zcj.aether.domain.agent.service.model.ModelProvider#classifyError}
 * 提供 Provider 特有的分类逻辑。
 */
public interface ModelErrorClassifier {

    /**
     * 对 API 调用异常进行分类。
     *
     * @param error    原始异常（Spring AI 异常经 NestedExceptionUtils 递归解包）
     * @param provider 当前 Provider 名称（如 "openai"、"anthropic"、"dashscope"）
     * @param model    当前模型 ID
     * @return 结构化的分类结果（含恢复动作提示）
     */
    ClassifiedError classify(Throwable error, String provider, String model);
}

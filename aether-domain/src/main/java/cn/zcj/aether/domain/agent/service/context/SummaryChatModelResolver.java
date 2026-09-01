package cn.zcj.aether.domain.agent.service.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * O7 增强: 摘要 ChatModel 显式选取器。
 *
 * <p>缺陷背景（01 文档 D7）：{@code ContextManager}/{@code ChunkSummarizer} 按类型注入
 * {@code ChatModel}，容器存在多个 {@code chatModel-{name}} bean 时选取不确定，
 * 摘要模型与对话模型可能不同导致成本归属错误。</p>
 *
 * <p>选取规则（确定性）：</p>
 * <ol>
 *   <li>配置 {@code aether.context.compaction.summary-model=<model>} →
 *       优先查找 bean {@code chatModel-<model>}（或已带 chatModel 前缀的 bean 名）；</li>
 *   <li>未配置或未命中 → 回退全局默认 bean {@code chatModel}（ChatModelNode 运行时注册）；</li>
 *   <li>两者皆缺 → 抛 {@link IllegalStateException}（fail-fast，由调用方降级拼接摘要）。</li>
 * </ol>
 *
 * <p>由于 {@code chatModel} bean 由 ChatModelNode 在运行期动态注册，无法构造注入；
 * 本组件经 {@link ListableBeanFactory} 按名解析，替代原先散落各处的
 * {@code @Lazy @Resource ChatModel} 字段注入，统一摘要模型选取口径。</p>
 */
@Slf4j
@Component
public class SummaryChatModelResolver {

    private final ListableBeanFactory beanFactory;

    @Value("${aether.context.compaction.summary-model:}")
    private String summaryModel;

    public SummaryChatModelResolver(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 解析摘要所用 ChatModel。
     *
     * @return 确定选取的 ChatModel（非 null）
     * @throws IllegalStateException 无任何可用 ChatModel bean 时
     */
    public ChatModel resolve() {
        // 1) 显式配置的摘要模型优先
        if (summaryModel != null && !summaryModel.isBlank()) {
            String beanName = summaryModel.startsWith("chatModel")
                    ? summaryModel
                    : "chatModel-" + summaryModel;
            if (beanFactory.containsBean(beanName)) {
                ChatModel model = beanFactory.getBean(beanName, ChatModel.class);
                log.debug("摘要模型（显式配置）: {} → bean {}", summaryModel, beanName);
                return model;
            }
            log.warn("配置的摘要模型 bean {} 不存在，回退默认 chatModel", beanName);
        }

        // 2) 全局默认 chatModel
        if (beanFactory.containsBean("chatModel")) {
            return beanFactory.getBean("chatModel", ChatModel.class);
        }

        throw new IllegalStateException(
                "无可用 ChatModel（chatModel bean 未注册且未配置 aether.context.compaction.summary-model）");
    }

    /** 当前解析出的摘要模型 bean 名（监控/测试用）。 */
    public String resolvedBeanName() {
        if (summaryModel != null && !summaryModel.isBlank()) {
            String beanName = summaryModel.startsWith("chatModel")
                    ? summaryModel
                    : "chatModel-" + summaryModel;
            if (beanFactory.containsBean(beanName)) {
                return beanName;
            }
        }
        return "chatModel";
    }
}

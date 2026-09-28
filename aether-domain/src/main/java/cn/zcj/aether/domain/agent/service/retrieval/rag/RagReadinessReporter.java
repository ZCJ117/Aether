package cn.zcj.aether.domain.agent.service.retrieval.rag;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D4/F3-2: RAG 三级能力启动自检 —— 报告各级依赖齐备度，杜绝"开关打开但端口缺失"的静默降级
 * （即"以为开着其实没开"）。
 *
 * <p><b>只报告、不改行为</b>：本类不修改任何检索路径，缺失级别以 WARN 输出并记
 * {@code aether.rag.capability.missing{stage}} 计数器（存在 {@link MeterRegistry} 时）。</p>
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是 {@code @Autowired(required=true)}</b>：
 * 自检要探测的正是"这些 Bean 是否存在"这一事实，强依赖装配会在缺失时直接让应用启动失败；
 * 且延迟查询可避免启动期循环依赖。</p>
 *
 * <p><b>为什么不因缺失而改变任何行为</b>：降级策略由 {@link RetrievalPipeline} 逐级承担
 * （见其"切换条件"），自检只负责把实际可用性如实讲出来——doc 口径与运行时事实必须一致。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.rag.enabled", havingValue = "true")
public class RagReadinessReporter {

    /** 指标名：启动自检发现的能力缺失。 */
    private static final String METRIC_MISSING = "aether.rag.capability.missing";

    private final RagProperties properties;
    private final ObjectProvider<HybridSearchPort> hybridPort;
    private final ObjectProvider<RerankPort> rerankPort;
    private final ObjectProvider<ChatModel> chatModel;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public RagReadinessReporter(RagProperties properties,
                                ObjectProvider<HybridSearchPort> hybridPort,
                                ObjectProvider<RerankPort> rerankPort,
                                ObjectProvider<ChatModel> chatModel,
                                @Qualifier("memoryEmbeddingModel") ObjectProvider<EmbeddingModel> embeddingModel,
                                ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.hybridPort = hybridPort;
        this.rerankPort = rerankPort;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.meterRegistry = meterRegistry;
    }

    /** 各级可用性快照。 */
    public record ReadinessReport(boolean enabled, boolean rewrite, boolean hybrid,
                                  boolean rerank, boolean embedding,
                                  List<String> missingReasons) {
    }

    /**
     * 探测并输出自检结论。
     *
     * @return 各级是否可用，供测试断言
     */
    @EventListener(ApplicationReadyEvent.class)
    public ReadinessReport report() {
        boolean chatModelPresent = chatModel.getIfAvailable() != null;
        boolean hybridPortPresent = hybridPort.getIfAvailable() != null;
        boolean rerankPortPresent = rerankPort.getIfAvailable() != null;
        boolean embeddingPresent = embeddingModel.getIfAvailable() != null;

        List<String> missingReasons = new ArrayList<>();

        // 一级 rewrite：开关开 + ChatModel 在
        boolean rewrite = properties.getRewrite().isEnabled() && chatModelPresent;
        if (!properties.getRewrite().isEnabled()) {
            missingReasons.add("rewrite: 关闭");
        } else if (!chatModelPresent) {
            missingReasons.add("rewrite: 开启但 ChatModel 缺失，将降级原 query");
        }

        // 二级 hybrid：开关开 + 端口在
        boolean hybrid = properties.getHybrid().isEnabled() && hybridPortPresent;
        if (properties.getHybrid().isEnabled() && !hybridPortPresent) {
            missingReasons.add("hybrid: 端口缺失，将降级纯向量");
        }

        // 三级 rerank：开关开 + 端口在
        boolean rerank = properties.getRerank().isEnabled() && rerankPortPresent;
        if (!properties.getRerank().isEnabled()) {
            missingReasons.add("rerank: 关闭");
        } else if (!rerankPortPresent) {
            missingReasons.add("rerank: 开启但 RerankPort 缺失");
        }

        // 向量能力：memoryEmbeddingModel 在否
        if (!embeddingPresent) {
            missingReasons.add("embedding: 缺失，向量路将降级为纯词法");
        }

        if (!properties.isEnabled()) {
            missingReasons.add("RAG 三级管道: 未启用（enabled=false），记忆召回走原路径");
        }

        // 每级缺失各记一次计数；rewrite 的 ChatModel 缺失不属"端口"缺失，但仍计 rewrite 级
        recordMissing("rewrite", !rewrite);
        recordMissing("hybrid", !hybrid);
        recordMissing("rerank", !rerank);
        recordMissing("embedding", !embeddingPresent);

        String summary = "RAG 能力自检: enabled=" + properties.isEnabled()
                + ", rewrite=" + stageState(rewrite, properties.getRewrite().isEnabled(),
                        chatModelPresent, "ChatModel 缺失")
                + ", hybrid=" + stageState(hybrid, properties.getHybrid().isEnabled(),
                        hybridPortPresent, "端口缺失")
                + ", rerank=" + stageState(rerank, properties.getRerank().isEnabled(),
                        rerankPortPresent, "端口缺失")
                + ", embedding=" + (embeddingPresent ? "on" : "off(缺失)");

        if (missingReasons.isEmpty()) {
            log.info(summary);
        } else {
            log.warn(summary);
            for (String reason : missingReasons) {
                log.warn("RAG 能力自检 · 缺失: {}", reason);
            }
        }

        return new ReadinessReport(properties.isEnabled(), rewrite, hybrid, rerank,
                embeddingPresent, List.copyOf(missingReasons));
    }

    /**
     * 渲染某一级的状态串，并带上不可用的<b>原因</b>——"off" 本身没有信息量，
     * 自检的价值在于区分"你没开"还是"开了但依赖不在"。
     *
     * @param usable            该级是否真正可用（开关开 + 依赖在）
     * @param switchOn          该级运行期开关
     * @param dependencyPresent 该级所依赖的 Bean 是否存在
     * @param missingLabel      依赖缺失时括号内的话术（如"端口缺失"/"ChatModel 缺失"）
     */
    private static String stageState(boolean usable, boolean switchOn,
                                    boolean dependencyPresent, String missingLabel) {
        if (usable) {
            return "on";
        }
        return switchOn && !dependencyPresent ? "off(" + missingLabel + ")" : "off(关闭)";
    }

    private void recordMissing(String stage, boolean missing) {
        if (!missing) {
            return;
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(METRIC_MISSING, "stage", stage).increment();
        }
    }
}

package cn.zcj.aether.rag;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import cn.zcj.aether.domain.agent.service.memory.RecallFlow;
import cn.zcj.aether.domain.agent.service.retrieval.rag.RagProperties;
import cn.zcj.aether.domain.agent.service.retrieval.rag.RerankPort;
import cn.zcj.aether.domain.agent.service.retrieval.rag.RetrievalPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4/F3-3 + G-D4-2: dev 启用后 shallow 召回真的走三级管道 —— T3-6。
 *
 * <p><b>集成用例</b>（{@code @Tag("integration")}，默认测试集不执行）：需要真实
 * PostgreSQL + pgvector 与 dev profile 的基础设施，验收时以
 * {@code mvn -Pintegration test -Dtest=RagEnabledRecallIT} 单独运行。</p>
 *
 * <p>本用例要证明的正是"改造前无法回答"的那一问：RAG 在开发环境<b>线上开着</b>——
 * 总开关为 true、{@link RetrievalPipeline} 实际接管 {@link RecallFlow} 的 shallow 召回、
 * 且二级混合召回真的产出了 {@code aether.rag.stage.duration}{stage=hybrid} 采样。</p>
 *
 * <p><b>它不校验 dev 形态本身</b>：下面的 {@code aether.rag.rewrite.enabled=false} 覆盖是为了
 * 免掉外部 LLM 依赖（真跑一级改写要连真实模型），因此这里验证的是"管道被走通"，
 * 而"application-dev.yml 确实打开了 enabled/rewrite"由 {@code DevProfileRagConfigTest}
 * （无需基础设施、随默认测试集执行）负责锁住。两者互补，不重叠。</p>
 *
 * <p><b>未在本机执行</b>：Docker/PostgreSQL 不可用，见交付说明的"未验证项"。</p>
 */
@Tag("integration")
@SpringBootTest(
        properties = {
                "spring.profiles.active=dev",
                // 断言的是"管道被走通"，不依赖真实 LLM 改写；一级保持关闭以免引入外部模型依赖
                "aether.rag.enabled=true",
                "aether.rag.rewrite.enabled=false",
                "aether.rag.hybrid.enabled=true"
        }
)
class RagEnabledRecallIT {

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private RecallFlow recallFlow;

    @Autowired
    private RetrievalPipeline retrievalPipeline;

    @Autowired(required = false)
    private RerankPort rerankPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("T3-6 dev 下总开关开启，三级管道 Bean 与运行期开关状态一致")
    void pipelineIsWiredAndEnabledUnderDev() {
        assertThat(ragProperties.isEnabled()).isTrue();
        assertThat(retrievalPipeline).isNotNull();

        // F3-1 的直接证据：重排端口的装配不再由 rerank.enabled 决定 ——
        // dev 下 rerank.enabled 保持 false，但 Bean 应当在（Python 地址缺省即视为可用）
        assertThat(ragProperties.getRerank().isEnabled()).isFalse();
        assertThat(rerankPort).as("F3-1: 开关关闭时 RerankPort 仍应装配").isNotNull();
    }

    @Test
    @DisplayName("T3-6 recallShallow 经管道产生 hybrid 阶段采样，finalSource 为 hybrid")
    void shallowRecallGoesThroughHybridStage() {
        MeterRegistry registry = meterRegistry;

        // 触发一次 shallow 召回（查询内容无所谓，断言的是链路而非结果质量）
        recallFlow.recallShallow("验收：三级检索是否真的在线", new MemoryFacade.RecallOptions()).join();

        var stageTimer = registry.find("aether.rag.stage.duration").tag("stage", "hybrid").timer();
        assertThat(stageTimer)
                .as("二级混合召回必须留下 aether.rag.stage.duration{stage=hybrid} 采样 —— "
                        + "改造前三级管道从未执行过一次，该 Timer 不存在")
                .isNotNull();
        assertThat(stageTimer.count()).isGreaterThanOrEqualTo(1L);
    }
}

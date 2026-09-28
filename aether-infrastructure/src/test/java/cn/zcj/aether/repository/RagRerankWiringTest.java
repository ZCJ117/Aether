package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.retrieval.rag.RerankPort;
import cn.zcj.aether.domain.agent.service.tool.python.PythonServicePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * D4/F3-1: 重排端口的装配与运行开关解耦 —— T3-1。
 *
 * <p>改造前 {@code PythonReranker} 的条件是
 * {@code @ConditionalOnProperty(name="aether.rag.rerank.enabled", havingValue="true")}，
 * 而该开关默认 false，于是容器内恒无 {@code RerankPort} —— "三级检索"在装配层面只有两级。</p>
 *
 * <p>改造后条件改为基础设施可用性（{@code aether.python.doc-url}，缺省即视为可用）。
 * 本用例的核心断言是：<b>开关为 false 时 Bean 依然存在</b>（装配 ≠ 调用）。</p>
 */
class RagRerankWiringTest {

    /** PythonServicePort 是 PythonReranker 的构造依赖，替身即可（本用例不真的调用它）。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(PythonServicePort.class, () -> mock(PythonServicePort.class))
            .withUserConfiguration(PythonReranker.class);

    @Test
    @DisplayName("T3-1 rerank.enabled=false 时 RerankPort 仍装配（装配与开关解耦）")
    void rerankPortAssembledWhileRuntimeSwitchIsOff() {
        runner.withPropertyValues(
                        "aether.rag.rerank.enabled=false",
                        "aether.python.doc-url=http://localhost:8001")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RerankPort.class);
                    assertThat(ctx.getBean(RerankPort.class)).isInstanceOf(PythonReranker.class);
                });
    }

    @Test
    @DisplayName("T3-1 补充：Python 地址缺省时同样装配（@Value 会落到默认 localhost:8001）")
    void rerankPortAssembledWhenAddressKeyAbsent() {
        runner.withPropertyValues("aether.rag.rerank.enabled=false")
                .run(ctx -> {
                    assertThat(ctx.getEnvironment().containsProperty("aether.python.doc-url")).isFalse();
                    assertThat(ctx).hasSingleBean(RerankPort.class);
                });
    }

    @Test
    @DisplayName("T3-1 补充：rerank.enabled=true 时同样装配（开关不再影响装配）")
    void rerankPortAssembledWhenRuntimeSwitchIsOn() {
        runner.withPropertyValues("aether.rag.rerank.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(RerankPort.class));
    }

    @Test
    @DisplayName("T3-1 补充：显式关闭 Python 地址可阻止装配（保留关闭手段）")
    void rerankPortNotAssembledWhenPythonAddressDisabled() {
        runner.withPropertyValues("aether.python.doc-url=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RerankPort.class));
    }
}

package cn.zcj.aether.domain.agent.service.retrieval.rag;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * D4/F3-2: RAG 三级能力启动自检 —— T3-4 / T3-5。
 *
 * <p>自检的职责是"如实报告"：把各级依赖的真实齐备度讲出来，杜绝"开关开着但端口缺失"
 * 的静默降级。本用例覆盖各 Bean 存在/缺失组合下的报告字段与缺失文案。</p>
 */
class RagReadinessReporterTest {

    /** 构造一个只返回固定值（可为 null）的 ObjectProvider。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static RagProperties props(boolean enabled, boolean rewrite, boolean hybrid, boolean rerank) {
        RagProperties p = new RagProperties();
        p.setEnabled(enabled);
        p.getRewrite().setEnabled(rewrite);
        p.getHybrid().setEnabled(hybrid);
        p.getRerank().setEnabled(rerank);
        return p;
    }

    private static RagReadinessReporter reporter(RagProperties props, boolean hybridPort,
                                                 boolean rerankPort, boolean chatModel,
                                                 boolean embedding, SimpleMeterRegistry registry) {
        return new RagReadinessReporter(
                props,
                provider(hybridPort ? mock(HybridSearchPort.class) : null),
                provider(rerankPort ? mock(RerankPort.class) : null),
                provider(chatModel ? mock(ChatModel.class) : null),
                provider(embedding ? mock(EmbeddingModel.class) : null),
                provider(registry));
    }

    /** T3-4：依赖齐备时各级为 true，且无缺失文案。 */
    @Test
    @DisplayName("T3-4 依赖齐备时各级均可用且无缺失文案")
    void allDependenciesPresentYieldsAllTrue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, true), true, true, true, true, registry).report();

        assertTrue(report.enabled());
        assertTrue(report.rewrite());
        assertTrue(report.hybrid());
        assertTrue(report.rerank());
        assertTrue(report.embedding());
        assertTrue(report.missingReasons().isEmpty(),
                "无缺失时不应产生文案，实际: " + report.missingReasons());
        assertNull(registry.find("aether.rag.capability.missing").counter(),
                "无缺失时不应打 capability.missing 计数");
    }

    /** T3-4：dev 形态 —— rerank 开关关、端口在，如实报"关闭"而非"端口缺失"。 */
    @Test
    @DisplayName("T3-4 rerank 开关关闭时报告为不可用且文案为「关闭」")
    void rerankSwitchOffIsReportedAsOffNotMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, false), true, true, true, true, registry).report();

        assertFalse(report.rerank(), "rerank 开关关闭 → 该级不可用");
        assertTrue(report.missingReasons().stream().anyMatch(r -> r.contains("rerank: 关闭")),
                "应如实报告为「关闭」而不是伪装成端口缺失，实际: " + report.missingReasons());
        assertEquals(1.0, registry.get("aether.rag.capability.missing")
                .tag("stage", "rerank").counter().count());
    }

    /** T3-4：rerank 开关开但端口缺 → 报"RerankPort 缺失"。 */
    @Test
    @DisplayName("T3-4 rerank 开关开启但端口缺失时给出对应文案")
    void rerankSwitchOnButPortMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, true), true, false, true, true, registry).report();

        assertFalse(report.rerank());
        assertTrue(report.missingReasons().stream()
                        .anyMatch(r -> r.contains("rerank: 开启但 RerankPort 缺失")),
                "实际: " + report.missingReasons());
    }

    /** T3-4：二级端口缺失。 */
    @Test
    @DisplayName("T3-4 HybridSearchPort 缺失时报告 hybrid 不可用")
    void hybridPortMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, false), false, true, true, true, registry).report();

        assertFalse(report.hybrid());
        assertTrue(report.missingReasons().stream()
                        .anyMatch(r -> r.contains("hybrid: 端口缺失，将降级纯向量")),
                "实际: " + report.missingReasons());
        assertEquals(1.0, registry.get("aether.rag.capability.missing")
                .tag("stage", "hybrid").counter().count());
    }

    /** T3-4：一级改写开关开但 ChatModel 缺失。 */
    @Test
    @DisplayName("T3-4 rewrite 开启但 ChatModel 缺失时给出对应文案")
    void rewriteOnButChatModelMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, false), true, true, false, true, registry).report();

        assertFalse(report.rewrite());
        assertTrue(report.missingReasons().stream()
                        .anyMatch(r -> r.contains("rewrite: 开启但 ChatModel 缺失，将降级原 query")),
                "实际: " + report.missingReasons());
    }

    /** T3-4：embedding 缺失。 */
    @Test
    @DisplayName("T3-4 memoryEmbeddingModel 缺失时报告向量路将降级")
    void embeddingMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, false), true, true, true, false, registry).report();

        assertFalse(report.embedding());
        assertTrue(report.missingReasons().stream()
                        .anyMatch(r -> r.contains("embedding: 缺失，向量路将降级为纯词法")),
                "实际: " + report.missingReasons());
        assertEquals(1.0, registry.get("aether.rag.capability.missing")
                .tag("stage", "embedding").counter().count());
    }

    /** T3-4：总开关关闭时的文案。 */
    @Test
    @DisplayName("T3-4 总开关关闭时报告未启用")
    void pipelineDisabledIsReported() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagReadinessReporter.ReadinessReport report =
                reporter(props(false, false, true, false), true, true, true, true, registry).report();

        assertFalse(report.enabled());
        assertTrue(report.missingReasons().stream()
                        .anyMatch(r -> r.contains("未启用（enabled=false），记忆召回走原路径")),
                "实际: " + report.missingReasons());
    }

    /** T3-4 边界：MeterRegistry 缺失时只打日志，不抛异常。 */
    @Test
    @DisplayName("T3-4 边界：无 MeterRegistry 时自检仍可用")
    void worksWithoutMeterRegistry() {
        RagReadinessReporter.ReadinessReport report =
                reporter(props(true, true, true, true), true, false, true, true, null).report();

        assertFalse(report.rerank());
        assertFalse(report.missingReasons().isEmpty());
    }


    // =========================================================
    // T3-5：按开关装配
    // =========================================================
    /**
     * §8.4-5 启动信号：dev 形态（{@code enabled=true} + {@code rewrite=true} +
     * {@code hybrid=true} + {@code rerank=false}，依赖齐备）下必须打印出确定的汇总行。
     *
     * <p>本用例通过 logback ListAppender 捕获真实日志消息，等价于"看启动日志"，
     * 无需拉起完整应用即可锁住该行文本（防止后续无意改动格式而悄悄破坏验收信号）。</p>
     *
     * <p><b>与 spec 原文的差异</b>：spec §8.4-5 期望 {@code rerank=off(端口缺失)}，
     * 但 F3-1 采用 {@code matchIfMissing=true} 后 {@code RerankPort} 恒装配，
     * 缺失原因实际是"开关关闭"而非"端口缺失"。此处按<b>事实</b>断言
     * {@code rerank=off(关闭)}——自检的意义正是如实报告，伪装成端口缺失会掩盖真相。</p>
     */
    @Test
    @DisplayName("§8.4-5 dev 形态的启动自检汇总行")
    void devShapeEmitsExpectedStartupLine() {
        RagReadinessReporter reporter =
                reporter(props(true, true, true, false), true, true, true, true,
                        new SimpleMeterRegistry());

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        RagReadinessReporter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            reporter.report();
        } finally {
            logger.detachAppender(appender);
        }

        String line = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("RAG 能力自检:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("启动自检必须打印汇总行，实际日志: "
                        + appender.list.stream()
                                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                                .toList()));

        assertEquals(
                "RAG 能力自检: enabled=true, rewrite=on, hybrid=on, rerank=off(关闭), embedding=on",
                line);
    }

    /** T3-5：aether.rag.enabled=false 时自检类不在容器中。 */
    @Test
    @DisplayName("T3-5 aether.rag.enabled=false 时 RagReadinessReporter 不装配")
    void reporterNotRegisteredWhenRagDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RagProperties.class, RagReadinessReporter.class)
                .withPropertyValues("aether.rag.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RagReadinessReporter.class));
    }

    /** T3-5 对照：aether.rag.enabled=true 时装配。 */
    @Test
    @DisplayName("T3-5 aether.rag.enabled=true 时 RagReadinessReporter 装配")
    void reporterRegisteredWhenRagEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RagProperties.class, RagReadinessReporter.class)
                .withPropertyValues("aether.rag.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(RagReadinessReporter.class));
    }
}

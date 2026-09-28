package cn.zcj.aether.config;

import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * D4/F2-1: OpenTelemetry 程序化装配 —— 复用 {@code application-*.yml} 中既有的 {@code otel.*} 键
 * （原先由 {@link OtelProperties} 占位，无任何宿主读取，属死配置）。
 *
 * <p><b>为什么用 {@code otel.traces.exporter} 作为装配条件</b>：dev/prod yml 已写好该键
 * （值为 {@code otlp}），test/bench 未写。以它为条件天然实现"本地开发与生产开启导出、
 * 单测不导出"，既不新增开关，也不修改任何既有 profile 配置。条件不满足时整体不装配，
 * {@link AgentTracer} 回落 no-op，零行为变化。</p>
 *
 * <p><b>为什么不用 OTel Java Agent（-javaagent）</b>：Agent 方式需额外下载 jar 并改造
 * {@code docker/Dockerfile} 的 ENTRYPOINT，与项目"占位类 + 无 agent"的既有形态冲突较大。
 * 程序化 SDK 恰好用上 {@code aether-app/pom.xml} 已声明的 {@code opentelemetry-sdk}
 * + {@code opentelemetry-exporter-otlp}，改动面最小、不新增依赖。</p>
 *
 * <p><b>为什么不按 spec 把全局注册放在 {@code @PostConstruct}</b>：{@code @Configuration}
 * 自身的 {@code @PostConstruct} 与它自己的 {@code @Bean} 方法之间没有次序保证——
 * {@code @PostConstruct} 可能先跑，此时 {@code SdkTracerProvider} 尚未创建，
 * 注册无从谈起（且 {@code @PostConstruct} 不支持注入方法参数）。故把"建造 → 注册"
 * 收进同一个 {@code @Bean} 方法体内，次序确定。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "otlp")
public class OtelConfig {

    /** OTLP gRPC 导出超时（秒）。 */
    private static final long EXPORT_TIMEOUT_SECONDS = 3;

    /** Batch 处理器队列上限，队列满时丢弃最旧 span，业务线程不受影响。 */
    private static final int MAX_QUEUE_SIZE = 2048;

    /** 单批导出上限。 */
    private static final int MAX_EXPORT_BATCH_SIZE = 512;

    /** 导出调度间隔（秒）。 */
    private static final long SCHEDULE_DELAY_SECONDS = 5;

    /** 与 {@link AgentTracer} 内部 SCOPE_NAME/SCOPE_VERSION 保持一致。 */
    private static final String SCOPE_NAME = "aether-agent";
    private static final String SCOPE_VERSION = "1.0.0";

    /**
     * Resource 语义属性键。此处使用字符串字面量而非 {@code semconv} 常量类：
     * OTel 1.41 起 {@code ServiceAttributes} 位于独立的 {@code opentelemetry-semconv}
     * artifact（未被 SDK 传递引入），引用它等于新增 Maven 依赖，违反本卷全局约束。
     * 字面量即该常量的实际取值，Collector 侧 {@code service.name} 对齐不受影响。
     */
    private static final String ATTR_SERVICE_NAME = "service.name";
    private static final String ATTR_SERVICE_VERSION = "service.version";

    /**
     * 装配 OTLP gRPC 导出链并注册为全局 SDK。
     *
     * <p>建造与全局注册在同一方法体内完成：先 {@code GlobalOpenTelemetry.set}，
     * 再 {@code AgentTracer.setTracer}，保证显式注入优先于 {@link AgentTracer} 的懒解析。</p>
     *
     * @param endpoint    OTLP gRPC 端点（复用既有的 {@code otel.exporter.otlp.endpoint}）
     * @param serviceName 服务名（复用既有的 {@code otel.service.name}）
     * @return 已注册的 SDK 提供者；容器关闭时经 {@code destroyMethod} 调 {@code shutdown} 冲刷队列
     */
    @Bean(destroyMethod = "shutdown")
    public SdkTracerProvider sdkTracerProvider(
            @Value("${otel.exporter.otlp.endpoint:http://localhost:4317}") String endpoint,
            @Value("${otel.service.name:aether-agent}") String serviceName) {

        Resource resource = Resource.getDefault().toBuilder()
                .put(ATTR_SERVICE_NAME, serviceName)
                .put(ATTR_SERVICE_VERSION, SCOPE_VERSION)
                .build();

        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setResource(resource)
                // 本项目 span 量级低，全采样；采样率调优留待有量级压力时再议
                .setSampler(Sampler.alwaysOn());

        try {
            OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter)
                    .setMaxQueueSize(MAX_QUEUE_SIZE)
                    .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                    .setScheduleDelay(SCHEDULE_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            // 边界（spec §7.1.9）：endpoint 格式非法等构造失败 → 降级为不导出，不阻塞应用启动。
            // 仍返回可用 provider，使 span 采集本身不致 NPE；仅"导出"被禁用。
            log.error("OTLP exporter 构造失败（endpoint={}），trace 导出已禁用: {}",
                    endpoint, e.getMessage());
        }

        SdkTracerProvider provider = builder.build();
        installGlobal(provider, endpoint, serviceName);
        return provider;
    }

    /**
     * 注册全局 SDK 并绑定 {@link AgentTracer}。
     *
     * <p>顺序：先 {@code GlobalOpenTelemetry.set}，再 {@code AgentTracer.setTracer}。</p>
     *
     * <p>{@code GlobalOpenTelemetry.set} 的入参是 {@link OpenTelemetrySdk} 而非
     * {@link SdkTracerProvider}——后者只是建链产物，需经 {@code OpenTelemetrySdk.builder()}
     * 包装成 {@code OpenTelemetry} 实例才能注册为全局（spec §7.1.7 写作
     * {@code set(SdkTracerProvider)}，该写法无法通过编译）。</p>
     *
     * <p>{@code GlobalOpenTelemetry.set} 在同一 JVM 内只允许成功一次（重复调用抛
     * {@link IllegalStateException}）——测试多上下文加载时会命中，此处降级为 WARN 并保留首次注册，
     * 不让重复注册演变成启动失败。</p>
     */
    private void installGlobal(SdkTracerProvider provider, String endpoint, String serviceName) {
        try {
            GlobalOpenTelemetry.set(
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build());
        } catch (IllegalStateException e) {
            log.warn("GlobalOpenTelemetry 已注册，跳过全局覆盖（保留首次注册）: {}", e.getMessage());
        }
        AgentTracer.setTracer(provider.get(SCOPE_NAME, SCOPE_VERSION));
        log.info("OpenTelemetry SDK 已装配: endpoint={}, service.name={}", endpoint, serviceName);
    }
}

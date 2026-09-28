package cn.zcj.aether.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * D4: 测试用 span 收集器。
 *
 * <p>替代 {@code opentelemetry-sdk-testing} 的 {@code InMemorySpanExporter}：该 artifact
 * 未被任何 pom 声明，引入它会违反 D4 §5「不新增 Maven 依赖」。{@link SpanExporter} 是
 * {@code opentelemetry-sdk}（已在 {@code aether-app/pom.xml} 声明）的接口，自行实现零依赖。</p>
 *
 * <p>配合 {@code SimpleSpanProcessor} 使用：span 结束时同步写入，断言无需等待。</p>
 */
public final class RecordingSpanExporter implements SpanExporter {

    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public synchronized CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public synchronized CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /** 已采集的全部 span（按结束顺序）。 */
    public synchronized List<SpanData> spans() {
        return List.copyOf(spans);
    }

    /** 按名称取首个 span。 */
    public synchronized Optional<SpanData> find(String name) {
        return spans.stream().filter(s -> name.equals(s.getName())).findFirst();
    }

    /** 按名称取全部同名 span。 */
    public synchronized List<SpanData> named(String name) {
        return spans.stream().filter(s -> name.equals(s.getName())).toList();
    }

    /** 清空已采集的 span。 */
    public synchronized void reset() {
        spans.clear();
    }
}

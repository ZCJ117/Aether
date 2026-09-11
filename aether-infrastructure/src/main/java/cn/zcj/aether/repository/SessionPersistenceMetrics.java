package cn.zcj.aether.repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话持久化写失败指标 — P0-3 / O6。
 *
 * <p>计数 Micrometer counter {@code aether.session.persist.failures}，
 * 使写失败不再静默（配合 ERROR 日志可观测、可告警）。
 * O6 新增"最近错误"字段：保存最后一次失败的 sessionId / 根因 / 时间戳，
 * 供会话列表、恢复路径发现状态缺失时直接取因，无需翻日志。
 * MeterRegistry 为可选注入：无注册表（如部分测试环境）时仅记录日志，不抛异常。</p>
 *
 * <p><b>【架构亮点 · 工程化闭环】</b><br>面试举证点：这是"可观测闭环"的落地样本——
 * 会话持久化是跨重启恢复的关键路径，但其写失败历来静默丢失；本组件用 Micrometer Counter
 * {@code aether.session.persist.failures} 将失败显式计数，并额外保存最近一次失败快照
 * （sessionId/根因/时间戳），使 FailureSnapshot 可被 actuator/prometheus 暴露、被 Grafana 面板
 * 展示、被 Alertmanager 触发告警，形成"指标采集 → 可视化 → 告警"的完整闭环；
 * 同时 MeterRegistry 可选注入保证无监控环境也能安全降级。</p>
 */
@Slf4j
@Component
public class SessionPersistenceMetrics {

    // 【可观测】自定义 Micrometer 指标名：会话持久化写失败计数器，统一接入 Prometheus 拉取与告警规则。
    static final String METRIC_NAME = "aether.session.persist.failures";

    private final MeterRegistry meterRegistry;

    /** O6: 最近一次持久化失败快照（volatile 读，原子写）。 */
    private final AtomicReference<FailureSnapshot> lastFailure = new AtomicReference<>();

    /** O6: 最近一次持久化失败的可观测快照。 */
    public record FailureSnapshot(String sessionId, String operation, String rootCause, Instant at) {}

    public SessionPersistenceMetrics(
            @org.springframework.beans.factory.annotation.Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 记录一次会话持久化写失败（幂等：无注册表时安全跳过）。 */
    public void incrementFailures() {
        if (meterRegistry == null) {
            log.debug("无 MeterRegistry，跳过指标记录: {}", METRIC_NAME);
            return;
        }
        // 【可观测】惰性注册并自增计数器：首次调用时构建带描述的 Counter，后续直接 increment，
        // Prometheus 定时拉取该指标即可观测持久化失败趋势，异常突增即触发告警闭环。
        Counter.builder(METRIC_NAME)
                .description("会话持久化写失败次数")
                .register(meterRegistry)
                .increment();
    }

    /**
     * O6: 记录一次持久化失败——计数 + 挂载最近错误快照（sessionId / 操作 / 根因 / 时间）。
     * 失败对调用方可见：可直接通过 {@link #getLastFailure()} 取因告警。
     */
    public void recordFailure(String sessionId, String operation, Throwable cause) {
        incrementFailures();
        FailureSnapshot snapshot = new FailureSnapshot(
                sessionId, operation,
                cause != null ? cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName() : "unknown",
                Instant.now());
        lastFailure.set(snapshot);
        log.error("会话持久化失败[{}]: sessionId={}, rootCause={}", operation, sessionId, snapshot.rootCause(), cause);
    }

    /** O6: 最近一次持久化失败快照（从未失败过返回 null）。 */
    public FailureSnapshot getLastFailure() {
        return lastFailure.get();
    }
}

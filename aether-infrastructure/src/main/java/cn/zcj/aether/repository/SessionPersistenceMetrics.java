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
 */
@Slf4j
@Component
public class SessionPersistenceMetrics {

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

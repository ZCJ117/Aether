package cn.zcj.aether.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * P1: 审计日志持久化仓储。
 *
 * <p>P1(1.3)：{@code eventId} 幂等键支持 Kafka 至少一次投递——
 * {@link #saveBatch} 以 {@code ON CONFLICT (event_id) DO NOTHING} 批量去重写入，
 * 消费端重放不会产生重复审计行；直写路径 {@code save} 的 eventId 可为 null。</p>
 */
@Slf4j
@Repository
public class AuditLogRepository {

    private static final String INSERT_SQL = """
        INSERT INTO t_audit_log
            (event_id, user_id, username, action, resource, detail,
             ip_address, success, error_message, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void save(AuditLogEntity entity) {
        jdbcTemplate.update(INSERT_SQL,
            entity.getEventId(), entity.getUserId(), entity.getUsername(),
            entity.getAction(), entity.getResource(),
            entity.getDetail(), entity.getIpAddress(),
            entity.getSuccess(), entity.getErrorMessage(),
            Timestamp.from(entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now()));
    }

    /**
     * P1(1.3): Kafka 消费端批量落库 —— 单条冲突（重复 eventId）跳过，其余照写。
     *
     * @return 实际写入行数（= 批内去重后的新增数）
     */
    public int saveBatch(List<AuditLogEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        int[][] rows = jdbcTemplate.batchUpdate(INSERT_SQL, entities, Math.min(entities.size(), 500),
            (PreparedStatement ps, AuditLogEntity e) -> {
                ps.setString(1, e.getEventId());
                ps.setObject(2, e.getUserId());
                ps.setString(3, e.getUsername());
                ps.setString(4, e.getAction());
                ps.setString(5, e.getResource());
                ps.setString(6, e.getDetail());
                ps.setString(7, e.getIpAddress());
                ps.setObject(8, e.getSuccess());
                ps.setString(9, e.getErrorMessage());
                ps.setTimestamp(10, Timestamp.from(
                    e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now()));
            });
        int total = 0;
        for (int[] batch : rows) {
            for (int r : batch) {
                // ON CONFLICT DO NOTHING 在 driver 不回报行数时为 SUCCESS_NO_INFO(-2)：按 1 计
                total += (r >= 0) ? r : 1;
            }
        }
        return total;
    }

    @lombok.Data
    public static class AuditLogEntity {
        private String eventId;
        private Long userId;
        private String username;
        private String action;
        private String resource;
        private String detail;
        private String ipAddress;
        private Boolean success;
        private String errorMessage;
        private Instant createdAt;
    }
}

package cn.zcj.aether.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * P1: 审计日志持久化仓储。
 */
@Slf4j
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void save(AuditLogEntity entity) {
        String sql = """
            INSERT INTO t_audit_log
                (user_id, username, action, resource, detail,
                 ip_address, success, error_message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            entity.getUserId(), entity.getUsername(),
            entity.getAction(), entity.getResource(),
            entity.getDetail(), entity.getIpAddress(),
            entity.getSuccess(), entity.getErrorMessage(),
            Timestamp.from(Instant.now()));
    }

    @lombok.Data
    public static class AuditLogEntity {
        private Long id;
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

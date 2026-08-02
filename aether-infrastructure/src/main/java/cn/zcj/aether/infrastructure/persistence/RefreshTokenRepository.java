package cn.zcj.aether.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * P0: RefreshToken 持久化仓储。
 */
@Slf4j
@Repository
public class RefreshTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefreshTokenRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        log.info("RefreshTokenRepository 已初始化");
    }

    private static final String SELECT_BY_TOKEN_SQL =
        "SELECT id, user_id, token, expires_at, revoked, created_at FROM t_refresh_token WHERE token = ?";

    private static final String INSERT_SQL = """
        INSERT INTO t_refresh_token (user_id, token, expires_at, revoked, created_at)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String REVOKE_ALL_FOR_USER_SQL =
        "UPDATE t_refresh_token SET revoked = TRUE WHERE user_id = ?";

    private static final String REVOKE_BY_TOKEN_SQL =
        "UPDATE t_refresh_token SET revoked = TRUE WHERE token = ?";

    public Optional<RefreshTokenEntity> findByToken(String token) {
        List<RefreshTokenEntity> results = jdbcTemplate.query(SELECT_BY_TOKEN_SQL, new RefreshTokenRowMapper(), token);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public RefreshTokenEntity save(RefreshTokenEntity entity) {
        jdbcTemplate.update(INSERT_SQL,
            entity.getUserId(),
            entity.getToken(),
            Timestamp.from(entity.getExpiresAt()),
            entity.getRevoked() != null ? entity.getRevoked() : false,
            Timestamp.from(Instant.now()));
        return findByToken(entity.getToken()).orElseThrow();
    }

    public void revokeAllForUser(Long userId) {
        jdbcTemplate.update(REVOKE_ALL_FOR_USER_SQL, userId);
    }

    public void revokeByToken(String token) {
        jdbcTemplate.update(REVOKE_BY_TOKEN_SQL, token);
    }

    private static class RefreshTokenRowMapper implements RowMapper<RefreshTokenEntity> {
        @Override
        public RefreshTokenEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            RefreshTokenEntity e = new RefreshTokenEntity();
            e.setId(rs.getLong("id"));
            e.setUserId(rs.getLong("user_id"));
            e.setToken(rs.getString("token"));
            e.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
            e.setRevoked(rs.getBoolean("revoked"));
            e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            return e;
        }
    }

    @lombok.Data
    public static class RefreshTokenEntity {
        private Long id;
        private Long userId;
        private String token;
        private Instant expiresAt;
        private Boolean revoked;
        private Instant createdAt;
    }
}

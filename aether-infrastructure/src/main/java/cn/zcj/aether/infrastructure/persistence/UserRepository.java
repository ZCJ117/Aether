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
 * P0: 用户持久化仓储。
 */
@Slf4j
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        log.info("UserRepository 已初始化");
    }

    private static final String UPSERT_SQL = """
        INSERT INTO t_user (username, password, email, role, enabled, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (username) DO UPDATE SET
            password = EXCLUDED.password,
            email = EXCLUDED.email,
            updated_at = EXCLUDED.updated_at
        """;

    private static final String SELECT_BY_USERNAME_SQL =
        "SELECT id, username, password, email, role, enabled, created_at, updated_at FROM t_user WHERE username = ?";

    private static final String SELECT_BY_ID_SQL =
        "SELECT id, username, password, email, role, enabled, created_at, updated_at FROM t_user WHERE id = ?";

    public Optional<UserEntity> findByUsername(String username) {
        List<UserEntity> results = jdbcTemplate.query(SELECT_BY_USERNAME_SQL, new UserRowMapper(), username);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<UserEntity> findById(Long id) {
        List<UserEntity> results = jdbcTemplate.query(SELECT_BY_ID_SQL, new UserRowMapper(), id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public UserEntity save(UserEntity user) {
        Instant now = Instant.now();
        jdbcTemplate.update(UPSERT_SQL,
            user.getUsername(),
            user.getPassword(),
            user.getEmail(),
            user.getRole() != null ? user.getRole() : "VIEWER",
            user.getEnabled() != null ? user.getEnabled() : true,
            user.getCreatedAt() != null ? Timestamp.from(user.getCreatedAt()) : Timestamp.from(now),
            Timestamp.from(now));
        return findByUsername(user.getUsername()).orElseThrow();
    }

    private static class UserRowMapper implements RowMapper<UserEntity> {
        @Override
        public UserEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserEntity e = new UserEntity();
            e.setId(rs.getLong("id"));
            e.setUsername(rs.getString("username"));
            e.setPassword(rs.getString("password"));
            e.setEmail(rs.getString("email"));
            e.setRole(rs.getString("role"));
            e.setEnabled(rs.getBoolean("enabled"));
            e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            e.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
            return e;
        }
    }

    @lombok.Data
    public static class UserEntity {
        private Long id;
        private String username;
        private String password;
        private String email;
        private String role;
        private Boolean enabled;
        private Instant createdAt;
        private Instant updatedAt;
    }
}

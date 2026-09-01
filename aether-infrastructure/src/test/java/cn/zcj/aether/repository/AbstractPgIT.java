package cn.zcj.aether.repository;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * P1(2.2): pgvector 集成测试基类。
 *
 * <p>容器镜像与生产一致（{@code pgvector/pgvector:pg16}，见 docker/docker-compose-secure.yml），
 * JVM 级单例容器供全部 IT 复用；启动时按演进链执行 DDL：
 * {@code data/sql/V1__baseline.sql}（canonical 基线：认证/审计/会话/记忆）
 * → {@code data/sql/V3__audit_log.sql}（t_audit_log 增量索引）
 * → {@code data/sql/V5__p1_messaging_and_memory.sql}（P1 增量：event_id/archived/content_bigram/
 * dashboard_stats/aether_processed_event）——迁移文件与生产手工迁移同源，
 * 表结构演进有真实数据库回归防线（新增迁移时在此追加执行即可）。</p>
 *
 * <p>运行方式：{@code mvn verify -Pintegration}（failsafe，需 Docker；CI 中独立 job 执行）。</p>
 */
public abstract class AbstractPgIT {

    /** 演进链：canonical 基线 + 本工程 IT 覆盖到的手工迁移（按版本序）。 */
    private static final Path[] SCHEMA_FILES = {
            Path.of("..", "data", "sql", "V1__baseline.sql"),
            Path.of("..", "data", "sql", "V3__audit_log.sql"),
            Path.of("..", "data", "sql", "V5__p1_messaging_and_memory.sql"),
    };

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aether")
            .withUsername("aether")
            .withPassword("aether");

    static {
        PG.start();
    }

    /** 独立 DataSource（不走 Hikari，IT 内直连即可）。 */
    protected static DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    protected static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    @BeforeAll
    static void initCanonicalSchema() throws Exception {
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            for (Path schemaFile : SCHEMA_FILES) {
                String sql = Files.readString(schemaFile);
                for (String part : sql.split(";")) {
                    String stmt = part.strip();
                    if (stmt.isEmpty()) {
                        continue;
                    }
                    st.execute(stmt);
                }
            }
        }
    }
}

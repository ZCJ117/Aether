# MySQL → PostgreSQL 全量迁移 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 MySQL 依赖，将所有数据库操作统一到 PostgreSQL 单一连接池。

**Architecture:** 删除 `MySqlSessionRepository`，新建 `PgSessionRepository`（PostgreSQL JdbcTemplate 实现 `SessionRepository` 接口），替换 pom 依赖，更新配置文件，重写 PostgreSQL DDL。

**Tech Stack:** Java 17, Spring Boot 3.4.3, JdbcTemplate, PostgreSQL 18.1, Maven

---

### Task 1: 修改根 pom.xml — 移除 mysql-connector-java 托管依赖

**Files:**
- Modify: `pom.xml:72-81`

- [ ] **Step 1: 删除 mysql-connector-java 托管依赖**

```xml
<!-- 删除以下内容 (pom.xml 第 72-81 行) -->
            <!-- # 多数据源路由配置
                 # mysql 5.x driver-class-name: com.mysql.jdbc.Driver    mysql-connector-java 5.1.34
                 # mysql 8.x driver-class-name: com.mysql.cj.jdbc.Driver mysql-connector-java 8.0.22-->
            <dependency>
                <groupId>mysql</groupId>
                <artifactId>mysql-connector-java</artifactId>
                <version>8.0.28</version>
            </dependency>
```

- [ ] **Step 2: Commit**

```bash
git add pom.xml
git commit -m "移除mysql-connector-java托管依赖"
```

---

### Task 2: 修改 aether-app/pom.xml — 替换 JDBC 驱动

**Files:**
- Modify: `aether-app/pom.xml:36-47`

- [ ] **Step 1: 替换 mysql-connector-java 为 postgresql**

```xml
<!-- 删除 -->
        <!-- # 多数据源路由配置
             # mysql 5.x driver-class-name: com.mysql.jdbc.Driver    mysql-connector-java 5.1.34
             # mysql 8.x driver-class-name: com.mysql.cj.jdbc.Driver mysql-connector-java 8.0.22-->
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
        </dependency>

<!-- 替换为 -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
```

> 注意：postgresql 版本由根 pom 的 dependencyManagement 托管，此处无需写 version。

- [ ] **Step 2: Commit**

```bash
git add aether-app/pom.xml
git commit -m "替换mysql驱动为postgresql驱动"
```

---

### Task 3: 新建 PgSessionRepository — PostgreSQL 会话持久化实现

**Files:**
- Create: `aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgSessionRepository.java`
- Reference: `MySqlSessionRepository.java`（同目录，稍后删除）

- [ ] **Step 1: 创建 PgSessionRepository**

```java
package cn.zcj.aether.repository;

import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL 实现的会话持久化仓储 — P0-4。
 * 激活条件: PostgreSQL 驱动可用 + JdbcTemplate Bean 存在 + aether.session.persistence=true
 */
@Slf4j
@Repository
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(name = "aether.session.persistence", havingValue = "true", matchIfMissing = false)
public class PgSessionRepository implements SessionRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final String UPSERT_SQL = """
        INSERT INTO aether_session (session_id, user_id, agent_id, status, state_json, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (session_id) DO UPDATE SET
            status = EXCLUDED.status,
            state_json = EXCLUDED.state_json,
            updated_at = EXCLUDED.updated_at
        """;

    private static final String SELECT_SQL = """
        SELECT id, session_id, user_id, agent_id, status, state_json, created_at, updated_at
        FROM aether_session WHERE session_id = ?
        """;

    private static final String DELETE_SQL = "DELETE FROM aether_session WHERE session_id = ?";

    private static final String LIST_BY_USER_SQL = """
        SELECT id, session_id, user_id, agent_id, status, state_json, created_at, updated_at
        FROM aether_session WHERE user_id = ? AND status = 'ACTIVE' ORDER BY updated_at DESC
        """;

    @Override
    public CompletableFuture<Void> save(SessionEntity entity) {
        return CompletableFuture.runAsync(() -> {
            Instant now = Instant.now();
            jdbcTemplate.update(UPSERT_SQL,
                entity.getSessionId(),
                entity.getUserId(),
                entity.getAgentId(),
                entity.getStatus() != null ? entity.getStatus() : "ACTIVE",
                entity.getStateJson(),
                entity.getCreatedAt() != null
                    ? Timestamp.from(entity.getCreatedAt()) : Timestamp.from(now),
                Timestamp.from(now)
            );
            log.debug("会话已持久化: sessionId={}, status={}", entity.getSessionId(), entity.getStatus());
        });
    }

    @Override
    public Optional<SessionEntity> findBySessionId(String sessionId) {
        List<SessionEntity> results = jdbcTemplate.query(SELECT_SQL, new SessionRowMapper(), sessionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public CompletableFuture<Void> deleteBySessionId(String sessionId) {
        return CompletableFuture.runAsync(() ->
            jdbcTemplate.update(DELETE_SQL, sessionId));
    }

    @Override
    public List<SessionEntity> listByUserId(String userId) {
        return jdbcTemplate.query(LIST_BY_USER_SQL, new SessionRowMapper(), userId);
    }

    private static class SessionRowMapper implements RowMapper<SessionEntity> {
        @Override
        public SessionEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SessionEntity.builder()
                .sessionId(rs.getString("session_id"))
                .userId(rs.getString("user_id"))
                .agentId(rs.getString("agent_id"))
                .status(rs.getString("status"))
                .stateJson(rs.getString("state_json"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .build();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgSessionRepository.java
git commit -m "新增PgSessionRepository PostgreSQL会话持久化实现"
```

---

### Task 4: 删除 MySqlSessionRepository

**Files:**
- Delete: `aether-infrastructure/src/main/java/cn/zcj/aether/repository/MySqlSessionRepository.java`

- [ ] **Step 1: 删除文件**

```bash
rm aether-infrastructure/src/main/java/cn/zcj/aether/repository/MySqlSessionRepository.java
```

- [ ] **Step 2: Commit**

```bash
git add aether-infrastructure/src/main/java/cn/zcj/aether/repository/MySqlSessionRepository.java
git commit -m "删除MySqlSessionRepository MySQL会话持久化实现"
```

---

### Task 5: 修改 application-dev.yml — 数据源改为 PostgreSQL

**Files:**
- Modify: `aether-app/src/main/resources/application-dev.yml:15-36`

- [ ] **Step 1: 替换数据源配置**

```yaml
# 删除 MySQL 数据源配置（第 15-36 行），替换为:
# 数据库配置；PostgreSQL
spring:
  datasource:
    username: postgres
    password: 123456
    url: jdbc:postgresql://127.0.0.1:5432/aether
    driver-class-name: org.postgresql.Driver
    hikari:
      pool-name: Aether_HikariCP
      minimum-idle: 15
      idle-timeout: 180000
      maximum-pool-size: 25
      auto-commit: true
      max-lifetime: 1800000
      connection-timeout: 30000
      connection-test-query: SELECT 1
    type: com.zaxxer.hikari.HikariDataSource
```

> 关键变更: url 改为 `jdbc:postgresql://127.0.0.1:5432/aether`，driver 改为 `org.postgresql.Driver`，pool-name 改为 `Aether_HikariCP`。

- [ ] **Step 2: Commit**

```bash
git add aether-app/src/main/resources/application-dev.yml
git commit -m "开发环境数据源改为PostgreSQL"
```

---

### Task 6: 修改 application-test.yml 和 application-prod.yml — 更新注释中的数据库配置

**Files:**
- Modify: `aether-app/src/main/resources/application-test.yml:15-31`
- Modify: `aether-app/src/main/resources/application-prod.yml:15-31`

- [ ] **Step 1: 更新 test 配置注释**

```yaml
# 将第 15-31 行的 MySQL 注释替换为:
# 数据库配置；PostgreSQL（启用时取消注释）
#spring:
#  datasource:
#    username: postgres
#    password: 123456
#    url: jdbc:postgresql://127.0.0.1:5432/aether
#    driver-class-name: org.postgresql.Driver
#  hikari:
#    pool-name: Aether_HikariCP
#    minimum-idle: 15
#    idle-timeout: 180000
#    maximum-pool-size: 25
#    auto-commit: true
#    max-lifetime: 1800000
#    connection-timeout: 30000
#    connection-test-query: SELECT 1
#  type: com.zaxxer.hikari.HikariDataSource
```

- [ ] **Step 2: 更新 prod 配置注释（同上）**

```yaml
# 将第 15-31 行的 MySQL 注释替换为:
# 数据库配置；PostgreSQL（启用时取消注释）
#spring:
#  datasource:
#    username: postgres
#    password: ${DB_PASSWORD}
#    url: jdbc:postgresql://127.0.0.1:5432/aether
#    driver-class-name: org.postgresql.Driver
#  hikari:
#    pool-name: Aether_HikariCP
#    minimum-idle: 15
#    idle-timeout: 180000
#    maximum-pool-size: 25
#    auto-commit: true
#    max-lifetime: 1800000
#    connection-timeout: 30000
#    connection-test-query: SELECT 1
#  type: com.zaxxer.hikari.HikariDataSource
```

> prod 配置中密码用环境变量 `${DB_PASSWORD}` 代替硬编码。

- [ ] **Step 3: Commit**

```bash
git add aether-app/src/main/resources/application-test.yml aether-app/src/main/resources/application-prod.yml
git commit -m "更新test和prod环境数据库配置为PostgreSQL"
```

---

### Task 7: 重写 docs/postgresql_schema.sql — 完整 PostgreSQL DDL

**Files:**
- Rewrite: `docs/postgresql_schema.sql`

- [ ] **Step 1: 写入完整 DDL**

```sql
-- ============================================================================
-- Aether Agent — PostgreSQL 数据库建表脚本（单一数据库 aether）
-- ============================================================================
-- 生成依据：
--   PgSessionRepository.java   — aether_session 表（会话持久化）
--   PgvectorVectorStore.java   — aether_memories 表（向量记忆存储）
--   SessionEntity.java         — 会话实体字段定义
--   MemoryRecord.java          — 记忆实体字段定义
-- ============================================================================
-- 前置要求:
--   1. PostgreSQL 16+ (已验证 18.1)
--   2. 数据库: CREATE DATABASE aether;
--   3. pgvector 扩展（可选，用于 aether_memories 向量索引；未安装时 embedding 降级为 TEXT）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 表: aether_session — Agent 会话持久化表
-- 对应: PgSessionRepository (INSERT ... ON CONFLICT DO UPDATE)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aether_session (
    id          BIGINT       NOT NULL GENERATED BY DEFAULT AS IDENTITY,
    session_id  VARCHAR(128) NOT NULL,
    user_id     VARCHAR(128) NOT NULL,
    agent_id    VARCHAR(128) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    state_json  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT uk_session_id UNIQUE (session_id)
);

CREATE INDEX IF NOT EXISTS idx_session_user_id    ON aether_session (user_id);
CREATE INDEX IF NOT EXISTS idx_session_agent_id   ON aether_session (agent_id);
CREATE INDEX IF NOT EXISTS idx_session_status     ON aether_session (status);
CREATE INDEX IF NOT EXISTS idx_session_updated_at ON aether_session (updated_at);

COMMENT ON TABLE  aether_session            IS 'Agent 会话持久化表';
COMMENT ON COLUMN aether_session.id         IS '自增主键';
COMMENT ON COLUMN aether_session.session_id IS '会话唯一标识（业务主键）';
COMMENT ON COLUMN aether_session.user_id    IS '用户 ID';
COMMENT ON COLUMN aether_session.agent_id   IS 'Agent ID';
COMMENT ON COLUMN aether_session.status     IS '会话状态: ACTIVE / ARCHIVED / ERROR';
COMMENT ON COLUMN aether_session.state_json IS 'AgentState JSON 序列化数据';
COMMENT ON COLUMN aether_session.created_at IS '创建时间';
COMMENT ON COLUMN aether_session.updated_at IS '更新时间';

-- ----------------------------------------------------------------------------
-- 表: aether_memories — Agent 记忆向量存储表
-- 对应: PgvectorVectorStore (INSERT ... ON CONFLICT DO UPDATE)
-- 说明: embedding 列使用 TEXT 类型存储 '[x1,x2,...]' 格式向量，兼容未安装 pgvector
--       的 PostgreSQL 实例。安装 pgvector 后执行:
--         ALTER TABLE aether_memories ALTER COLUMN embedding TYPE vector(1280)
--           USING embedding::vector;
-- 然后取消下方 ivfflat 索引注释并执行。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aether_memories (
    id               VARCHAR(64)  NOT NULL,
    content          TEXT         NOT NULL,
    embedding        TEXT,
    scope_path       VARCHAR(512),
    scope_private    BOOLEAN      NOT NULL DEFAULT false,
    categories       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    importance       REAL         NOT NULL DEFAULT 0.5,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    source           VARCHAR(64)  NOT NULL DEFAULT 'agent_extracted',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_accessed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    access_count     INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_memories_scope_path    ON aether_memories (scope_path);
CREATE INDEX IF NOT EXISTS idx_memories_created_at    ON aether_memories (created_at);
CREATE INDEX IF NOT EXISTS idx_memories_source        ON aether_memories (source);
CREATE INDEX IF NOT EXISTS idx_memories_importance    ON aether_memories (importance);
CREATE INDEX IF NOT EXISTS idx_memories_last_accessed ON aether_memories (last_accessed_at);

-- [需 pgvector] ivfflat 向量索引 — 安装 pgvector 后取消注释:
-- CREATE INDEX IF NOT EXISTS idx_memories_embedding ON aether_memories
--     USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

COMMENT ON TABLE  aether_memories                  IS 'Agent 记忆向量存储表';
COMMENT ON COLUMN aether_memories.id               IS '记忆唯一标识';
COMMENT ON COLUMN aether_memories.content          IS '记忆文本内容';
COMMENT ON COLUMN aether_memories.embedding        IS 'Embedding 向量（TEXT 格式，安装 pgvector 后可 ALTER 为 vector(1280)）';
COMMENT ON COLUMN aether_memories.scope_path       IS '作用域路径（层级命名空间）';
COMMENT ON COLUMN aether_memories.scope_private    IS '是否私密记忆';
COMMENT ON COLUMN aether_memories.categories       IS '分类标签（JSONB 数组）';
COMMENT ON COLUMN aether_memories.importance       IS '重要性评分（0.0 ~ 1.0）';
COMMENT ON COLUMN aether_memories.metadata         IS '扩展元数据（JSONB 对象）';
COMMENT ON COLUMN aether_memories.source           IS '来源: user_manual / agent_extracted / system';
COMMENT ON COLUMN aether_memories.created_at       IS '创建时间';
COMMENT ON COLUMN aether_memories.last_accessed_at IS '最后访问时间';
COMMENT ON COLUMN aether_memories.access_count     IS '访问次数';
```

- [ ] **Step 2: Commit**

```bash
git add docs/postgresql_schema.sql
git commit -m "重写PostgreSQL完整建表脚本包含aether_session和aether_memories"
```

---

### Task 8: Maven 编译验证

**Files:** 无（验证步骤）

- [ ] **Step 1: 清理编译**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-infrastructure -am
```

Expected: BUILD SUCCESS（无 MySQL 驱动依赖错误，PgSessionRepository 编译通过）

- [ ] **Step 2: 全量编译**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS（所有模块编译通过，无 MySqlSessionRepository 导入报错）

---

### Task 9: 在 PostgreSQL 中执行建表脚本

- [ ] **Step 1: 创建数据库（如未创建）**

在 IDEA Database 面板的 PostgreSQL 连接查询控制台中执行：

```sql
CREATE DATABASE aether;
```

- [ ] **Step 2: 执行建表脚本**

在 IDEA 中打开 `docs/postgresql_schema.sql`，切换到 `aether` 库，右键 → Run。

Expected: 两张表创建成功，索引和注释生效。

- [ ] **Step 3: 验证**

```sql
\d aether_session
\d aether_memories
```

Expected: 表结构、索引、注释均与脚本一致。

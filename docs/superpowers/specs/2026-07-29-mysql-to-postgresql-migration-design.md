# MySQL → PostgreSQL 全量迁移设计

**日期**: 2026-07-29
**状态**: 已批准

## 目标

移除 MySQL 依赖，将所有数据库操作统一到 PostgreSQL，使用单一 HikariCP 连接池。

## 改动清单

### 依赖变更

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` (root) | 删除 `mysql-connector-java` | 移除托管依赖 |
| `aether-app/pom.xml` | `mysql-connector-java` → `postgresql` | 替换 JDBC 驱动 |

### 代码变更

| 文件 | 操作 | 说明 |
|------|------|------|
| `MySqlSessionRepository.java` | 删除 | `aether-infrastructure/.../repository/` |
| `PgSessionRepository.java` | 新建 | 同目录，PostgreSQL JdbcTemplate 实现 |
| `application-dev.yml` | 修改 | datasource → PostgreSQL |
| `application-test.yml` | 修改 | 注释中 MySQL 配置 → PostgreSQL |
| `application-prod.yml` | 修改 | 同上 |

### DDL 变更

| 文件 | 操作 |
|------|------|
| `docs/postgresql_schema.sql` | 重写，包含 `aether_session` + `aether_memories` 两张表 |

### 不变项

- `SessionRepository` 接口
- `SessionPersistenceHook`、`ChatService`、`ReActAgent`、`AgentRuntime` — 均注入接口
- `RedisSessionRepository` — 独立条件激活，不冲突
- `PgvectorVectorStore` — 已是 PostgreSQL
- `SessionRepositoryTest` — 内存实现测接口契约

## 关键 SQL 差异

```
MySQL:    INSERT ... ON DUPLICATE KEY UPDATE status = VALUES(status), ...
PG:       INSERT ... ON CONFLICT (session_id) DO UPDATE SET status = EXCLUDED.status, ...
```

其余逻辑（JdbcTemplate、RowMapper、CompletableFuture.runAsync、条件加载注解）完全一致。

## 配置

单一 PostgreSQL DataSource，启用会话持久化需配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/aether
    username: postgres
    password: 123456
aether:
  session:
    persistence: true
```

## 数据库

单一数据库 `aether`，包含两张表：
- `aether_session` — 会话持久化
- `aether_memories` — 向量记忆（可选 pgvector 扩展，降级时 embedding 用 TEXT）

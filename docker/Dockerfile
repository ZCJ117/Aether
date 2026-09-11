# ============================================================
# P0(3.2): Aether 主服务镜像 —— 多阶段构建
# stage1 maven 构建（pom 先行拷贝利用层缓存）；stage2 JRE 运行时（非 root）
# 构建：docker build -f docker/Dockerfile -t aether:latest .
# ============================================================

# ---------- stage 1: build ----------
# 【工程化闭环】多阶段构建（构建层 → 运行层）：maven:3.9-temurin-17 仅用于编译打包，
# 最终运行镜像切换到轻量 eclipse-temurin:17-jre，显著缩小攻击面与镜像体积。
# 层缓存优化：先单独 COPY 各模块 pom.xml 执行 dependency:go-offline，源码未变更时
# 直接命中依赖层缓存，避免每次改码都重新下载全量依赖，加速 CI 构建。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷 pom 下载依赖（源码不变时命中层缓存）
COPY pom.xml .
COPY aether-api/pom.xml aether-api/
COPY aether-types/pom.xml aether-types/
COPY aether-domain/pom.xml aether-domain/
COPY aether-infrastructure/pom.xml aether-infrastructure/
COPY aether-trigger/pom.xml aether-trigger/
COPY aether-app/pom.xml aether-app/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY aether-api/src aether-api/src
COPY aether-types/src aether-types/src
COPY aether-domain/src aether-domain/src
COPY aether-infrastructure/src aether-infrastructure/src
COPY aether-trigger/src aether-trigger/src
COPY aether-app/src aether-app/src
RUN mvn -B -q package -DskipTests

# ---------- stage 2: runtime ----------
# 【工程化闭环】运行阶段使用 JRE（非 JDK）并创建专用非 root 用户 aether（uid/gid=10000）运行，
# 遵循最小权限原则：即便容器被攻破，攻击者亦无 root 权限，配合只读卷与 chown 进一步收敛风险。
FROM eclipse-temurin:17-jre AS runtime

# healthcheck 需要 curl
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10000 aether \
    && useradd -u 10000 -g aether -m -s /bin/bash aether

WORKDIR /app
COPY --from=build /build/aether-app/target/aether-app.jar app.jar
COPY aether-app/src/main/resources/agent/skills /app/agent/skills

# 非 root 运行时的可写目录：JSON/滚动日志（logback-spring.xml 写 ./logs 与 ./data/log）
RUN mkdir -p /app/logs /app/data/log \
    && chown -R aether:aether /app

USER aether
EXPOSE 8091

# 【工程化闭环】HEALTHCHECK 直接探测 Spring Boot Actuator 的 /actuator/health 端点，
# 与运行时健康检查语义一致；编排器（docker/k8s）可据此自动重启异常实例、摘除流量，
# 是"可观测 → 自愈"闭环的落地点。start-period=90s 留足冷启动与依赖就绪时间。
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8091/actuator/health || exit 1

# 容器内存感知（替代过时的 -XX:MaxPermSize）：堆最多用容器限额的 75%
# 【工程化闭环】-XX:MaxRAMPercentage=75 让 JVM 按容器 cgroup 内存限额自动设定堆上限，
# 避免容器内"看到宿主机全量内存"而超卖 OOM；-XX:+HeapDumpOnOutOfMemoryError 在 OOM 时
# 自动落堆转储，为事后根因分析保留现场，是生产排障闭环的关键一环。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+HeapDumpOnOutOfMemoryError", "-jar", "/app/app.jar"]

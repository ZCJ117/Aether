# ============================================================
# P0(3.2): Aether 主服务镜像 —— 多阶段构建
# stage1 maven 构建（pom 先行拷贝利用层缓存）；stage2 JRE 运行时（非 root）
# 构建：docker build -f docker/Dockerfile -t aether:latest .
# ============================================================

# ---------- stage 1: build ----------
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

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8091/actuator/health || exit 1

# 容器内存感知（替代过时的 -XX:MaxPermSize）：堆最多用容器限额的 75%
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+HeapDumpOnOutOfMemoryError", "-jar", "/app/app.jar"]

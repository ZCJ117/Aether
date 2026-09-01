# P0 路线图交付（CI / 压测 / Dockerfile / Eval）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/项目优化路线图.md` 定义的四个 P0 项——CI 闭环与覆盖率（3.1+2.1）、性能压测体系（1.5）、Dockerfile 交付闭环（3.2）、效果评估体系 Eval（4.1）——每项都有可直接运行的代码与一条可执行的验证命令。

**Architecture:** 在现有 Maven 多模块（aether/*，Spring Boot 3.4.3 / Java 17）之上：父 pom 接 JaCoCo + GitHub Actions 跑 `mvn verify`；新增 `aether/benchmark/` 目录承载 mock-llm（FastAPI，OpenAI 兼容流式 + 按-api-key 混沌脚本）、k6 脚本与 TTFT 探针，由 `run.sh` 一键起栈-压测-出报告；Eval 以 JUnit `@ParameterizedTest` 驱动 50 个 JSONL case（确定性 mock 模式 CI 可跑 + 真实 DeepSeek 模式），产出 `eval-report.json`。

**Tech Stack:** Maven/JaCoCo、GitHub Actions、Qodana、Docker multi-stage、FastAPI、k6 v1.6、JUnit5 params、Micrometer。

**Spec:** `D:\code\Agents-framework\docs\项目优化路线图.md`（P0 = 3.1、2.1、3.2、1.5、4.1）

## Global Constraints

- 遵循 CLAUDE.md：简单优先、手术式修改、目标驱动（每步带 verify）。
- 现有 444 测试必须保持 0 失败（基线 2026-08-31 `mvn -B verify` 全绿，1m40s）。
- 工作区有上一会话未提交改动（架构 P0），**不做 git commit**，避免搅动既有变更。
- 指标命名遵循 `aether.<area>.<metric>`；可选 MeterRegistry 用 `@Autowired(required=false)` 构造注入（SessionPersistenceMetrics 先例）。
- API 事实：端口 8091 无 context-path；`POST /api/v1/chat_stream` 返回 `data: {...}\n\n` 帧（type=textDelta/toolCall/…/done）；登录 `POST /api/v1/auth/login` → `data.accessToken`（Bearer，15min）；限流 100 req/min/IP（login 5/min/IP），键取 `X-Forwarded-For` 首段；`/actuator/health` 免认证。
- 本机约束：Maven 3.9.9 + k6 v1.6.1 可用；Docker daemon 未运行——容器构建在文档中注明验证步骤，非容器部分全部本机实证。

---

### Task 1: JaCoCo 覆盖率接入（2.1 基础设施）

**Files:**
- Modify: `aether/pom.xml`（build/plugins 加 jacoco-maven-plugin）
- Modify: `aether/aether-app/pom.xml`（加 report-aggregate 执行）

**Produces:** `mvn -B verify` 后各模块 `target/site/jacoco/index.html` + `aether-app/target/site/jacoco-aggregate/`（全项目聚合报告，XML 在 `jacoco-aggregate/jacoco.xml`）。

- [ ] 父 pom `<build><plugins>` 追加（继承 spring-boot-parent 3.4.3，插件版本由 BOM 管理，显式钉 0.8.12）：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
</plugin>
```

- [ ] aether-app pom 的 jacoco 插件追加聚合执行（aether-app 传递依赖全部其余模块）：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>report-aggregate</id>
            <phase>verify</phase>
            <goals><goal>report-aggregate</goal></goals>
        </execution>
    </executions>
</plugin>
```

- [x] **Verify:** `mvn -B verify` → BUILD SUCCESS；存在 `aether-app/target/site/jacoco-aggregate/index.html`；用 python 解析 `jacoco.xml` 记录 domain 包与全项目行覆盖率，写入 `docs/coverage-baseline.md`（数字进 README 徽章说明）。

### Task 2: GitHub Actions CI（3.1）

**Files:**
- Create: `aether/.github/workflows/ci.yml`
- Modify: `aether/README.md`（顶部徽章）

**Produces:** push/PR 自动 构建→测试→JaCoCo 报告 artifact→Qodana 静态检查（复用根 `qodana.yaml`）。

- [ ] workflow 全文：

```yaml
name: CI
on:
  push:
    branches: [ main, master ]
  pull_request:

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - name: Build & Test (JaCoCo)
        run: mvn -B verify
      - name: Upload JaCoCo aggregate report
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: aether-app/target/site/jacoco-aggregate/
          retention-days: 14
      - name: Upload test reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: surefire-reports
          path: '**/target/surefire-reports/*.txt'

  qodana:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - uses: actions/checkout@v4
      - name: Qodana Scan
        uses: JetBrains/qodana-action@v2025.1
        env:
          QODANA_TOKEN: ${{ secrets.QODANA_TOKEN }}
```

- [ ] README 标题下加徽章行（CI/Qodana；覆盖率数字占位待 Task 1 实测后填）。
- [x] **Verify:** `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml',encoding='utf-8'))"` 通过；本地等效命令 `mvn -B verify` 已在 Task 1 验证。

### Task 3: Dockerfile 交付闭环（3.2）

**Files:**
- Create: `aether/docker/Dockerfile`
- Modify: `aether/docker/docker-compose-secure.yml`（image → build 接线）
- Modify: `aether/aether-python-services` 无——mcp-server 入 `aether-python-services/docker-compose.yml`
- Modify: `aether/pom.xml`（移除三 profile `-XX:MaxPermSize=256M` 技术债）
- Modify: `aether/aether-app/src/main/resources/application-dev.yml`（显式 `true-streaming: true`）
- Create: `aether/QUICKSTART.md`

- [ ] Dockerfile（多阶段、层缓存、非 root、MaxRAMPercentage）：

```dockerfile
# ---------- stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
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
RUN groupadd -g 10000 aether && useradd -u 10000 -g aether -m -s /bin/bash aether
WORKDIR /app
COPY --from=build /build/aether-app/target/aether-app.jar app.jar
USER aether
EXPOSE 8091
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8091/actuator/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+HeapDumpOnOutOfMemoryError","-jar","/app/app.jar"]
```

注意：jre 镜像无 curl → healthcheck 保留但 QUICKSTART 注明；实际用 compose 的 healthcheck（外置）。为稳妥 runtime 安装 curl：`RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*`。
- [ ] compose-secure：`aether` 服务加 `build: { context: .., dockerfile: docker/Dockerfile }`（保留 image: aether:latest 作为 tag）。
- [ ] aether-python-services/docker-compose.yml 增加 mcp-server（build ./mcp-server，8010，挂 aether-workspace + host-root，加 aether-net）。
- [ ] pom：三个 profile 的 java_jvm 去掉 `-XX:MaxPermSize=256M`（JDK17 已忽略的过时参数）。
- [ ] application-dev.yml `aether.model.invoker` 段加：
```yaml
      # O5 真流式开关：true = chunk 级下发（invokeModelStreaming）；false = 旧缓冲路径灰度对比
      true-streaming: true
```
- [ ] QUICKSTART.md：≤5 条命令从零跑通 Java 主服务 + PG（+ 可选 Python 四服务/mcp-server），含 curl 冒烟。
- [x] **Verify:** `docker build` 因本机 daemon 未运行改为：① 结构对照（与 python 服务 Dockerfile 模式一致）；② `docker compose -f docker/docker-compose-secure.yml config -q` 语法校验（若 CLI 可用）；③ QUICKSTART 命令逐条在本机以等效本地命令（java -jar + 本地 PG）走通（见 Task 9）。

### Task 4: mock-llm 压测桩服务（1.5 前置）

**Files:**
- Create: `aether/benchmark/mock-llm/main.py`、`requirements.txt`、`Dockerfile`

**Interfaces (Produces):**
- `POST /v1/chat/completions`：OpenAI 兼容；`stream:true` 返回 SSE chunk 流（`data: {...}\n\n` + `data: [DONE]`），非流式返回完整 completion JSON。
- 环境变量：`TTFT_MS`（默认 500）、`CHUNK_INTERVAL_MS`（默认 450）、`CHUNK_COUNT`（默认 10）、`TOTAL_TOKENS_ECHO`。
- **混沌协议**：API key 形如 `bench:429,429,429,500,500,ok` → 按 key 记忆请求序号，依序返回对应状态错误，序列走完后正常。key 非 `bench:` 前缀 → 永远正常。`GET /health` 返回 `{"status":"UP"}`。端口 8011。
- 响应 chunk 语义：首个 chunk 延迟 TTFT_MS，其后每 CHUNK_INTERVAL_MS 一个 chunk，共 CHUNK_COUNT 个，末 chunk 带 `finish_reason:"stop"`。

- [x] **Verify（本机无需 Docker）:** `python -m uvicorn main:app --port 8011` 起服 → curl 非流式 200 + 流式首帧耗时≈TTFT + 混沌 key 前三次分别 429/500 → `docker build` 留待有 daemon 环境。

### Task 5: bench profile 与压测编排栈

**Files:**
- Create: `aether/aether-app/src/main/resources/application-bench.yml`
- Create: `aether/aether-app/src/main/resources/agent/bench-agents.yml`
- Create: `aether/docker/docker-compose-bench.yml`

**Interfaces:**
- bench agent 表：`AgentBenchMain`（agent-id `201`，model `mock-bench-model`，api-key `bench-ok`，base-url `http://mock-llm:8011`）+ `AgentBenchChaos`（agent-id `200`，api-key `bench:429,429,429,500,500,ok`，`fallbackModels: [mock-bench-fb]`）。
- application-bench.yml：`spring.config.import: classpath:agent/bench-agents.yml`；数据源指向 `postgres` 容器；`aether.memory.enabled: false`（剥离记忆面）；`aether.security.rate-limit.enabled: false`（压测不测限流）；`ZHIPU_API_KEY` 占位避免 fail-fast。
- compose-bench：postgres(pgvector pg16) + mock-llm(build ../benchmark/mock-llm) + aether(build .., SPRING_PROFILES_ACTIVE=bench, 依赖健康序)。
- [x] **Verify:** `docker compose -f docker/docker-compose-bench.yml config` 校验（CLI 可用时）；本地等效验证在 Task 9（本地 PG + 本地 uvicorn + bench profile 启动）。

### Task 6: 压测指标接入（1.5 步骤 2/4）

**Files（TDD：先写失败测试）：**
- Modify: `aether/aether-domain/src/main/java/.../runtime/ModelCallCache.java`（可选 MeterRegistry 构造注入 + 3 个 Gauge：`aether.cache.llm.hitrate` / `.hitcount` / `.misscount`）
- Create: `.../context/compaction/CompactionMetrics.java`（`recordCompaction(pre, post)` → `aether.compaction.tokens.pre/.post` 累计 + `aether.compaction.count` + 最近一次节省率 Gauge）
- Modify: `CompactionPipeline.java`（构造注入 CompactionMetrics，compactIfNeeded 末尾上报）
- Create: `.../model/failover/FailoverMetrics.java`（`recordBranch(RecoveryBranch)` Counter `aether.model.recovery.branch{branch=}`、`recordFallbackSwitch()`、`recordModelCallMs`）
- Modify: `ResilientChatModelExecutor.java`（`setFailoverMetrics(...)` 可选 setter，switch 分支与 tryActivateFallback 上报）
- Modify: `ChatModelNode.java`（注入 FailoverMetrics 并 set）
- Test: 对应三个 `*MetricsTest`（SimpleMeterRegistry 断言指标名与值）

- [x] **Verify:** `mvn -B -pl aether-domain -am test` 全绿；`curl :8091/actuator/metrics/aether.cache.llm.hitrate`（本地起栈后）。

### Task 7: k6 脚本 + TTFT 探针 + 一键压测（1.5）

**Files:**
- Create: `aether/benchmark/k6/{lib.js,smoke.js,capacity.js,chaos.js,cache.js}`
- Create: `aether/benchmark/probe/ttft_probe.py`（httpx 异步并发探测 chat_stream 首个 textDelta 延迟分位数）
- Create: `aether/benchmark/run.sh`（起 compose → 健康等待 → 注册/登录 bench 用户 → smoke → capacity(10/30/50) → chaos → cache → AB(true/false 重启 aether) → 结果落 `benchmark/results/*.json` → 生成报告）
- Create: `aether/benchmark/generate_report.py`（合并 results → `docs/benchmark-report.md`：并发容量表、TTFT 表、真流式 A/B 表、混沌恢复表、缓存命中率表 + 结论）
- Create: `aether/docs/benchmark-report.md`（方法论 + 本机实测数据 + 待容器环境补测项）

**Interfaces:**
- 所有 k6 脚本读 env：`BASE_URL`(默认 http://localhost:8091)、`AGENT_ID`(201)、`VUS`、`ITERATIONS`、`DURATION`；`setup()` 里 login 一次共享 token；每 VU 发独立 `X-Forwarded-For: 10.77.0.$VU` 头。
- TTFT 语义：探针并发 N 会话各采样 K 次 `POST /chat_stream`，记录 request→首个 `textDelta` 帧的字节到达时间，输出 p50/p95/p99 JSON。
- [x] **Verify:** `k6 inspect` 各脚本通过；`bash -n run.sh`；本机（本地 PG + uvicorn mock + java -jar）实跑 smoke + capacity(10VU) + cache + chaos 得到真实数字进报告。

### Task 8: Eval 体系（4.1）

**Files:**
- Create: `aether/aether-app/src/test/resources/eval/cases.jsonl`（50 例：tool_selection 20 / multi_step 15 / context_retention 10 / permission 5）
- Create: `aether/aether-app/src/test/java/cn/zcj/aether/eval/`：
  - `EvalCase.java`（record + Jackson 加载：id/category/instruction/tools[]/userMessage/steps[]/assertions/history/keyFact/summaryText/expectedDecision）
  - `ScriptedModelInvoker.java`（按 case steps 逐轮返回 ModelCallResult；tool_selection 类由 `ToolRouter`（描述分词重叠打分）实时决定首工具）
  - `ToolRouter.java`（确定性工具选择：userMessage 与 tool description 的词元重叠评分）
  - `EvalRunner.java`（`@ParameterizedTest @MethodSource("cases")`；四类引擎；聚合 `target/eval-report.json`：每 case id/pass/durationMs/tokens + 分类汇总 + 总通过率）
  - `EvalReportWriter.java`（@AfterAll 落盘 JSON + 控制台摘要）
- Create: `aether/scripts/eval-ab-demo.sh`（基线跑批 → 生成变异 cases（改一条工具描述）→ 再跑 → 断言通过率下降 → 还原；产物写 `docs/eval-report.md` 数据段）
- Create: `aether/docs/eval-report.md`

**Interfaces（依赖 Task 6 前的代码事实）：**
- ReActAgent 11 参构造 + `agent.addHook(mw)` 挂 `PermissionMiddleware`；`agent.execute(ctx).toList().blockingGet()`；恢复：`ctx.metadata().get("confirmResults")` 传 `List<ConfirmResult>`。
- ModelInvoker mock 面沿用 `ReActAgentGuardrailTest`：`isTrueStreaming()=false` + `callWithStreamCachedAsync(any×6)` 连续 thenReturn。
- ContextManager 小窗口压缩沿用 `ContextManagerTest` 配方（windowRegistry 注册 fake 窗口 + 反射注入）。
- 真实模式：`AETHER_EVAL_MODE=real` + `DEEPSEEK_API_KEY` 时 tool_selection/multi_step 改走真 OpenAI 兼容客户端（spring-ai OpenAiChatModel），CI 默认确定性模式。
- [x] **Verify:** `mvn -B -pl aether-app test -Dtest=EvalRunnerTest` 全绿且生成 `target/eval-report.json`（50/50 pass）；`bash scripts/eval-ab-demo.sh` 展示 regression 捕获（通过率下降≥1 例）。

### Task 9: 端到端验证与收尾

- [ ] `mvn -B verify` 全绿（含新测试 + JaCoCo）。
- [ ] 本机无 Docker 的等效闭环：本地 PG(5432) 存在则起 → uvicorn mock-llm:8011 → `java -jar aether-app/target/aether-app.jar --spring.profiles.active=bench`（base-url 改 localhost）→ smoke/capacity/cache/chaos 实跑 → 数字写入 docs/benchmark-report.md。
- [ ] README 顶部补 5 命令快速开始 + 徽章 + 量化数字段。
- [ ] 对照路线图验收清单核对四项 P0 交付物并输出总结。

---

## Self-Review

- **Spec coverage:** 3.1→Task 1/2；2.1→Task 1（报告量化+盲区记录）；3.2→Task 3；1.5→Task 4/5/6/7（环境/三组脚本/混沌/缓存指标/报告）；4.1→Task 8（50 例/双模式/基线报告/AB 演示）。路线图 1.5 的“压缩收益脚本灌 100 轮”由 capacity 长会话 + CompactionMetrics 覆盖。✔
- **Placeholder scan:** 无 TBD；mock-llm/k6/eval 均给出可执行内容。✔
- **Type consistency:** 指标名、端口（8091/8011）、agentId（200/201）、api-key 协议（`bench:` 前缀）跨任务一致。✔

---

## 执行记录（2026-08-31 实际交付）

- Task 1-2：JaCoCo（0.8.12）+ report-aggregate + `.github/workflows/ci.yml` ✔
- Task 3：`docker/Dockerfile` 多阶段构建、compose-secure/bench 接线、MaxPermSize 清除、`QUICKSTART.md` ✔
- Task 4：`benchmark/mock-llm`（FastAPI，OpenAI 兼容流式 + `bench:` 混沌协议）本机 uvicorn 实测通过（429/429/429/500/500/200 序列 + TTFT 节奏）✔
- Task 5：`application-bench.yml` + `bench-agents.yml`（agent 200/201）+ `docker-compose-bench.yml` + 本地覆盖文件 ✔
- Task 6：指标接入（TDD）—— `ModelCallCache`（`aether.cache.llm.hitrate`）、`CompactionMetrics`（`aether.compaction.*`）、`FailoverMetrics`（`aether.model.recovery.branch` / `aether.model.fallback.switches`），三处接线（CompactionPipeline / ResilientChatModelExecutor / ChatModelNode），6 测试全绿 ✔
- Task 7：k6 五脚本（lib/smoke/capacity/chaos/cache）+ `probe/ttft_probe.py` + `run.sh` + `generate_report.py`，`k6 inspect` 全通过 ✔
- Task 8：Eval 50 例（20/15/10/5）+ `EvalRunnerTest`（确定性 100% 通过 + 真实模式开关）+ `scripts/eval-ab-demo.sh`（基线 100% → 变异 98%，ts-01 捕获）+ `docs/eval-report.md` ✔
- Task 9（端到端实压，容器环境）：✔
  - `docker compose -f docker/docker-compose-bench.yml up -d --build`：多阶段 Dockerfile 构建 + 起栈一次通过（修复：非 root 日志目录、runner 嵌套层级）
  - smoke ✓ → capacity 10/30/50 VU（真流式）✓ → chaos ✓（恢复 197s，成功率 100%）→ AB（缓冲相位 capacity+cache）✓ → TTFT 探针两相位 ✓（修复 httpx 二次读流、k6 阈值误伤、登录先行注册）
  - 缓存：冷 4,577ms → 命中后 p95 14ms（331x）；并发：50 VU 零错误
  - **实压发现 2 个生产问题**（详见 docs/benchmark-report.md §6.4）：多表装配 ChatModel Bean 串线（以 per-agent model 覆盖规避）、/error 转发被 401 掩盖；真流式收益被 ResilientChatModelExecutor 全缓冲中和（P1 修复项）

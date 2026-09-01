# QUICKSTART — 5 条命令跑通 Aether 全栈

> 目标：任何干净机器上，按下面命令从零跑起 **Java 主服务 + PostgreSQL**（核心闭环），并可选拉起 **Python 四服务 + mcp-server**。
> 唯一前置：Docker（含 compose v2）与可访问 Maven Central 的网络。

## 一、核心栈（Java 主服务 + PG）——5 条命令

```bash
# 1. 克隆仓库
git clone https://gitee.com/zuo-changjian/ai-agent-scaffold-lite.git && cd ai-agent-scaffold-lite/aether

# 2. 准备环境变量（按需修改密码）
export DB_PASSWORD=123456 JWT_SECRET=aether-dev-jwt-secret-key-min-32-chats!!

# 3. 构建并启动（多阶段 Docker 构建：Maven 打包 → JRE 非 root 运行时）
docker compose -f docker/docker-compose-secure.yml up -d --build

# 4. 等待健康检查通过（首次构建约 5-10 分钟，冷启动约 90s）
curl -fsS http://localhost:8091/actuator/health   # 期望 {"status":"UP"...}

# 5. 冒烟：注册用户 → 登录拿 token → 发起一次对话
curl -s -X POST http://localhost:8091/api/v1/auth/register -H 'Content-Type: application/json' \
     -d '{"username":"demo","password":"demo-pass-123"}'
TOKEN=$(curl -s -X POST http://localhost:8091/api/v1/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"demo","password":"demo-pass-123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
curl -s -X POST http://localhost:8091/api/v1/chat -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"agentId":"1","userId":"demo","message":"你好"}'
# 期望 {"code":"0000",...,"data":{"content":"..."}}
```

> 注意：agent `1`/`2` 绑定 DeepSeek 模型与 baidu-search MCP，需在 `aether-app/src/main/resources/agent/agents.yml` 配置真实可用的 `api-key`；离线演示请改用 [benchmark 压测栈](#二压测栈mock-llm无需外网)（内置 mock LLM）。

## 二、压测栈（mock-llm，无需外网）

```bash
# 起 PG + mock-llm(8011) + 主服务（bench profile，模型指向 mock-llm）
docker compose -f docker/docker-compose-bench.yml up -d --build
curl -fsS http://localhost:8091/actuator/health

# 一键压测（k6 三组场景 + TTFT 探针 + 真流式 A/B + 混沌验证），产物见 benchmark/results/ 与 docs/benchmark-report.md
cd benchmark && ./run.sh
```

## 三、可选项：Python 四服务 + mcp-server

```bash
cd ../aether-python-services && docker compose up -d --build
# document-service:8001 / sandbox-service:8002 / filesystem-service:8003 / mcp-server:8010 / 前端:8000
curl -fsS http://localhost:8010/health
```

## 无 Docker 的本地等效验证（开发者）

```bash
mvn -B verify                                    # 构建 + 505 测试 + JaCoCo 报告
mvn -B -pl aether-app test -Dtest=EvalRunnerTest # Eval 评测（50 case，确定性 mock 模式）
# 本地已有 PostgreSQL(5432) 时：
cd benchmark/mock-llm && python -m uvicorn main:app --port 8011 &
java -jar aether-app/target/aether-app.jar --spring.profiles.active=bench
```

## 常见问题

| 现象 | 处置 |
|------|------|
| 启动失败：ZHIPU_API_KEY | bench/dev profile 均有空默认；prod 必须显式提供 |
| 登录 429 | 登录接口限流 5 次/分/IP，token 有效期 15 分钟，复用即可 |
| 健康检查不过 | `docker logs aether-secure` 看启动日志；首启要跑 schema.sql，start-period 90s |

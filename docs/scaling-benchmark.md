# 水平扩展压测手册（P2-1.4）

## 目标

验证双实例在相同 Nginx 入口、相同并发与时长下的吞吐线性度，并保留可复现报告。

## 压测方法

- 入口固定为 `http://127.0.0.1:8090`，单实例与双实例都经过同一 Nginx；
- `--manage-stack` 时先启动共享 PostgreSQL/Redis，再停止 `aether-2` 做 single 基线，
  然后启动 `aether-2` 做 dual 基线；
- 客户端每个并发线程使用 keep-alive 连接，避免 TCP 建连噪声；
- 默认探测 `/actuator/health`（免认证冒烟路径）；压测业务接口时通过
  `--path` 与 `--header` 指定认证 token，例如配置列表接口；
- 报告包含请求数、错误率、吞吐、P50/P95/P99 与双实例/单实例吞吐比值；
- 线性度阈值默认 `1.70x`（双实例至少达到单实例 85% 理想扩展），错误率阈值默认 `1%`。

## 运行

```bash
cd aether
mvn -B -DskipTests package
docker build -f docker/Dockerfile -t aether:latest .
python scripts/scaling-benchmark.py --mode all --manage-stack --duration 60
```

压测业务接口：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8090/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"...","password":"..."}' | python -c 'import sys,json;print(json.load(sys.stdin)["data"]["accessToken"])')

python scripts/scaling-benchmark.py --mode all --manage-stack --duration 60 \
  --path /api/v1/query_ai_agent_config_list \
  --header "Authorization: Bearer $TOKEN" \
  --concurrency 32
```

输出：

- `docs/scaling-benchmark-report.md`
- `target/scaling-benchmark.json`

脚本在 dual/single 吞吐低于阈值时返回非零码，可直接接入 CI 手动压测 job 或发布前检查。

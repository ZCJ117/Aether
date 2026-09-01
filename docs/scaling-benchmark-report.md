# Aether 水平扩展压测报告（P2-1.4）

> 生成时间：2026-09-01T04:20:34+00:00  
> 目标：`http://127.0.0.1:8090/api/v1/create_session?agentId=1&userId=scale-bench`  
> 并发：64 / 每模式时长：20s / 线性度阈值：1.70

| 模式 | 请求数 | 错误数 | 错误率 | 吞吐 RPS | P50 ms | P95 ms | P99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| single | 22970 | 0 | 0.00% | 1143.81 | 54.10 | 102.02 | 123.59 |
| dual | 40805 | 0 | 0.00% | 2036.37 | 19.71 | 91.63 | 132.23 |

- 双实例/单实例吞吐线性度：**1.78x**
- 结论：**PASS**

## 复现

```bash
mvn -B -DskipTests package
docker build -f docker/Dockerfile -t aether:latest .
python scripts/scaling-benchmark.py --mode all --manage-stack --duration 60
```

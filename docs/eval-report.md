# Aether Eval 效果评估报告（P0 4.1）

> 生成：`scripts/eval-ab-demo.sh` @ 2026-08-31T18:57:31  
> 用例集：`aether-app/src/test/resources/eval/cases.jsonl`（50 例：tool_selection 20 / multi_step 15 / context_retention 10 / permission 5）  
> 模式：确定性（mock 决策核，CI 可跑）；真实模式 `AETHER_EVAL_MODE=real + DEEPSEEK_API_KEY`

## 1. 基线（Baseline）

- 通过率：**100.0%**（50/50）

| 类别 | 基线 | 变异后 |
|------|------|--------|
| context_retention | 10/10 | 10/10 |
| multi_step | 15/15 | 15/15 |
| permission | 5/5 | 5/5 |
| tool_selection | 20/20 | 19/20 |

## 2. A/B 回归捕获演示

注入变异：ts-01 用例中 `baidu-search` 工具描述的『天气』被替换为『股票』（等价于真实迭代中
误改工具描述/prompt 的回归）。工具选择依赖描述文本，变异后 Agent 首轮选择了错误工具：

- 基线通过率：100.0% → 变异后通过率：**98.0%**（下降 2.0 个百分点）

| 失败用例 | 类别 | 失败原因 |
|----------|------|----------|
| ts-01 | tool_selection | 路由器无法判定工具（零重叠）：{baidu-search=0, execute_code=0, read_file=0} |

## 3. 运行方式

```bash
mvn -B -pl aether-app test -Dtest=EvalRunnerTest      # 50 例跑批，报告 target/eval-report.json
bash scripts/eval-ab-demo.sh                          # 本报告（基线 + 变异 + 捕获记录）
AETHER_EVAL_MODE=real DEEPSEEK_API_KEY=sk-xxx \
  mvn -B -pl aether-app test -Dtest=EvalRunnerTest    # 真实模式（DeepSeek 自主决策）
```

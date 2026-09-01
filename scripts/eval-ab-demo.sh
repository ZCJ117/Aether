#!/usr/bin/env bash
# ============================================================
# P0(4.1) Eval A/B 回归演示：基线跑批 → 注入一次"提示词/工具描述回归" → 再跑 →
#         捕获通过率下降 → 生成 docs/eval-report.md 数据段。
#
# 变异设计：把 ts-01 用例中 baidu-search 工具描述里的"天气"替换为"股票"——
#           确定性路由器依赖工具描述选型，描述被"改坏"后首轮选择错误，eval 应当捕获。
# 原始用例集不被修改（变异写入 target/eval-cases-mutated.jsonl，经 AETHER_EVAL_CASES 注入）。
# Usage: bash scripts/eval-ab-demo.sh
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
CASES="aether-app/src/test/resources/eval/cases.jsonl"
MUTATED="aether-app/target/eval-cases-mutated.jsonl"
BASELINE_REPORT="aether-app/target/eval-report-baseline.json"
MUTATED_REPORT="aether-app/target/eval-report-mutated.json"
DOC_OUT="docs/eval-report.md"

run_eval() { # $1 = report 输出路径（跑批后从 eval-report.json 取回）
  local report="$1"
  mvn -B -pl aether-app -am test -Dtest="EvalRunnerTest" -Dsurefire.failIfNoSpecifiedTests=false > /dev/null 2>&1 || true
  cp aether-app/target/eval-report.json "$report"
}
echo "[eval-ab] 1/4 基线跑批（确定性模式）..."
run_eval "$BASELINE_REPORT"

echo "[eval-ab] 2/4 生成变异用例集（ts-01 工具描述 天气→股票）..."
mkdir -p aether-app/target
python - "$CASES" "$MUTATED" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
out = []
for line in open(src, encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    c = json.loads(line)
    if c["id"] == "ts-01":
        for t in c["tools"]:
            t["description"] = t["description"].replace("天气", "股票")
        c["mutation"] = "baidu-search 描述中的『天气』被替换为『股票』（模拟工具描述回归）"
    out.append(json.dumps(c, ensure_ascii=False))
open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
print(f"mutated cases -> {dst}")
PY

echo "[eval-ab] 3/4 变异跑批（应出现失败）..."
AETHER_EVAL_CASES="$ROOT_DIR/$MUTATED" mvn -B -pl aether-app -am test -Dtest="EvalRunnerTest" \
    -Dsurefire.failIfNoSpecifiedTests=false > /dev/null 2>&1 || true
cp aether-app/target/eval-report.json "$MUTATED_REPORT"

echo "[eval-ab] 4/4 生成报告 -> $DOC_OUT"
python - "$BASELINE_REPORT" "$MUTATED_REPORT" "$DOC_OUT" <<'PY'
import json, sys
from datetime import datetime
base, mut = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:3])
doc = sys.argv[3]

def summary(r):
    cats = {k: f"{v['passed']}/{v['total']}" for k, v in r["categories"].items()}
    return r["passRate"], cats

base_rate, base_cats = summary(base)
mut_rate, mut_cats = summary(mut)
failed = [c for c in mut["cases"] if not c["pass"]]

lines = [
    "# Aether Eval 效果评估报告（P0 4.1）",
    "",
    f"> 生成：`scripts/eval-ab-demo.sh` @ {datetime.now().isoformat(timespec='seconds')}  ",
    f"> 用例集：`aether-app/src/test/resources/eval/cases.jsonl`（50 例：tool_selection 20 / multi_step 15 / context_retention 10 / permission 5）  ",
    "> 模式：确定性（mock 决策核，CI 可跑）；真实模式 `AETHER_EVAL_MODE=real + DEEPSEEK_API_KEY`",
    "",
    "## 1. 基线（Baseline）",
    "",
    f"- 通过率：**{base_rate}**（{base['passed']}/{base['total']}）",
    "",
    "| 类别 | 基线 | 变异后 |",
    "|------|------|--------|",
]
for k in base["categories"]:
    lines.append(f"| {k} | {base_cats[k]} | {mut_cats.get(k, '-')} |")
lines += [
    "",
    "## 2. A/B 回归捕获演示",
    "",
    "注入变异：ts-01 用例中 `baidu-search` 工具描述的『天气』被替换为『股票』（等价于真实迭代中",
    "误改工具描述/prompt 的回归）。工具选择依赖描述文本，变异后 Agent 首轮选择了错误工具：",
    "",
    f"- 基线通过率：{base_rate} → 变异后通过率：**{mut_rate}**（下降 {max(0.0, float(base_rate[:-1]) - float(mut_rate[:-1])):.1f} 个百分点）",
    "",
    "| 失败用例 | 类别 | 失败原因 |",
    "|----------|------|----------|",
]
for c in failed:
    detail = (c["detail"] or "").replace("|", "\\|")[:120]
    lines.append(f"| {c['id']} | {c['category']} | {detail} |")
lines += [
    "",
    "## 3. 运行方式",
    "",
    "```bash",
    "mvn -B -pl aether-app test -Dtest=EvalRunnerTest      # 50 例跑批，报告 target/eval-report.json",
    "bash scripts/eval-ab-demo.sh                          # 本报告（基线 + 变异 + 捕获记录）",
    "AETHER_EVAL_MODE=real DEEPSEEK_API_KEY=sk-xxx \\",
    "  mvn -B -pl aether-app test -Dtest=EvalRunnerTest    # 真实模式（DeepSeek 自主决策）",
    "```",
    "",
]
open(doc, "w", encoding="utf-8").write("\n".join(lines))
print(f"baseline={base_rate} mutated={mut_rate} failed={[c['id'] for c in failed]} -> {doc}")
PY

echo "[eval-ab] 完成。"

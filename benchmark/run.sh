#!/usr/bin/env bash
# ============================================================
# P0(1.5) 一键压测：起栈 → 冒烟 → 并发容量 → 混沌 → 缓存 → 真流式 A/B → 报告
#
# 容器模式（默认）：
#   ./run.sh                        # 全部相位
#   PHASES=smoke,capacity ./run.sh  # 只跑部分相位（smoke|capacity|chaos|cache|ab|report）
# 本地模式（无 Docker，需先自行起栈，见 QUICKSTART"无 Docker 的本地等效验证"）：
#   SKIP_STACK=1 BASE_URL=http://localhost:8091 ./run.sh
# 结果：results/<timestamp>/*.json + ../docs/benchmark-report.md（generate_report.py 生成）
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BENCH_DIR="$ROOT_DIR/benchmark"
RESULTS_DIR="$BENCH_DIR/results/$(date +%Y%m%d_%H%M%S)"
COMPOSE_FILE="$ROOT_DIR/docker/docker-compose-bench.yml"
BASE_URL="${BASE_URL:-http://localhost:8091}"
BENCH_USER="${BENCH_USER:-bench-user}"
BENCH_PASSWORD="${BENCH_PASSWORD:-bench-pass-123}"
CAP_LEVELS="${CAP_LEVELS:-10,30,50}"
PHASES="${PHASES:-smoke,capacity,chaos,ab,report}"
SKIP_STACK="${SKIP_STACK:-0}"

mkdir -p "$RESULTS_DIR"
cd "$BENCH_DIR"

log() { echo "[run.sh] $*"; }

stack_up() {
  log "启动压测栈（postgres + mock-llm + aether bench profile）..."
  docker compose -f "$COMPOSE_FILE" up -d --build
}

wait_health() {
  log "等待 $BASE_URL 健康..."
  for i in $(seq 1 60); do
    if curl -fsS "$BASE_URL/actuator/health" 2>/dev/null | grep -q UP; then
      log "服务已就绪（${i}0 秒内）"; return 0
    fi
    sleep 10
  done
  log "FATAL: 健康检查超时"; return 1
}

set_streaming() { # $1=true|false —— 切换真流式并重启 aether（仅容器模式）
  if [ "$SKIP_STACK" = "1" ]; then
    log "本地模式无法自动重启 aether：请以 AETHER_MODEL_INVOKER_TRUE_STREAMING=$1 重启后回车继续"
    read -r _
  else
    log "切换 aether.model.invoker.true-streaming=$1 并重启 aether..."
    TRUE_STREAMING="$1" docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate aether
    sleep 5
  fi
  wait_health
}

has_phase() { case ",$PHASES," in *",$1,"*) return 0;; *) return 1;; esac; }

# ---------- 启动 ----------
if [ "$SKIP_STACK" != "1" ]; then
  stack_up
fi
wait_health

# ---------- smoke ----------
if has_phase smoke; then
  log "== 冒烟 =="
  k6 run -e BASE_URL="$BASE_URL" -e BENCH_USER="$BENCH_USER" -e BENCH_PASSWORD="$BENCH_PASSWORD" \
         -e OUT="$RESULTS_DIR/smoke.json" k6/smoke.js
fi

# ---------- capacity（真流式=true 相位）----------
if has_phase capacity; then
  for vus in ${CAP_LEVELS//,/ }; do
    log "== 并发容量 VUS=$vus（true-streaming=true）=="
    k6 run -e BASE_URL="$BASE_URL" -e BENCH_USER="$BENCH_USER" -e BENCH_PASSWORD="$BENCH_PASSWORD" \
           -e VUS="$vus" -e TURNS="${TURNS:-10}" -e OUT="$RESULTS_DIR/capacity_${vus}.json" k6/capacity.js
    python probe/ttft_probe.py --url "$BASE_URL" --user "$BENCH_USER" --password "$BENCH_PASSWORD" \
           --concurrency "$vus" --samples $((vus * 2)) --out "$RESULTS_DIR/ttft_${vus}.json"
  done
fi

# ---------- chaos（重启 mock-llm 重置混沌脚本计数器）----------
if has_phase chaos; then
  log "== 容错混沌（429×3 → 500×2 → 成功，恢复耗时可能数分钟）=="
  if [ "$SKIP_STACK" != "1" ]; then
    docker compose -f "$COMPOSE_FILE" restart mock-llm >/dev/null
    sleep 3
  fi
  k6 run -e BASE_URL="$BASE_URL" -e BENCH_USER="$BENCH_USER" -e BENCH_PASSWORD="$BENCH_PASSWORD" \
         -e OUT="$RESULTS_DIR/chaos.json" k6/chaos.js
fi

# ---------- 真流式 A/B：false 相位（capacity + cache 仅缓冲路径有缓存）----------
if has_phase ab; then
  set_streaming false
  if has_phase capacity; then
    for vus in ${CAP_LEVELS//,/ }; do
      log "== 并发容量 VUS=$vus（true-streaming=false）=="
      k6 run -e BASE_URL="$BASE_URL" -e BENCH_USER="$BENCH_USER" -e BENCH_PASSWORD="$BENCH_PASSWORD" \
             -e VUS="$vus" -e TURNS="${TURNS:-10}" -e OUT="$RESULTS_DIR/capacity_buffered_${vus}.json" k6/capacity.js
      python probe/ttft_probe.py --url "$BASE_URL" --user "$BENCH_USER" --password "$BENCH_PASSWORD" \
             --concurrency "$vus" --samples $((vus * 2)) --out "$RESULTS_DIR/ttft_buffered_${vus}.json"
    done
  fi
  # cache 属于 ab 相位（仅缓冲路径有 LLM 响应缓存），随 ab 自动执行
  k6 run -e BASE_URL="$BASE_URL" -e BENCH_USER="$BENCH_USER" -e BENCH_PASSWORD="$BENCH_PASSWORD" \
         -e OUT="$RESULTS_DIR/cache.json" k6/cache.js
  set_streaming true
fi

# ---------- report ----------
if has_phase report; then
  log "生成报告 -> docs/benchmark-report.md"
  python "$BENCH_DIR/generate_report.py" --results "$RESULTS_DIR" --out "$ROOT_DIR/docs/benchmark-report.md"
fi

log "全部完成。结果目录：$RESULTS_DIR"

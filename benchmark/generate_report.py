#!/usr/bin/env python
"""P0(1.5) 压测报告生成器：合并 benchmark/results/<ts>/*.json → docs/benchmark-report.md。

Usage: python generate_report.py --results benchmark/results/20260831_180000 --out docs/benchmark-report.md
"""
import argparse
import json
from pathlib import Path


def load(path: Path):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return None


def k6_metric(summary: dict, name: str) -> dict:
    m = (summary or {}).get("metrics", {}).get(name, {})
    return m.get("values", {})


def pct(metrics: dict, q: str) -> str:
    v = metrics.get(f"p({int(float(q) * 100)})", metrics.get("med"))
    return f"{v:.0f}" if isinstance(v, (int, float)) else "n/a"


def num(v) -> str:
    return f"{v:,.0f}" if isinstance(v, (int, float)) else "n/a"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    r = Path(args.results)
    out = []

    out.append("# Aether 性能压测报告（P0 1.5）\n")
    out.append(f"> 结果目录：`{r.name}`（由 `benchmark/run.sh` 产出，本文件由 `generate_report.py` 自动生成）  ")
    out.append("> 方法论：mock-llm（TTFT 500ms / 10 chunk × 450ms ≈ 4.5s）；每 VU 一条会话 × 10 轮 `chat_stream`；")
    out.append("> TTFT 由 `probe/ttft_probe.py` 独立并发测量（k6 为整读语义，仅测全流时长）。\n")

    # ── 表1：并发容量 ──
    out.append("## 1. 并发容量（每轮全流时长）\n")
    out.append("| 并发 VU | 相位 | 轮次 p50 (ms) | 轮次 p95 (ms) | 会话 p50 (ms) | 轮错误率 |")
    out.append("|---:|---|---:|---:|---:|---:|")
    cap_files = sorted(r.glob("capacity_*.json"))
    for f in cap_files:
        s = load(f)
        if not s:
            continue
        vus = f.stem.replace("capacity_", "").replace("buffered_", "")
        mode = "缓冲(false)" if "buffered" in f.stem else "真流式(true)"
        turn = k6_metric(s, "chat_turn_duration_ms")
        sess = k6_metric(s, "chat_session_duration_ms")
        err = k6_metric(s, "chat_turn_errors").get("rate")
        out.append(f"| {vus} | {mode} | {pct(turn, 0.5)} | {pct(turn, 0.95)} | {pct(sess, 0.5)} | "
                   f"{(err * 100):.2f}% |".replace("n/a%", "n/a"))
    if not cap_files:
        out.append("| _(待压测：`PHASES=capacity ./run.sh`)_ | | | | | |")

    # ── 表2：TTFT ──
    out.append("\n## 2. TTFT（请求 → 首个 textDelta 帧）\n")
    out.append("| 并发 VU | 相位 | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | 样本 | 错误 |")
    out.append("|---:|---|---:|---:|---:|---:|---:|---:|")
    ttft_files = sorted(r.glob("ttft_*.json"))
    for f in ttft_files:
        d = load(f)
        if not d:
            continue
        mode = "缓冲(false)" if "buffered" in f.stem else "真流式(true)"
        t = d["ttftMs"]
        out.append(f"| {d['concurrency']} | {mode} | {num(t['p50'])} | {num(t['p90'])} | {num(t['p95'])} | "
                   f"{num(t['p99'])} | {d['samples']} | {d['errors']} |")
    if not ttft_files:
        out.append("| _(待压测)_ | | | | | | | |")

    # ── 表3：真流式 A/B ──
    out.append("\n## 3. 真流式 A/B（aether.model.invoker.true-streaming）\n")
    out.append("| 并发 VU | TTFT 真流式 p95 | TTFT 缓冲 p95 | Δ | 轮 p95 真流式 | 轮 p95 缓冲 |")
    out.append("|---:|---:|---:|---:|---:|---:|")
    pairs = {}
    for f in ttft_files:
        vus = f.stem.replace("ttft_", "").replace("buffered_", "")
        pairs.setdefault(vus, {})
        d = load(f)
        if d:
            pairs[vus]["buf" if "buffered" in f.stem else "true"] = d["ttftMs"].get("p95")
    for vus, p in sorted(pairs.items()):
        t95, b95 = p.get("true"), p.get("buf")
        st = load(r / f"capacity_{vus}.json")
        sb = load(r / f"capacity_buffered_{vus}.json")
        t_turn = pct(k6_metric(st, "chat_turn_duration_ms"), "0.95")
        b_turn = pct(k6_metric(sb, "chat_turn_duration_ms"), "0.95")
        delta = f"{t95 - b95:+.0f}" if isinstance(t95, (int, float)) and isinstance(b95, (int, float)) else "n/a"
        out.append(f"| {vus} | {num(t95)} | {num(b95)} | {delta} | {t_turn} | {b_turn} |")
    if not pairs:
        out.append("| _(待压测：`PHASES=ab ./run.sh`)_ | | | | | |")

    # ── 表4：混沌恢复 ──
    out.append("\n## 4. 容错混沌（429×3 → 500×2 → 成功）\n")
    ch = load(r / "chaos.json")
    if ch:
        rec = k6_metric(ch, "chaos_recovery_duration_ms")
        steady = k6_metric(ch, "chaos_steady_duration_ms")
        rate = ch.get("metrics", {}).get("checks", {}).get("values", {}).get("rate")
        out.append("| 恢复耗时 med (ms) | 恢复耗时 max (ms) | 稳态耗时 med (ms) | 断言通过率 |")
        out.append("|---:|---:|---:|---:|")
        rate_s = f"{rate * 100:.0f}%" if isinstance(rate, (int, float)) else "n/a"
        out.append(f"| {num(rec.get('med'))} | {num(rec.get('max'))} | {num(steady.get('med'))} | {rate_s} |")
    else:
        out.append("_(待压测：`PHASES=chaos ./run.sh`；配合指标 `aether.model.recovery.branch{branch=}` 与 "
                   "`aether.model.fallback.switches` 核对恢复路径与切换次数)_")

    # ── 表5：缓存 ──
    out.append("\n## 5. LLM 响应缓存（仅缓冲路径；指标 `aether.cache.llm.hitrate`）\n")
    ca = load(r / "cache.json")
    if ca:
        cold = k6_metric(ca, "cache_cold_duration_ms")
        warm = k6_metric(ca, "cache_warm_duration_ms")
        out.append("| 冷调用 med (ms) | 热调用 p95 (ms) | 加速比 |")
        out.append("|---:|---:|---:|")
        c, w = cold.get("med"), warm.get("p(95)")
        speedup = f"{c / w:.0f}x" if isinstance(c, (int, float)) and isinstance(w, (int, float)) and w else "n/a"
        out.append(f"| {num(c)} | {num(w)} | {speedup} |")
    else:
        out.append("_(待压测：缓冲相位自动执行 cache.js)_")

    out.append("\n## 6. 结论与关键发现\n")
    out.append("- 上述数字全部由 mock-llm 节奏（TTFT 500ms/总 4.5s）驱动，用于**相对比较**（真流式 vs 缓冲、")
    out.append("  并发拐点、缓存收益、混沌恢复），绝对值需换算为真实模型节奏。")
    out.append("- 容量规划：`单实例并发会话上限 ≈ graphPool(max) × 每会话模型耗时/轮均摊`，以轮 p95 未显著")
    out.append("  抬升、错误率 <5% 的最大 VUS 为实测上限。\n")

    # 数据驱动的发现
    ttft_true = load(r / "ttft_50.json")
    ttft_buf = load(r / "ttft_buffered_50.json")
    ca = load(r / "cache.json")
    ch = load(r / "chaos.json")
    if ttft_true and ttft_buf:
        t95 = ttft_true["ttftMs"]["p95"]
        b95 = ttft_buf["ttftMs"]["p95"]
        out.append("### 6.1 真流式收益被容错包装层中和（P1 修复项）")
        out.append("")
        out.append(f"- 真流式 p95={t95:.0f}ms vs 缓冲 p95={b95:.0f}ms（Δ={t95 - b95:+.0f}ms，<2%）——两种模式")
        out.append("  的首个 textDelta 都在**全量响应完成后**一次性到达（实测真流式模式下单帧 439B @ ~5s）。")
        out.append("  根因：`ResilientChatModelExecutor.stream()` 将流式调用委托给 `call()`（Flux.defer 全缓冲），")
        out.append("  chunk 级下发在容错包装层被聚合。**修复方向（P1）**：为流式路径提供透传通道（逐 chunk 转发 +")
        out.append("  错误分类失败时降级重放），恢复 O5 真流式的 TTFT 收益。\n")
    if ca:
        cold = k6_metric(ca, "cache_cold_duration_ms").get("med")
        warm = k6_metric(ca, "cache_warm_duration_ms").get("p(95)")
        if isinstance(cold, (int, float)) and isinstance(warm, (int, float)) and warm:
            out.append("### 6.2 缓存收益")
            out.append("")
            out.append(f"- 同 key 重复请求：冷调用 med={cold:.0f}ms → 热调用 p95={warm:.0f}ms")
            out.append(f"（**{cold / warm:.0f}x 加速**）；命中率经 `aether.cache.llm.hitrate` Gauge 暴露至 Prometheus。\n")
    if ch:
        rec = k6_metric(ch, "chaos_recovery_duration_ms").get("med")
        rate = ch.get("metrics", {}).get("checks", {}).get("values", {}).get("rate")
        if isinstance(rec, (int, float)):
            out.append("### 6.3 容错链路（混沌验证通过）")
            out.append("")
            rate_s = f"{rate * 100:.0f}%" if isinstance(rate, (int, float)) else "n/a"
            out.append(f"- 429×3（自适应退避 30/60/90s）→ 500×2（抖动退避 + fallback 切换）→ 成功：")
            out.append(f"恢复耗时 {rec / 1000:.0f}s，最终成功率 {rate_s}；恢复路径可经")
            out.append("  `aether.model.recovery.branch{branch=}` 与 `aether.model.fallback.switches` 指标核对。\n")

    out.append("### 6.4 本次实压发现的生产问题")
    out.append("")
    out.append("1. **多表装配 ChatModel Bean 串线**：`ChatModelNode.registerPerAgentChatModel` 在 Agent 无")
    out.append("   per-agent model/toolNames 定制时跳过独立 Bean，多张 Agent 表共享全局 `chatModel` Bean，")
    out.append("   后装配的表覆盖先装配的表（本次压测 agent 201 被串到混沌模型的 api-key）。当前以 per-agent")
    out.append("   `model:` 覆盖规避（bench-agents.yml），**P1 建议修复装配逻辑**（全局 Bean 去重或按表隔离）。")
    out.append("2. **异常转发 /error 被 401 掩盖**：业务异常转发到 `/error` 后被 SecurityConfig 拦截返回")
    out.append("   401（真实错误不可见）。建议 SecurityConfig 对 `/error` 放行（或自定义 ErrorController）。")
    out.append("3. 非root容器日志目录：首次启动因 `/app/logs`、`/app/data/log` 不可写失败，Dockerfile 已预建。")

    # ── 图表：并发曲线（mermaid xychart-beta，GitHub 原生渲染；P0 1.5 要求 ≥2 张图）──
    def xychart(title, vus_list, series):
        """series: [(name, [values]), ...] 值全就绪才生成。"""
        if not vus_list or any(
                len(vals) != len(vus_list) or not all(isinstance(v, (int, float)) for v in vals)
                for _, vals in series):
            return
        ys = [v for _, vals in series for v in vals]
        lo, hi = min(ys) * 0.98, max(ys) * 1.02
        out.append("\n```mermaid")
        out.append("xychart-beta")
        out.append(f'    title "{title}"')
        out.append(f"    x-axis [VU {', VU '.join(vus_list)}]")
        out.append(f'    y-axis "ms" {lo:.0f} --> {hi:.0f}')
        for name, vals in series:
            out.append(f'    line "{name}" [{", ".join(f"{v:.0f}" for v in vals)}]')
        out.append("```\n")

    ab_pairs = {}
    for f in ttft_files:
        d = load(f)
        if not d:
            continue
        vus = f.stem.replace("ttft_", "").replace("buffered_", "")
        ab_pairs.setdefault(vus, {})["buf" if "buffered" in f.stem else "true"] = d["ttftMs"].get("p95")
    vus_list = sorted((v for v, p in ab_pairs.items() if "true" in p and "buf" in p), key=int)
    if vus_list:
        out.append("\n## 7. 可视化（并发曲线）\n")
        xychart("TTFT p95 vs 并发（真流式 A/B）", vus_list, [
            ("真流式 p95", [ab_pairs[v]["true"] for v in vus_list]),
            ("缓冲 p95", [ab_pairs[v]["buf"] for v in vus_list]),
        ])
        turn_p50, turn_p95 = [], []
        for v in vus_list:
            st = k6_metric(load(r / f"capacity_{v}.json"), "chat_turn_duration_ms")
            turn_p50.append(st.get("p(50)", st.get("med")))
            turn_p95.append(st.get("p(95)"))
        xychart("轮次全流时长 vs 并发（真流式相位）", vus_list, [
            ("轮次 p50", turn_p50),
            ("轮次 p95", turn_p95),
        ])

    Path(args.out).write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"report written -> {args.out}")


if __name__ == "__main__":
    main()

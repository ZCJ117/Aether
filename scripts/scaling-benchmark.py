#!/usr/bin/env python
"""P2(1.4): measure single-instance vs dual-instance HTTP linearity.

Examples:
  python scripts/scaling-benchmark.py --mode all --manage-stack --duration 30
  python scripts/scaling-benchmark.py --mode all \
    --path /api/v1/query_ai_agent_config_list \
    --header "Authorization: Bearer $TOKEN" --duration 60

The benchmark talks to the Nginx entrypoint for both modes. With
--manage-stack it stops aether-2 for the single-instance run, then starts it
again for the dual-instance run. Therefore both runs share the same proxy,
request body, headers and client overhead.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import http.client
import json
import math
import ssl
import statistics
import subprocess
import sys
import time
import urllib.parse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPOSE_FILE = ROOT / "docker" / "docker-compose-scale.yml"
COMPOSE_SINGLE_FILE = ROOT / "docker" / "docker-compose-scale-single.yml"
DIRECT_URLS = {
    "aether-1": "http://127.0.0.1:8091/actuator/health",
    "aether-2": "http://127.0.0.1:8092/actuator/health",
}


@dataclass
class BenchmarkResult:
    mode: str
    concurrency: int
    duration_seconds: int
    requests: int
    errors: int
    throughput_rps: float
    latency_p50_ms: float
    latency_p95_ms: float
    latency_p99_ms: float


class HttpWorker:
    """Keep one connection per worker to avoid measuring TCP setup noise."""

    def __init__(self, base_url: str, path: str, headers: dict[str, str], timeout: float):
        parsed = urllib.parse.urlparse(base_url)
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.https = parsed.scheme == "https"
        self.prefix = parsed.path.rstrip("/")
        self.path = path if path.startswith("/") else "/" + path
        self.headers = headers
        self.timeout = timeout
        self.connection: http.client.HTTPConnection | None = None

    def _connect(self) -> None:
        if self.https:
            self.connection = http.client.HTTPSConnection(
                self.host, self.port, timeout=self.timeout, context=ssl.create_default_context()
            )
        else:
            self.connection = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def request(self) -> None:
        if self.connection is None:
            self._connect()
        try:
            assert self.connection is not None
            self.connection.request("GET", self.prefix + self.path, headers=self.headers)
            response = self.connection.getresponse()
            response.read()
            if response.status >= 400:
                raise RuntimeError(f"HTTP {response.status}")
        except (http.client.HTTPException, OSError, RuntimeError, AssertionError):
            if self.connection is not None:
                self.connection.close()
            self.connection = None
            raise


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = math.ceil(pct / 100 * len(ordered)) - 1
    return ordered[max(0, min(index, len(ordered) - 1))]


def run_load(base_url: str, args: argparse.Namespace, mode: str) -> BenchmarkResult:
    headers = dict(args.header_dict)
    started = time.monotonic()
    deadline = started + args.duration
    requests = 0
    errors = 0
    latencies: list[float] = []

    def worker() -> None:
        nonlocal requests, errors
        http_worker = HttpWorker(base_url, args.path, headers, args.timeout)
        while time.monotonic() < deadline:
            request_started = time.perf_counter_ns()
            try:
                http_worker.request()
                requests += 1
            except Exception:
                errors += 1
            latencies.append((time.perf_counter_ns() - request_started) / 1_000_000)

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(worker) for _ in range(args.concurrency)]
        for future in futures:
            future.result()

    elapsed = max(time.monotonic() - started, 0.001)
    return BenchmarkResult(
        mode=mode,
        concurrency=args.concurrency,
        duration_seconds=args.duration,
        requests=requests,
        errors=errors,
        throughput_rps=round(requests / elapsed, 2),
        latency_p50_ms=round(percentile(latencies, 50), 2),
        latency_p95_ms=round(percentile(latencies, 95), 2),
        latency_p99_ms=round(percentile(latencies, 99), 2),
    )


def compose(command: tuple[str, ...], files: tuple[Path, ...] = (COMPOSE_FILE,)) -> None:
    file_args = [argument for path in files for argument in ("-f", str(path))]
    subprocess.run(["docker", "compose", *file_args, *command], cwd=ROOT, check=True)


def wait_direct(args: argparse.Namespace, services: set[str]) -> None:
    deadline = time.monotonic() + args.stack_timeout
    pending = set(services)
    while pending and time.monotonic() < deadline:
        for service in list(pending):
            try:
                parsed = urllib.parse.urlparse(DIRECT_URLS[service])
                conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=2)
                conn.request("GET", parsed.path)
                response = conn.getresponse()
                response.read()
                conn.close()
                if response.status < 400:
                    pending.remove(service)
            except Exception:
                pass
        if pending:
            time.sleep(2)
    if pending:
        raise RuntimeError("services did not become healthy: " + ", ".join(sorted(pending)))


def manage_single(args: argparse.Namespace) -> None:
    compose(("up", "-d", "postgres", "redis", "aether-1", "aether-2", "nginx"),
            (COMPOSE_FILE, COMPOSE_SINGLE_FILE))
    wait_direct(args, {"aether-1", "aether-2"})
    compose(("pause", "aether-2"), (COMPOSE_FILE,))
    compose(("up", "-d", "--no-deps", "--force-recreate", "nginx"), (COMPOSE_FILE, COMPOSE_SINGLE_FILE))
    wait_direct(args, {"aether-1"})


def manage_dual(args: argparse.Namespace) -> None:
    compose(("unpause", "aether-2"), (COMPOSE_FILE,))
    compose(("up", "-d", "postgres", "redis", "aether-1", "aether-2", "nginx"), (COMPOSE_FILE,))
    compose(("up", "-d", "--no-deps", "--force-recreate", "nginx"), (COMPOSE_FILE,))
    wait_direct(args, {"aether-1", "aether-2"})


def render_report(results: list[BenchmarkResult], args: argparse.Namespace) -> str:
    by_mode = {result.mode: result for result in results}
    single, dual = by_mode.get("single"), by_mode.get("dual")
    ratio = dual.throughput_rps / single.throughput_rps if single and dual and single.throughput_rps else 0.0
    error_rate = {
        result.mode: result.errors / max(result.requests + result.errors, 1) * 100
        for result in results
    }
    passed = bool(single and dual and ratio >= args.threshold and all(v < args.error_threshold for v in error_rate.values()))

    lines = [
        "# Aether 水平扩展压测报告（P2-1.4）",
        "",
        f"> 生成时间：{datetime.now(timezone.utc).isoformat(timespec='seconds')}  ",
        f"> 目标：`{args.base_url}{args.path}`  ",
        f"> 并发：{args.concurrency} / 每模式时长：{args.duration}s / 线性度阈值：{args.threshold:.2f}",
        "",
        "| 模式 | 请求数 | 错误数 | 错误率 | 吞吐 RPS | P50 ms | P95 ms | P99 ms |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for result in results:
        lines.append(
            f"| {result.mode} | {result.requests} | {result.errors} | "
            f"{error_rate[result.mode]:.2f}% | {result.throughput_rps:.2f} | "
            f"{result.latency_p50_ms:.2f} | {result.latency_p95_ms:.2f} | {result.latency_p99_ms:.2f} |"
        )
    lines += [
        "",
        f"- 双实例/单实例吞吐线性度：**{ratio:.2f}x**",
        f"- 结论：**{'PASS' if passed else 'FAIL'}**",
        "",
        "## 复现",
        "",
        "```bash",
        "mvn -B -DskipTests package",
        "docker build -f docker/Dockerfile -t aether:latest .",
        "python scripts/scaling-benchmark.py --mode all --manage-stack --duration 60",
        "```",
        "",
    ]
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["single", "dual", "all"], default="all")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--path", default="/actuator/health")
    parser.add_argument("--header", action="append", default=[], metavar="NAME: VALUE")
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--threshold", type=float, default=1.70)
    parser.add_argument("--error-threshold", type=float, default=1.0)
    parser.add_argument("--manage-stack", action="store_true", help="stop/start aether-2 for modes")
    parser.add_argument("--stack-timeout", type=int, default=180)
    parser.add_argument("--report", type=Path, default=ROOT / "docs" / "scaling-benchmark-report.md")
    parser.add_argument("--json", type=Path, default=ROOT / "target" / "scaling-benchmark.json")
    args = parser.parse_args()
    if args.concurrency < 1 or args.duration < 1:
        parser.error("--concurrency and --duration must be positive")
    if not 0 < args.threshold <= 2:
        parser.error("--threshold must be in (0, 2]")
    args.header_dict = dict(item.split(":", 1) for item in args.header)
    args.header_dict = {key.strip(): value.strip() for key, value in args.header_dict.items()}
    return args


def main() -> int:
    args = parse_args()
    if args.manage_stack:
        if args.mode in ("single", "all"):
            print("[scaling] preparing single instance", flush=True)
            manage_single(args)
        if args.mode == "dual":
            print("[scaling] preparing dual instances", flush=True)
            manage_dual(args)
    elif args.mode == "all":
        print("[scaling] assumes single mode is already active for the first run", flush=True)

    results: list[BenchmarkResult] = []
    modes = ["single", "dual"] if args.mode == "all" else [args.mode]
    for index, mode in enumerate(modes):
        if args.manage_stack and mode == "dual" and len(results) == 1:
            manage_dual(args)
        print(f"[scaling] warmup {mode}", flush=True)
        warmup = HttpWorker(args.base_url, args.path, args.header_dict, args.timeout)
        warmup_deadline = time.monotonic() + args.stack_timeout if args.manage_stack else time.monotonic() + 30
        while time.monotonic() < warmup_deadline:
            try:
                warmup.request()
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError(f"benchmark target did not become ready before {mode} run")

        if args.manage_stack and mode == "single" and args.mode == "all":
            pass
        print(f"[scaling] benchmarking {mode}", flush=True)
        results.append(run_load(args.base_url, args, mode))
        if args.json.exists() is False or index == len(modes) - 1:
            args.json.parent.mkdir(parents=True, exist_ok=True)
            args.json.write_text(json.dumps([asdict(item) for item in results], indent=2), encoding="utf-8")

    if args.report:
        args.report.write_text(render_report(results, args), encoding="utf-8")
        print(f"[scaling] report -> {args.report}", flush=True)

    if args.mode == "all":
        single = next(item for item in results if item.mode == "single")
        dual = next(item for item in results if item.mode == "dual")
        ratio = dual.throughput_rps / single.throughput_rps if single.throughput_rps else 0
        return 0 if ratio >= args.threshold else 2
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"[scaling] failed: {exc}", file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env python
"""P0(1.5) TTFT 探针：并发 POST /api/v1/chat_stream，测量 请求→首个 textDelta 帧 的延迟分位数。

k6 的 HTTP 客户端为整读语义（无法逐帧感知 SSE），TTFT 由本探针承担：
  asyncio + httpx 流式读取，首个含 "textDelta" 的 data: 行到达时刻 - 请求发出时刻。

Usage:
  python probe/ttft_probe.py --url http://localhost:8091 --user bench-user --password bench-pass-123 --agent-id 201 \
      --concurrency 30 --samples 60 --out results/ttft_30.json
"""
import argparse
import asyncio
import json
import time

import httpx


async def one_sample(client: httpx.AsyncClient, url: str, token: str, agent_id: str, seq: int) -> float:
    body = {
        "agentId": agent_id,
        "userId": f"probe-{seq}",
        "message": f"TTFT 探测 #{seq} ts={time.time()}",
    }
    start = time.perf_counter()
    ttft = None
    async with client.stream(
        "POST", url, json=body, headers={"Authorization": f"Bearer {token}"}, timeout=600.0
    ) as resp:
        # 只读取到首个 textDelta 帧即止（TTFT 已到手）；退出 with 块时连接关闭，
        # SSE 流被服务端正常收尾。httpx 不允许对同一响应二次迭代，勿在此后再读。
        async for chunk in resp.aiter_text():
            if '"textDelta"' in chunk:
                ttft = time.perf_counter() - start
                break
    if ttft is None:
        raise RuntimeError("no textDelta frame observed")
    return ttft * 1000.0


async def run(args) -> dict:
    async with httpx.AsyncClient() as client:
        # 登录
        login = await client.post(
            f"{args.url}/api/v1/auth/login",
            json={"username": args.user, "password": args.password},
        )
        token = login.json()["data"]["accessToken"]

        samples: list[float] = []
        errors = 0
        sem = asyncio.Semaphore(args.concurrency)

        async def guarded(i: int):
            nonlocal errors
            async with sem:
                try:
                    samples.append(await one_sample(client, f"{args.url}/api/v1/chat_stream", token, args.agent_id, i))
                except Exception as e:
                    errors += 1
                    print(f"sample {i} failed: {e}")

        await asyncio.gather(*(guarded(i) for i in range(args.samples)))

    samples.sort()

    def pct(p: float) -> float:
        if not samples:
            return -1
        return round(samples[min(len(samples) - 1, int(p * len(samples)))], 1)

    result = {
        "url": args.url,
        "agentId": args.agent_id,
        "concurrency": args.concurrency,
        "samples": len(samples),
        "errors": errors,
        "ttftMs": {"p50": pct(0.50), "p90": pct(0.90), "p95": pct(0.95), "p99": pct(0.99)},
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8091")
    ap.add_argument("--user", default="bench-user")
    ap.add_argument("--password", default="bench-pass-123")
    ap.add_argument("--agent-id", default="201")
    ap.add_argument("--concurrency", type=int, default=10)
    ap.add_argument("--samples", type=int, default=30)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()

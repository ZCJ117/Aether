"""
Aether 压测 mock LLM —— OpenAI 兼容 /v1/chat/completions（流式 + 非流式）+ 按脚本注入故障。

用途（路线图 1.5 性能压测体系）：
  - 固定 chunk 流，可控 TTFT / chunk 间隔 / chunk 数，模拟真实模型节奏（默认 TTFT 500ms、总时长 ~4.5s）；
  - 混沌协议：API key 形如 "bench:429,429,429,500,500,ok" —— 同一 key 的请求按序返回脚本状态码，
    序列走完后恢复正常（用于验证 ResilientChatModelExecutor 的退避/兜底切换/恢复）；
  - 非 "bench:" 前缀的 key 永远正常。

环境变量：TTFT_MS / CHUNK_INTERVAL_MS / CHUNK_COUNT（在 compose 或命令行按场景覆盖）。

Usage: uvicorn main:app --host 0.0.0.0 --port 8011
"""

import asyncio
import json
import os
import time
import uuid

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

TTFT_MS = int(os.environ.get("TTFT_MS", "500"))
CHUNK_INTERVAL_MS = int(os.environ.get("CHUNK_INTERVAL_MS", "450"))
CHUNK_COUNT = int(os.environ.get("CHUNK_COUNT", "10"))

app = FastAPI(title="Aether Mock LLM")

# 每个 chaos key 的请求序号（序列走完即恢复正常；重启服务可重置重新注入）
_chaos_seq: dict[str, int] = {}


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return ""


def _chaos_status(key: str) -> int | None:
    """key 形如 bench:429,429,429,500,500,ok → 按序返回状态码；非该形态返回 None。"""
    if not key.startswith("bench:"):
        return None
    script = key[len("bench:"):].split(",")
    seq = _chaos_seq.get(key, 0)
    _chaos_seq[key] = seq + 1
    if seq >= len(script):
        return None
    step = script[seq].strip()
    return None if step.lower() == "ok" else int(step)


def _chunk_text(i: int, total: int) -> str:
    return f" 压测模拟分片{i}/{total}：Aether benchmark mock chunk."


def _completion_id() -> str:
    return "chatcmpl-mock-" + uuid.uuid4().hex[:12]


def _created() -> int:
    return int(time.time())


def _estimate_prompt_tokens(messages: list) -> int:
    return sum(len(str(m.get("content", ""))) // 4 + 8 for m in messages) or 16


@app.get("/health")
async def health():
    return {"status": "UP", "ttftMs": TTFT_MS, "chunkIntervalMs": CHUNK_INTERVAL_MS, "chunkCount": CHUNK_COUNT}


@app.post("/v1/chat/completions")
async def chat_completions(request: Request, authorization: str | None = Header(default=None)):
    status = _chaos_status(_bearer(authorization))
    if status is not None:
        raise HTTPException(
            status_code=status,
            detail=f"mock chaos injected ({status})",
            headers={"Retry-After": "1"} if status == 429 else None,
        )

    body = await request.json()
    model = body.get("model", "mock-bench-model")
    messages = body.get("messages", [])
    stream = bool(body.get("stream", False))
    completion_id = _completion_id()
    created = _created()
    prompt_tokens = _estimate_prompt_tokens(messages)
    completion_text = "".join(_chunk_text(i, CHUNK_COUNT) for i in range(1, CHUNK_COUNT + 1))
    completion_tokens = max(1, len(completion_text) // 4)

    if not stream:
        # 缓冲路径与流式路径做等量"工作"：TTFT + (N-1)×间隔后一次性返回
        await asyncio.sleep((TTFT_MS + (CHUNK_COUNT - 1) * CHUNK_INTERVAL_MS) / 1000)
        return {
            "id": completion_id,
            "object": "chat.completion",
            "created": created,
            "model": model,
            "choices": [
                {"index": 0, "message": {"role": "assistant", "content": completion_text}, "finish_reason": "stop"}
            ],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        }

    async def sse():
        # 首帧等待 TTFT，其后按固定间隔下发，末帧带 finish_reason=stop
        for i in range(1, CHUNK_COUNT + 1):
            if i == 1:
                await asyncio.sleep(TTFT_MS / 1000)
            else:
                await asyncio.sleep(CHUNK_INTERVAL_MS / 1000)
            chunk = {
                "id": completion_id,
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
                "choices": [
                    {
                        "index": 0,
                        "delta": {"content": _chunk_text(i, CHUNK_COUNT)},
                        "finish_reason": "stop" if i == CHUNK_COUNT else None,
                    }
                ],
            }
            yield f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        sse(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8011, reload=False)

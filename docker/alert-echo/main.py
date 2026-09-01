"""P1(3.3): 告警回显服务 —— Alertmanager webhook 通知通道的可实测落地。

职责：接收 Alertmanager 告警 POST（含 resolved），环形缓冲最近 200 条，
GET /alerts 供人/CI 验证「埋点 → 规则 → 通知」全链路触达。
生产环境请替换为钉钉/企业微信/邮件网关（配置见 alertmanager.yml 注释）。
"""
import json
import os
import time
from collections import deque
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI(title="aether alert-echo")

MAX_ALERTS = int(os.environ.get("ALERT_ECHO_BUFFER", "200"))
alerts = deque(maxlen=MAX_ALERTS)
LOG_FILE = Path(os.environ.get("ALERT_ECHO_LOG", "/tmp/alerts.jsonl"))


@app.post("/alerts")
async def receive_alerts(request: Request):
    """Alertmanager webhook 回调：v4 payload {alerts: [...], status, ...}。"""
    try:
        payload = await request.json()
    except Exception:
        return JSONResponse({"error": "invalid json"}, status_code=400)

    received = []
    entries = []
    for alert in payload.get("alerts", []):
        entry = {
            "receivedAt": time.time(),
            "status": payload.get("status", alert.get("status", "")),
            "alertname": alert.get("labels", {}).get("alertname", "unknown"),
            "severity": alert.get("labels", {}).get("severity", "unknown"),
            "summary": alert.get("annotations", {}).get("summary", ""),
            "labels": alert.get("labels", {}),
        }
        entries.append(entry)
        received.append(entry["alertname"])
    alerts.extend(entries)

    try:
        with LOG_FILE.open("a", encoding="utf-8") as f:
            for entry in entries:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError:
        pass  # 只读环境（tmpfs 外不可写）时静默降级

    return {"ok": True, "received": received, "buffered": len(alerts)}


@app.get("/alerts")
async def list_alerts():
    """回显缓冲区（CI/人工验证通知通道用）。"""
    return {"count": len(alerts), "alerts": list(alerts)}


@app.delete("/alerts")
async def clear_alerts():
    alerts.clear()
    return {"ok": True}


@app.get("/health")
async def health():
    return {"status": "up"}

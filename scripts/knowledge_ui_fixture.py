#!/usr/bin/env python3
"""启动 Android 知识库 UI 测试专用 HTTPS 服务，不读取正式家庭数据。"""

import json
import os
import secrets
from pathlib import Path

os.environ["ROBOT_PREPARE_AUDIO"] = "0"
os.environ["ROBOT_PRELOAD_MODELS"] = "0"
import uvicorn
from fastapi.testclient import TestClient
from robot_service.app import create_app
from robot_service.cli import initialize

root = Path(".artifacts/knowledge/runtime").resolve()
info = initialize(root, "10.0.2.2")
app = create_app(root)
with TestClient(app) as c:
    invite = app.state.store.issue_invite("register")
    robot = c.post(
        "/v1/register", json={"invite": invite, "name": "knowledge-ui-test"}
    ).json()
    rh = {"Authorization": "Bearer " + robot["token"]}
    inv = c.post("/v1/pairing", headers=rh).json()["invite"]
    claim = c.post(
        "/v1/pairing/claim", json={"invite": inv, "name": "knowledge-test-parent"}
    ).json()
    c.post(
        "/v1/pairing/" + claim["pairId"] + "/decision",
        headers=rh,
        json={"approved": True},
    )
    token = secrets.token_urlsafe(32)
    parent = c.post(
        "/v1/pairing/" + claim["pairId"] + "/complete",
        json={"claim": claim["claim"], "token": token},
    ).json()
    value = {
        **info,
        **parent,
        "token": token,
        "address": "https://10.0.2.2:8879",
        "protocolVersion": 1,
    }
    target = Path(".artifacts/knowledge/knowledge-connection.json")
    target.write_text(json.dumps(value))
    target.chmod(0o600)
app = create_app(root)
uvicorn.run(
    app,
    host="127.0.0.1",
    port=8879,
    ssl_keyfile=str(root / "server.key"),
    ssl_certfile=str(root / "server.pem"),
    access_log=False,
)

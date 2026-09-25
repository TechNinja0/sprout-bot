#!/usr/bin/env python3
"""启动 Android 返回导航 UI 测试专用 HTTPS 服务，不读取正式家庭数据。"""

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

root = Path(".artifacts/navigation/runtime").resolve()
info = initialize(root, "10.0.2.2")
app = create_app(root)
with TestClient(app) as c:
    invite = app.state.store.issue_invite("register")
    robot = c.post(
        "/v1/register", json={"invite": invite, "name": "navigation-ui-test"}
    ).json()
    rh = {"Authorization": "Bearer " + robot["token"]}
    inv = c.post("/v1/pairing", headers=rh).json()["invite"]
    claim = c.post(
        "/v1/pairing/claim", json={"invite": inv, "name": "navigation-test-parent"}
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
        "address": "https://10.0.2.2:8881",
        "protocolVersion": 1,
    }
    ph = {"Authorization": "Bearer " + token}
    pages = [
        {"id": f"page-{n}", "label": str(n), "text": f"这是第{n}页。", "reviewed": True}
        for n in range(1, 41)
    ]
    for n in range(25):
        response = c.post(
            "/v1/resources",
            headers=ph,
            json={
                "kind": "book",
                "draft": {
                    "title": f"导航测试书 {n:02}",
                    "complete": True,
                    "pages": pages,
                },
            },
        )
        response.raise_for_status()
    robot_value = {
        **info,
        **robot,
        "address": "https://10.0.2.2:8881",
        "protocolVersion": 1,
    }
    value = {"parent": value, "robot": robot_value}
    target = Path(".artifacts/navigation/navigation-connection.json")
    target.write_text(json.dumps(value))
    target.chmod(0o600)
app = create_app(root)
uvicorn.run(
    app,
    host="127.0.0.1",
    port=8881,
    ssl_keyfile=str(root / "server.key"),
    ssl_certfile=str(root / "server.pem"),
    access_log=False,
)

#!/usr/bin/env python3
"""陪伴记录 UI 回归专用 HTTPS 服务；仅使用隔离的测试身份和数据库。"""

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


def main():
    root = Path(".artifacts/records/runtime").resolve()
    info = initialize(root, "10.0.2.2")
    app = create_app(root)
    with TestClient(app) as client:
        robot = client.post(
            "/v1/register",
            json={
                "invite": app.state.store.issue_invite("register"),
                "name": "records-test",
            },
        ).json()
        rh = {"Authorization": "Bearer " + robot["token"]}
        invite = client.post("/v1/pairing", headers=rh).json()["invite"]
        claim = client.post(
            "/v1/pairing/claim", json={"invite": invite, "name": "records-parent"}
        ).json()
        client.post(
            f"/v1/pairing/{claim['pairId']}/decision",
            headers=rh,
            json={"approved": True},
        ).raise_for_status()
        token = secrets.token_urlsafe(32)
        parent = client.post(
            f"/v1/pairing/{claim['pairId']}/complete",
            json={"claim": claim["claim"], "token": token},
        ).json()
        common = {**info, "address": "https://10.0.2.2:8882", "protocolVersion": 1}
        target = root.parent / "records-connection.json"
        target.touch(mode=0o600)
        target.write_text(
            json.dumps(
                {
                    "parent": {**common, **parent, "token": token},
                    "robot": {**common, **robot},
                }
            )
        )
    uvicorn.run(
        create_app(root),
        host="127.0.0.1",
        port=8882,
        ssl_keyfile=str(root / "server.key"),
        ssl_certfile=str(root / "server.pem"),
        access_log=False,
    )


if __name__ == "__main__":
    main()

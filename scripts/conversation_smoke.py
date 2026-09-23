#!/usr/bin/env python3
"""真实本地模型的10组5轮上下文测试；文本输入，不替代儿童语音验收。"""

import json
import os
import tempfile
import time
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient
from robot_service.app import create_app

ROOT = Path(__file__).resolve().parents[1]
os.environ["ROBOT_MODELS"] = str(ROOT / "runtime/models")
output = ROOT / ".artifacts/development/conversation-smoke.json"
report = {
    "scope": "真实本地LLM，文本输入，原创情境；不是目标儿童声学测试",
    "passed": False,
    "groups": [],
}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
with tempfile.TemporaryDirectory(prefix="robot-context-") as folder:
    app = create_app(folder)
    with TestClient(app) as client:
        invitation = app.state.store.issue_invite("register")
        response = client.post(
            "/v1/register", json={"invite": invitation, "name": "上下文测试"}
        )
        response.raise_for_status()
        headers = {"Authorization": "Bearer " + response.json()["token"]}
        for index, name in enumerate(
            [
                "团团",
                "圆圆",
                "豆豆",
                "花花",
                "点点",
                "乐乐",
                "毛毛",
                "米米",
                "星星",
                "朵朵",
            ]
        ):
            session = uuid4().hex
            color = ["蓝", "红", "黄", "绿"][index % 4]
            turns = [
                (f"有一只玩具小猫，名字叫{name}，最喜欢{color}色球。", None),
                ("它叫什么名字？", name),
                ("它喜欢什么颜色的球？", color),
                ("我把它的球放进绿色盒子里了。", None),
                ("它的球现在在哪里？", "盒"),
            ]
            group = {"index": index + 1, "turns": [], "passed": True}
            for question, expected in turns:
                start = time.monotonic()
                response = client.post(
                    "/v1/turns",
                    headers=headers,
                    json={"sessionId": session, "text": question},
                )
                answer = response.json().get("text", "")
                passed = (
                    response.status_code == 200
                    and bool(answer)
                    and (expected is None or expected in answer)
                )
                group["turns"].append(
                    {
                        "question": question,
                        "answer": answer,
                        "seconds": round(time.monotonic() - start, 3),
                        "passed": passed,
                    }
                )
                group["passed"] = group["passed"] and passed
            report["groups"].append(group)
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print("group", index + 1, group["passed"], flush=True)
report["passed"] = all(group["passed"] for group in report["groups"])
output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
if not report["passed"]:
    raise SystemExit("上下文测试仍有失败，见报告")

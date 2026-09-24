#!/usr/bin/env python3
"""真实本地模型的启蒙问答抽查；临时库，不读写家庭会话或配置。

运行：PYTHONPATH=ai-service ai-service/.venv/bin/python scripts/prompt_smoke.py
结果保留原始回答供人工审阅；长度检查不等于知识正确性验收。
"""

import json
import os
import tempfile
import time
from pathlib import Path

from fastapi.testclient import TestClient
from robot_service.app import create_app
from robot_service.store import dumps


def main():
    os.environ["ROBOT_PRELOAD_MODELS"] = "0"
    os.environ["ROBOT_PREPARE_AUDIO"] = "0"
    output = (
        Path(__file__).resolve().parents[1] / ".artifacts/prompt-quality/smoke.json"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "scope": "本地真实文本模型；长度与链路检查，事实和叙事质量须人工审阅；未验收儿童语音体验",
        "cases": [],
    }
    cases = [
        ("rain", "为什么会下雨？", 100, 450),
        ("rain", "我还是没听懂，能换个容易懂的说法吗？", 80, 450),
        ("moon", "月亮是自己发光的吗？", 100, 450),
        ("sky", "天空为什么是蓝色的？", 100, 450),
        ("night", "为什么白天过去会变成晚上？", 80, 450),
        ("ice", "冰块为什么会变成水？", 80, 450),
        ("shadow", "影子为什么会跟着我走？", 80, 450),
        ("brief", "一句话告诉我，为什么会下雨？", 10, 100),
        ("hello", "你好", 1, 100),
        ("english", "苹果用英语怎么说？", 5, 200),
        ("story", "编一个小兔子给奶奶送礼物的完整故事。", 300, 600),
    ]
    with tempfile.TemporaryDirectory(prefix="robot-prompt-") as folder:
        app = create_app(folder)
        with TestClient(app) as client:
            invitation = app.state.store.issue_invite("register")
            registration = client.post(
                "/v1/register", json={"invite": invitation, "name": "提示词验证"}
            )
            registration.raise_for_status()
            identity = registration.json()
            headers = {"Authorization": "Bearer " + identity["token"]}
            config = json.loads(
                app.state.store.one(
                    "SELECT body FROM configs WHERE robot_id=?", (identity["deviceId"],)
                )["body"]
            )
            config["originalStories"] = True
            with app.state.store.transaction() as db:
                db.execute(
                    "UPDATE configs SET body=? WHERE robot_id=?",
                    (dumps(config), identity["deviceId"]),
                )
            for topic, question, minimum, maximum in cases:
                start = time.monotonic()
                response = client.post(
                    "/v1/turns",
                    headers=headers,
                    json={
                        "sessionId": "prompt-smoke-session-" + topic,
                        "text": question,
                    },
                )
                answer = response.json().get("text", "")
                case = {
                    "question": question,
                    "answer": answer,
                    "chars": len(answer),
                    "seconds": round(time.monotonic() - start, 2),
                    "status": response.status_code,
                    "length_ok": minimum <= len(answer) <= maximum,
                }
                report["cases"].append(case)
                output.write_text(
                    json.dumps(report, ensure_ascii=False, indent=2) + "\n"
                )
                print(json.dumps(case, ensure_ascii=False), flush=True)
    report["checks_passed"] = all(
        case["status"] == 200 and case["length_ok"] for case in report["cases"]
    )
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if not report["checks_passed"]:
        raise SystemExit("存在请求失败或长度异常，请审阅 " + str(output))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""隔离目录压测知识检索；不读取家庭数据，也不加载模型。"""

import json
import statistics
import tempfile
import time
from pathlib import Path

from fastapi.testclient import TestClient
from robot_service.app import create_app
from robot_service.knowledge import Card, make_snapshot


def stats(samples):
    samples = sorted(samples)
    return {
        "n": len(samples),
        "p50Ms": round(statistics.median(samples), 4),
        "p95Ms": round(samples[int(len(samples) * 0.95) - 1], 4),
        "maxMs": round(max(samples), 4),
    }


with tempfile.TemporaryDirectory(prefix="knowledge-perf-") as root:
    app = create_app(root)
    manager = app.state.knowledge
    entries = dict(manager.snapshot.entries)
    for i in range(970):
        ident = f"perf-{i}"
        card = Card(
            question=f"第{i}号收纳盒放在哪里",
            aliases=[f"第{i}号物品的位置是什么"],
            answer=f"第{i}号收纳盒在客厅柜子里。",
            kind="family",
            maxAge=18,
        ).model_dump()
        entries[ident] = {"id": ident, "version": 1, "origin": "parent", "card": card}
    start = time.perf_counter()
    manager.snapshot = make_snapshot(entries)
    report = {
        "cards": len(entries),
        "indexBuildMs": round((time.perf_counter() - start) * 1000, 3),
        "lookup": {},
    }
    for name, text in {
        "exact": "为什么会下雨",
        "alias": "第969号物品的位置是什么",
        "miss": "火星上有多少座城市",
        "similar": "第123号物品的位置是什么呢",
        "chat": "你好小伙伴",
    }.items():
        times = []
        for _ in range(600):
            start = time.perf_counter()
            manager.query(text)
            times.append((time.perf_counter() - start) * 1000)
        report["lookup"][name] = stats(times)
    with TestClient(app) as c:
        inv = app.state.store.issue_invite("register")
        robot = c.post(
            "/v1/register", json={"invite": inv, "name": "performance-only"}
        ).json()
        headers = {"Authorization": "Bearer " + robot["token"]}
        times = []
        for _ in range(100):
            start = time.perf_counter()
            res = c.post(
                "/v1/turns",
                headers=headers,
                json={"sessionId": "performance-knowledge-001", "text": "为什么会下雨"},
            )
            assert res.status_code == 200 and res.json()["knowledgeStatus"] == "matched"
            times.append((time.perf_counter() - start) * 1000)
        report["apiInProcess"] = stats(times)
    report["passed"] = all(v["p95Ms"] < 10 for v in report["lookup"].values())
    output = Path(".artifacts/knowledge/performance.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert report["passed"], "检索 p95 超过 10ms"

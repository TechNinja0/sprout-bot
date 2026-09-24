#!/usr/bin/env python3
"""真实本地 TTS 验证：发布自动生成、缓存读取耗时、进程生命周期重建后复用。"""

import argparse
import io
import json
import os
import secrets
import tempfile
import time
import wave
from pathlib import Path

from fastapi.testclient import TestClient
from robot_service.app import create_app
from robot_service.store import digest
from robot_service.tts import capabilities

ROOT = Path(__file__).resolve().parents[1]


def main(args):
    os.environ["ROBOT_MODELS"] = str(args.models.resolve())
    os.environ["ROBOT_PREPARE_AUDIO"] = "1"
    if not capabilities(args.models.resolve())["ready"]:
        raise RuntimeError(
            "TTS 运行环境或模型未就绪；隔离源码目录请设置 ROBOT_TTS_PYTHON"
        )
    args.output.mkdir(parents=True, exist_ok=True)
    measurements = {
        "transport": "in-process HTTP; excludes LAN and Android output",
        "synthesis": [],
        "cachedReads": [],
    }
    with tempfile.TemporaryDirectory(prefix="book-audio-smoke-") as folder:
        app = create_app(folder)
        token = secrets.token_urlsafe(32)
        headers = {"Authorization": "Bearer " + token}
        with app.state.store.transaction() as db:
            db.execute(
                "INSERT INTO devices(id,role,name,token_hash,robot_id) VALUES(?,?,?,?,?)",
                (
                    "smoke-parent",
                    "parent",
                    "isolated smoke parent",
                    digest(token),
                    "smoke-robot",
                ),
            )
        original = app.state.tts_worker.run

        async def observed(task, timeout):
            began = time.monotonic()
            result = await original(task, timeout)
            measurements["synthesis"].append(
                {"generationSeconds": round(time.monotonic() - began, 3)}
            )
            return result

        app.state.tts_worker.run = observed
        with TestClient(app) as c:
            book = c.post(
                "/v1/resources",
                headers=headers,
                json={
                    "kind": "book",
                    "draft": {
                        "title": "自动音频验证",
                        "complete": True,
                        "auditioned": True,
                        "pages": [
                            {"id": "one", "text": "小兔看见月亮。", "reviewed": True},
                            {"id": "two", "text": "小熊轻轻说晚安。", "reviewed": True},
                        ],
                    },
                },
            )
            book.raise_for_status()
            rid = book.json()["id"]
            began = time.monotonic()
            publication = c.post(
                f"/v1/resources/{rid}/publish",
                headers=headers,
                json={"expectedVersion": 1, "requestId": secrets.token_hex(16)},
            )
            publication.raise_for_status()
            measurements["publishMs"] = round((time.monotonic() - began) * 1000, 2)
            rev = publication.json()["revisionId"]
            deadline = time.monotonic() + 240
            while True:
                status = c.get(
                    f"/v1/resources/{rid}/audio-preparation", headers=headers
                ).json()
                if status["state"] == "ready":
                    break
                assert status["state"] != "failed", status
                assert time.monotonic() < deadline, status
                time.sleep(0.25)
            measurements["readySeconds"] = round(time.monotonic() - began, 3)
            manifest = c.get(f"/v1/resources/{rid}/manifest", headers=headers).json()
            urls = [
                f"/v1/resources/{rid}/audio/{s['id']}?revisionId={rev}"
                for s in manifest["segments"]
            ]
            assert len(measurements["synthesis"]) == 2
            for index, url in enumerate(urls):
                began = time.monotonic()
                response = c.get(url, headers=headers)
                response.raise_for_status()
                elapsed = (time.monotonic() - began) * 1000
                assert response.headers["X-Content-SHA256"] == digest(response.content)
                with wave.open(io.BytesIO(response.content)) as wav:
                    duration = wav.getnframes() / wav.getframerate()
                    assert duration > 0
                (args.output / f"segment-{index}.wav").write_bytes(response.content)
                measurements["cachedReads"].append(
                    {
                        "requestMs": round(elapsed, 2),
                        "durationSeconds": round(duration, 3),
                    }
                )
            assert len(measurements["synthesis"]) == 2
        # 新建服务对象/生命周期，不沿用上一个 worker 或内存任务。
        restored = create_app(folder)
        with TestClient(restored) as c:
            assert (
                c.get(f"/v1/resources/{rid}/audio-preparation", headers=headers).json()[
                    "state"
                ]
                == "ready"
            )
            for index, url in enumerate(urls):
                response = c.get(url, headers=headers)
                response.raise_for_status()
                assert (
                    response.content
                    == (args.output / f"segment-{index}.wav").read_bytes()
                )
            assert restored.state.tts_worker.process is None
        measurements["restartReusedCache"] = True
    report = args.output / "measurements.json"
    report.write_text(json.dumps(measurements, ensure_ascii=False, indent=2) + "\n")
    print(report.read_text(), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ROOT / "runtime/models")
    parser.add_argument(
        "--output", type=Path, default=ROOT / ".artifacts/book-audio-smoke"
    )
    main(parser.parse_args())

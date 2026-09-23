#!/usr/bin/env python3
"""真实本地模型串行性能；合成输入、不包含手机声学或Wi-Fi传输耗时。"""

import argparse
import base64
import io
import json
import os
import re
import ssl
import statistics
import subprocess
import tempfile
import time
import uuid
import wave
from contextlib import contextmanager
from pathlib import Path

import httpx
import numpy as np
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw
from robot_service.app import create_app
from robot_service.store import Store

ROOT = Path(__file__).resolve().parents[1]
os.environ["ROBOT_MODELS"] = str(ROOT / "runtime/models")
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--live", action="store_true", help="使用已运行服务，不另加载一份模型"
)
parser.add_argument("--data", type=Path, default=ROOT / "runtime")
parser.add_argument("--address", default="https://127.0.0.1:8765")
parser.add_argument(
    "--output", type=Path, default=ROOT / ".artifacts/development/model-benchmark.json"
)
args = parser.parse_args()
output = args.output.resolve()
if (
    output.is_relative_to(ROOT)
    and subprocess.run(
        ["git", "-C", str(ROOT), "check-ignore", "-q", str(output)], check=False
    ).returncode
):
    parser.error("仓库内结果必须写入已忽略目录")
output.parent.mkdir(parents=True, exist_ok=True)
report = {
    "scope": "真实ASR→LLM/VLM→首句TTS（与Android分句播放一致），本机API；合成输入，不含手机拾音/VAD/传输/输出缓冲",
    "liveService": args.live,
    "passed": False,
    "rounds": [],
}
output.write_text(json.dumps(report, ensure_ascii=False, indent=2))


def check(r):
    assert r.status_code == 200, (r.status_code, r.text[:200])
    return r


def mono16(data):
    with wave.open(io.BytesIO(data)) as source:
        rate = source.getframerate()
        values = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
    values = np.interp(
        np.arange(int(len(values) * 16000 / rate)) * rate / 16000,
        np.arange(len(values)),
        values,
    ).astype("<i2")
    result = io.BytesIO()
    with wave.open(result, "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(16000)
        target.writeframes(values.tobytes())
    return result.getvalue()


@contextmanager
def benchmark_client():
    if args.live:
        store = Store(args.data)
        with httpx.Client(
            base_url=args.address,
            verify=ssl.create_default_context(cafile=str(args.data / "server.pem")),
            trust_env=False,
            timeout=110,
        ) as client:
            health = check(client.get("/health")).json()
            assert (
                health["serviceId"] == store.meta("service_id")
                and health["modelWarmup"] == "ready"
            )
            robot = check(
                client.post(
                    "/v1/register",
                    json={
                        "invite": store.issue_invite("register"),
                        "name": "合成性能测试",
                    },
                )
            ).json()
            headers = {"Authorization": "Bearer " + robot["token"]}
            try:
                report["capabilities"] = check(
                    client.get("/v1/models", headers=headers)
                ).json()
                yield client, headers
            finally:
                response = client.delete(
                    f"/v1/devices/{robot['deviceId']}", headers=headers
                )
                report["testIdentityRevoked"] = response.status_code == 200
                output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
                response.raise_for_status()
    else:
        with tempfile.TemporaryDirectory(prefix="robot-benchmark-") as folder:
            app = create_app(folder)
            with TestClient(app) as client:
                invite = app.state.store.issue_invite("register")
                robot = check(
                    client.post(
                        "/v1/register", json={"invite": invite, "name": "合成性能测试"}
                    )
                ).json()
                yield client, {"Authorization": "Bearer " + robot["token"]}


with benchmark_client() as (client, headers):
    questions = [
        "苹果用英语怎么说？",
        "猫用英语怎么说？",
        "给我一个简单的英语招呼。",
        "蓝色用英语怎么说？",
        "图片中的圆是什么颜色？",
    ]
    inputs = {
        text: mono16(
            check(
                client.post("/v1/speech/reply", headers=headers, json={"text": text})
            ).content
        )
        for text in questions
    }
    for kind, count in [("voice", 30), ("vision", 20)]:
        for index in range(count):
            question = questions[index % 4] if kind == "voice" else questions[-1]
            start = time.monotonic()
            recognized = check(
                client.post(
                    "/v1/speech/recognize",
                    headers=headers,
                    files={"file": ("speech.wav", inputs[question], "audio/wav")},
                )
            ).json()["text"]
            after_asr = time.monotonic()
            payload = {"sessionId": uuid.uuid4().hex, "text": recognized}
            if kind == "vision":
                picture = Image.new("RGB", (320, 240), "white")
                ImageDraw.Draw(picture).ellipse(
                    (90, 50, 230, 190),
                    fill=["red", "blue", "green", "yellow"][index % 4],
                )
                image = io.BytesIO()
                picture.save(image, "JPEG")
                payload.update(
                    image=base64.b64encode(image.getvalue()).decode(),
                    imageAgeMs=0,
                    visualRequest=True,
                )
            answer = check(
                client.post("/v1/turns", headers=headers, json=payload)
            ).json()
            after_model = time.monotonic()
            assert answer["action"] == "speak", answer
            audio = check(
                client.post(
                    "/v1/speech/reply",
                    headers=headers,
                    json={"text": re.split(r"(?<=[。！？.!?])", answer["text"])[0]},
                )
            ).content
            assert audio.startswith(b"RIFF")
            end = time.monotonic()
            report["rounds"].append(
                {
                    "kind": kind,
                    "round": index + 1,
                    "asr": round(after_asr - start, 3),
                    "model": round(after_model - after_asr, 3),
                    "tts": round(end - after_model, 3),
                    "total": round(end - start, 3),
                    "recognized": recognized,
                    "answer": answer["text"],
                    "contentCorrect": any(
                        word in answer["text"].lower()
                        for word in (
                            [["apple"], ["cat"], ["hello", "hi"], ["blue"]][index % 4]
                            if kind == "voice"
                            else [
                                ["红", "red"],
                                ["蓝", "blue"],
                                ["绿", "green"],
                                ["黄", "yellow"],
                            ][index % 4]
                        )
                    ),
                }
            )
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(kind, index + 1, round(end - start, 2), flush=True)
for kind in ["voice", "vision"]:
    values = sorted(r["total"] for r in report["rounds"] if r["kind"] == kind)
    report[kind] = {
        "count": len(values),
        "median": statistics.median(values),
        "p95": values[int(np.ceil(len(values) * 0.95)) - 1],
    }
report["contentPassRate"] = sum(r["contentCorrect"] for r in report["rounds"]) / len(
    report["rounds"]
)
report["passed"] = (
    report["contentPassRate"] >= 0.9
    and report["voice"]["median"] <= 4
    and report["vision"]["median"] <= 6
    and report["vision"]["p95"] <= 12
)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(
    json.dumps(
        {key: report[key] for key in ["voice", "vision", "passed"]}, ensure_ascii=False
    )
)
if not report["passed"]:
    raise SystemExit("性能目标尚未全部达到，需按实际分段继续优化")

#!/usr/bin/env python3
"""向已启动家庭服务发送原创TTS并发请求；临时测试身份用完即撤销，不操作手机。"""

import argparse
import hashlib
import io
import json
import secrets
import ssl
import subprocess
import time
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
from robot_service.store import Store

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "runtime")
    parser.add_argument("--address", default="https://127.0.0.1:8765")
    parser.add_argument(
        "--output", type=Path, default=ROOT / ".artifacts/development/tts-concurrency"
    )
    args = parser.parse_args()
    output = args.output.resolve()
    if (
        output.is_relative_to(ROOT)
        and subprocess.run(
            [
                "git",
                "-C",
                str(ROOT),
                "check-ignore",
                "-q",
                str(output / "results.json"),
            ],
            check=False,
        ).returncode
    ):
        parser.error("仓库内结果必须写入已忽略目录")
    output.mkdir(parents=True, exist_ok=True)
    store = Store(args.data)
    report = {
        "scope": "单一实际TTS服务的热态API/并发；不含手机拾音、传输及扬声器起音",
        "requests": [],
        "passed": False,
    }
    with httpx.Client(
        base_url=args.address,
        verify=ssl.create_default_context(cafile=str(args.data / "server.pem")),
        trust_env=False,
        timeout=105,
    ) as client:

        def post(path, body, headers=None):
            response = client.post(path, json=body, headers=headers)
            response.raise_for_status()
            return response.json()

        health = client.get("/health").json()
        assert (
            health["serviceId"] == store.meta("service_id")
            and health["modelWarmup"] == "ready"
        )
        registered = post(
            "/v1/register",
            {"invite": store.issue_invite("register"), "name": "原创TTS并发验证"},
        )
        rh = {"Authorization": "Bearer " + registered["token"]}
        try:
            invite = post("/v1/pairing", {}, rh)["invite"]
            claim = post("/v1/pairing/claim", {"invite": invite, "name": "TTS验证家长"})
            post(f"/v1/pairing/{claim['pairId']}/decision", {"approved": True}, rh)
            token = secrets.token_urlsafe(48)
            post(
                f"/v1/pairing/{claim['pairId']}/complete",
                {"claim": claim["claim"], "token": token},
            )
            ph = {"Authorization": "Bearer " + token}

            def speak(name, text, background=False):
                began = time.monotonic()
                response = client.post(
                    "/v1/speech/" + ("preview" if background else "reply"),
                    headers=ph if background else rh,
                    json={"text": text, "voice": {"zh": "Serena", "style": "gentle"}},
                )
                row = {
                    "name": name,
                    "status": response.status_code,
                    "seconds": round(time.monotonic() - began, 3),
                }
                if response.status_code == 200:
                    with wave.open(io.BytesIO(response.content)) as audio:
                        row["durationSeconds"] = round(
                            audio.getnframes() / audio.getframerate(), 3
                        )
                        assert audio.getnchannels() == 1 and audio.getsampwidth() == 2
                    row["sha256"] = hashlib.sha256(response.content).hexdigest()
                    (output / (name + ".wav")).write_bytes(response.content)
                return row

            report["requests"].append(speak("warm-reply", "我听着呢。"))
            with ThreadPoolExecutor(max_workers=3) as pool:
                background = pool.submit(
                    speak,
                    "active-background",
                    "小兔子看见了圆圆的月亮。她坐在窗边，翻开绘本，轻轻地读起故事。",
                    True,
                )
                # 服务外部无法证明入队顺序；确定性优先级由单测验证，本处记录真实重叠负载。
                time.sleep(0.25)
                foreground = pool.submit(speak, "concurrent-reply", "我在。")
                time.sleep(0.1)
                queued = pool.submit(speak, "queued-background", "小猫回家了。", True)
                started = time.monotonic()
                heartbeat = client.post("/v1/heartbeat", headers=rh, json={})
                report["controlDuringTts"] = {
                    "status": heartbeat.status_code,
                    "seconds": round(time.monotonic() - started, 3),
                }
                report["requests"].extend(
                    future.result() for future in (background, foreground, queued)
                )
            rows = {row["name"]: row for row in report["requests"]}
            report["passed"] = (
                all(
                    rows[name]["status"] == 200
                    for name in ("warm-reply", "active-background", "concurrent-reply")
                )
                and rows["queued-background"]["status"] in (200, 429)
                and heartbeat.status_code == 200
            )
        finally:
            response = client.delete(
                f"/v1/devices/{registered['deviceId']}", headers=rh
            )
            report["testIdentityRevoked"] = response.status_code == 200
            report["passed"] = report["passed"] and report["testIdentityRevoked"]
            (output / "results.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n"
            )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report["passed"]:
        raise SystemExit("TTS并发冒烟未通过；结果不代表产品延迟目标已验收")


if __name__ == "__main__":
    main()

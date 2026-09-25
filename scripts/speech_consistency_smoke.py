#!/usr/bin/env python3
"""真实本地声音回归；仅原创样例与临时书库，不修改家庭数据。"""

import argparse
import asyncio
import base64
import io
import json
import os
import secrets
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient
from robot_service.app import create_app
from robot_service.speech_audio import finish_audio
from robot_service.tts import output_quality, render_profile, worker_python
from robot_service.worker_channel import SpeechWorker

ROOT = Path(__file__).resolve().parents[1]


def stats(audio):
    with wave.open(io.BytesIO(audio)) as w:
        samples = (
            np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float)
            / 32768
        )
        return {
            "sampleRate": w.getframerate(),
            "durationSeconds": len(samples) / w.getframerate(),
            "peak": float(np.max(np.abs(samples))),
            "rms": float(np.sqrt(np.mean(samples**2))),
        }


async def voice_samples(args):
    worker = SpeechWorker("robot_service.tts_worker", worker_python())
    asr = SpeechWorker()
    rows = []
    text = "小兔子推开门，外面下雪了。它开心地笑了。"
    try:
        for variant in (0, 1):
            started = time.monotonic()
            result = await worker.run(
                {
                    "root": str(args.models),
                    "kind": "tts",
                    "text": text,
                    "language": "zh",
                    "voice": {"zh": "Serena"},
                    "variant": variant,
                },
                timeout=90,
            )
            generation_seconds = time.monotonic() - started
            raw = base64.b64decode(result["audio"])
            repeated = await worker.run(
                {
                    "root": str(args.models),
                    "kind": "tts",
                    "text": text,
                    "language": "zh",
                    "voice": {"zh": "Serena", "speed": 1.15},
                    "variant": variant,
                },
                timeout=90,
            )
            assert base64.b64decode(repeated["audio"]) == raw, (
                "同机同seed原音频应复现，speed不参与生成"
            )
            for speed in (0.85, 1.0, 1.15):
                audio = finish_audio(raw, speed)
                name = f"voice-{variant}-{speed}.wav"
                (args.output / name).write_bytes(audio)
                row = dict(
                    variant=variant,
                    speed=speed,
                    file=name,
                    generationSeconds=generation_seconds,
                    repeatMatches=True,
                    **stats(audio),
                )
                assert row["durationSeconds"] > 1 and row["peak"] > 0.01
                assert row["peak"] <= 0.951
                row["transcript"] = (
                    await asr.run(
                        {
                            "kind": "asr",
                            "root": str(args.models),
                            "audio": base64.b64encode(
                                output_quality(audio, "standard")
                            ).decode(),
                        },
                        timeout=90,
                    )
                )["text"]
                # ASR is a secondary check, not a substitute for listening.
                assert all(
                    word in row["transcript"] for word in ("小兔", "下雪", "笑")
                ), row
                rows.append(row)
                print(json.dumps(row, ensure_ascii=False), flush=True)
            normal = rows[-2]["durationSeconds"]
            for row in rows[-3:]:
                assert abs(row["durationSeconds"] * row["speed"] / normal - 1) < 0.08, (
                    row
                )
            print(f"variant {variant}: {time.monotonic() - started:.2f}s", flush=True)
    finally:
        await worker.close()
        await asr.close()
    return rows


def book_samples(args, language="zh"):
    with tempfile.TemporaryDirectory(prefix="robot-speech-check-") as directory:
        with TestClient(create_app(Path(directory))) as c:
            store = c.app.state.store
            invite = store.issue_invite("register")
            robot = c.post(
                "/v1/register", json={"invite": invite, "name": "声音回归"}
            ).json()
            rh = {"Authorization": "Bearer " + robot["token"]}
            invite = c.post("/v1/pairing", headers=rh).json()["invite"]
            claim = c.post(
                "/v1/pairing/claim", json={"invite": invite, "name": "声音自测"}
            ).json()
            c.post(
                "/v1/pairing/" + claim["pairId"] + "/decision",
                headers=rh,
                json={"approved": True},
            )
            token = secrets.token_urlsafe(32)
            c.post(
                "/v1/pairing/" + claim["pairId"] + "/complete",
                json={"claim": claim["claim"], "token": token},
            )
            ph = {"Authorization": "Bearer " + token}
            calls = []
            original_run = c.app.state.tts_worker.run

            async def observe(task, timeout):
                calls.append(task["text"])
                return await original_run(task, timeout)

            c.app.state.tts_worker.run = observe
            draft = {
                "title": "原创短页回归",
                "complete": True,
                "readingMode": "continuous",
                "language": language,
                "voiceSource": "custom",
                "voice": {"en": "Aiden", "zh": "Serena"},
                "pages": [
                    {"id": f"p{i}", "text": text, "reviewed": True}
                    for i, text in enumerate(
                        ["小兔子推开门。", "外面下雪了。", "它开心地笑了。"]
                        if language == "zh"
                        else [
                            "The little rabbit opened the door.",
                            "Snow was falling outside.",
                            "What a beautiful morning!",
                        ]
                    )
                ],
            }
            book = c.post(
                "/v1/resources", headers=ph, json={"kind": "book", "draft": draft}
            ).json()
            rid, version = book["id"], book["draft_version"]
            plan = c.get(
                f"/v1/resources/{rid}/speech-plan?expectedVersion={version}", headers=ph
            )
            assert plan.status_code == 200, plan.text
            segs = plan.json()["segments"]
            assert len(segs) == 3 and len({s["groupId"] for s in segs}) == 1
            audio = []
            mode = None
            for i, seg in enumerate(segs):
                result = c.get(
                    f"/v1/resources/{rid}/speech-preview/{seg['id']}?expectedVersion={version}",
                    headers=ph,
                )
                assert result.status_code == 200, result.text
                mode = result.headers["X-Speech-Grouping"]
                audio.append(result.content)
                (args.output / f"book-{language}-page-{i + 1}.wav").write_bytes(
                    result.content
                )
            if language == "en":
                assert mode == "aligned" and len(calls) == 1, (mode, calls)
            calls_before_publish = len(calls)
            published = c.post(
                f"/v1/resources/{rid}/publish",
                headers=ph,
                json={"expectedVersion": version, "requestId": secrets.token_hex(16)},
            )
            assert published.status_code == 200, published.text
            rev = published.json()["revisionId"]
            started = time.monotonic()
            for seg, expected in zip(segs, audio, strict=True):
                result = c.get(
                    f"/v1/resources/{rid}/audio/{seg['id']}?revisionId={rev}",
                    headers=rh,
                )
                assert result.status_code == 200 and result.content == expected
            assert len(calls) == calls_before_publish
            marker = json.loads(
                (
                    store.root / "audio" / rev / (segs[0]["id"] + ".alignment.json")
                ).read_text()
            )
            (args.output / f"book-{language}-alignment.json").write_text(
                json.dumps(marker, ensure_ascii=False, indent=2)
            )
            return {
                "mode": mode,
                "language": language,
                "synthesisCalls": len(calls),
                "pages": len(segs),
                "publishAudioMatchesPreview": True,
                "cacheReadSeconds": time.monotonic() - started,
                "segments": [stats(a) for a in audio],
            }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ROOT / "runtime/models")
    parser.add_argument(
        "--output", type=Path, default=ROOT / ".artifacts/speech-consistency"
    )
    args = parser.parse_args()
    args.models = args.models.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    os.environ["ROBOT_MODELS"] = str(args.models)
    os.environ["ROBOT_PREPARE_AUDIO"] = "0"
    os.environ["ROBOT_TTS_BACKEND"] = "qwen3-mlx"
    report = {
        "profile": render_profile(),
        "samples": asyncio.run(voice_samples(args)),
        "books": [book_samples(args, language) for language in ("zh", "en")],
        "scope": "真实Qwen/变速/ASR/本地HTTP/缓存；不代替人工听感和真机扬声器验收",
    }
    (args.output / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(report["books"], ensure_ascii=False), flush=True)

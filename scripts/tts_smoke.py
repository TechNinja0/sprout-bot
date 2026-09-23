#!/usr/bin/env python3
"""真实本地 TTS 冒烟：同一原文比较语气，保存 WAV 和生成耗时。"""

import argparse
import asyncio
import base64
import io
import json
import platform
import time
import wave
from pathlib import Path

from robot_service.tts import STYLES, backend_name, worker_python
from robot_service.worker_channel import SpeechWorker

ROOT = Path(__file__).resolve().parents[1]


async def main(args):
    channel = SpeechWorker("robot_service.tts_worker", worker_python())
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    try:
        for style in args.styles:
            started = time.monotonic()
            result = await channel.run(
                {
                    "root": str(args.models.resolve()),
                    "kind": "tts",
                    "text": args.text,
                    "voice": {"zh": args.voice, "style": style},
                },
                timeout=90,
            )
            elapsed = time.monotonic() - started
            audio = base64.b64decode(result["audio"])
            with wave.open(io.BytesIO(audio)) as wav:
                assert wav.getsampwidth() == 2 and wav.getnchannels() == 1
                duration = wav.getnframes() / wav.getframerate()
                assert duration > 0
                rate = wav.getframerate()
            path = args.output / f"{args.voice}-{style}.wav"
            path.write_bytes(audio)
            row = {
                "style": style,
                "voice": args.voice,
                "text": args.text,
                "file": path.name,
                "sampleRate": rate,
                "generationSeconds": round(elapsed, 3),
                "durationSeconds": round(duration, 3),
                "realTimeFactor": round(elapsed / duration, 3),
                "includesColdLoad": not results,
            }
            results.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)
    finally:
        await channel.close()
        (args.output / "measurements.json").write_text(
            json.dumps(
                {
                    "platform": platform.platform(),
                    "backend": backend_name(),
                    "results": results,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n"
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ROOT / "runtime/models")
    parser.add_argument(
        "--output", type=Path, default=ROOT / ".artifacts/development/tts-samples"
    )
    parser.add_argument("--voice", default="Serena")
    parser.add_argument(
        "--styles",
        nargs="+",
        choices=list(STYLES),
        default=["gentle", "cheerful", "soothing"],
    )
    parser.add_argument(
        "--text",
        default="小兔子抬起头，看见了圆圆的月亮。我们一起数一数，天上有多少颗星星？",
    )
    asyncio.run(main(parser.parse_args()))

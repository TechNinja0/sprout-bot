#!/usr/bin/env python3
"""通过项目 TTS 接口生成原创本地短提示音；默认 Qwen3-TTS 1.7B。"""

import argparse
import array
import asyncio
import base64
import io
import json
import math
import wave
from pathlib import Path

from robot_service.tts import backend_name, make_request, render_profile, worker_python
from robot_service.worker_channel import SpeechWorker

ROOT = Path(__file__).resolve().parents[1]
PROMPTS = {
    "wake": "我在。",
    "wake_listen": "我听着呢。",
    "wake_here": "在呢，你说。",
    "wake_touch": "嗯，我在呢。",
    "pet": "嘿嘿。",
    "tickle": "哎呀，好痒！",
    "thinking": "我想想。",
    "rest": "我们休息一下吧。",
    "offline": "我现在还不能回答，可以先听已经下载的故事。",
    "unclear": "我没听清，可以再说一遍。",
    "camera_unavailable": "相机暂时用不了，我们可以说话。",
}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("names", nargs="*", help="只重建指定提示语；默认全部")
args = parser.parse_args()
unknown = set(args.names) - set(PROMPTS) - {"chime"}
if unknown:
    parser.error(f"未知提示语：{sorted(unknown)}")
runner = asyncio.Runner()
worker = SpeechWorker("robot_service.tts_worker", worker_python())
metadata = {}
for name, text in PROMPTS.items():
    if args.names and name not in args.names:
        continue
    result = runner.run(
        worker.run(
            {
                "root": str(ROOT / "runtime/models"),
                "kind": "tts",
                "text": text,
                "voice": {"zh": "Serena", "style": "gentle", "speed": 1.0},
            },
            timeout=90,
        )
    )
    path = ROOT / "android-app/app/src/main/assets/prompts" / (name + ".wav")
    path.parent.mkdir(parents=True, exist_ok=True)
    # 去除模型前后长静音，保留40ms边缘；所有短回应统一峰值，仍遵守App音量。
    with wave.open(io.BytesIO(base64.b64decode(result["audio"])), "rb") as wav:
        rate = wav.getframerate()
        samples = array.array("h", wav.readframes(wav.getnframes()))
    active = [i for i, value in enumerate(samples) if abs(value) > 160]
    if not active:
        raise ValueError(f"提示语为空：{name}")
    pad = int(rate * 0.04)
    samples = samples[max(0, active[0] - pad) : min(len(samples), active[-1] + pad + 1)]
    gain = min(2.0, 15000 / max(abs(v) for v in samples))
    samples = array.array("h", (int(v * gain) for v in samples))
    with wave.open(str(path), "wb") as wav:
        wav.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        wav.writeframes(samples.tobytes())
    print(name, round(len(samples) * 1000 / rate), "ms", flush=True)
    metadata[name] = {
        "text": text,
        "backend": backend_name(),
        "voice": make_request(text, {"zh": "Serena"}).voice,
        "style": "gentle",
        "renderProfile": render_profile(),
        "sampleRate": rate,
    }

runner.run(worker.close())
runner.close()
manifest = ROOT / "android-app/app/src/main/assets/prompts/provenance.json"
previous = json.loads(manifest.read_text()) if manifest.is_file() else {}
previous.update(metadata)
manifest.write_text(json.dumps(previous, ensure_ascii=False, indent=2) + "\n")

if not args.names or "chime" in args.names:
    # 原创160ms柔和双音；不依赖模型，不复用其他品牌提示音。
    rate = 24000
    count = int(rate * 0.16)
    samples = array.array(
        "h",
        (
            int(
                4800
                * math.sin(math.pi * i / count) ** 2
                * (
                    math.sin(2 * math.pi * 660 * i / rate)
                    + 0.3 * math.sin(2 * math.pi * 990 * i / rate)
                )
            )
            for i in range(count)
        ),
    )
    path = ROOT / "android-app/app/src/main/assets/prompts/chime.wav"
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        wav.writeframes(samples.tobytes())
    print("chime 160 ms", flush=True)

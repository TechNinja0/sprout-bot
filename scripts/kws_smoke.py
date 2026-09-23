#!/usr/bin/env python3
"""固定模型的合成口令回归；不是手机声学/AEC/目标儿童验收。"""

import base64
import io
import json
import tempfile
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx as so
from robot_service.keywords import book_keywords, generate
from robot_service.model_worker import execute

ROOT = Path(__file__).resolve().parents[1]
models = ROOT / "runtime/models"
kws_dir = models / "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
report = {"evidence": "合成TTS音频直接输入KWS；不是空气传播或儿童发音", "checks": []}
with tempfile.TemporaryDirectory() as folder:
    words = Path(folder) / "keywords.txt"
    words.write_text(generate("小伙伴") + book_keywords("abc", {"title": "红色小猫"}))
    kws = so.KeywordSpotter(
        tokens=str(kws_dir / "tokens.txt"),
        **{
            part: str(kws_dir / (part + "-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
            for part in ("encoder", "decoder", "joiner")
        },
        keywords_file=str(words),
    )
    for text, expected in [
        ("小伙伴", "wake"),
        ("停止", "stop"),
        ("继续", "resume"),
        ("下一页", "next"),
        ("下一章", "next_chapter"),
        ("休息吧", "rest"),
        ("读红色小猫", "book_abc"),
    ]:
        data = execute(
            {"root": str(models), "kind": "tts", "text": text, "sid": 45, "speed": 1.0}
        )
        with wave.open(io.BytesIO(base64.b64decode(data["audio"]))) as source:
            samples = (
                np.frombuffer(
                    source.readframes(source.getnframes()), dtype="<i2"
                ).astype(np.float32)
                / 32768
            )
            rate = source.getframerate()
        stream = kws.create_stream()
        samples = np.concatenate([samples, np.zeros(rate // 2, dtype=np.float32)])
        found = []
        for start in range(0, len(samples), rate // 10):
            stream.accept_waveform(rate, samples[start : start + rate // 10])
            while kws.is_ready(stream):
                kws.decode_stream(stream)
            result = kws.get_result(stream)
            if result:
                found.append(result)
                kws.reset_stream(stream)
        report["checks"].append(
            {
                "text": text,
                "expected": expected,
                "found": found,
                "passed": expected in found,
            }
        )
        print(text, found, flush=True)
report["passed"] = all(c["passed"] for c in report["checks"])
path = ROOT / ".artifacts/development/kws-synthetic.json"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
if not report["passed"]:
    raise SystemExit("有合成口令未检出，详见报告；不能据此推算儿童准确率")

"""语音模型驻留在可销毁的独立进程；父进程有界等待，原始输入不落日志。"""

import base64
import binascii
import io
import json
import os
import sys
import wave
from pathlib import Path

import numpy as np

MODELS = {}


def execute(task):
    root = Path(task["root"])
    if task["kind"] in ("warm", "asr", "align"):
        import onnxruntime

        # VAD依赖的ONNX Runtime无需遥测；同时避免macOS退出时遥测线程销毁竞态。
        onnxruntime.disable_telemetry_events()
    if task["kind"] == "warm":
        # 项目自有短句仅在内存中预热，避免用家庭录音作初始化材料。
        audio = {"audio": task["audio"]}
        with wave.open(io.BytesIO(base64.b64decode(audio["audio"]))) as wav:
            rate = wav.getframerate()
            pcm = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
        samples = np.interp(
            np.arange(int(len(pcm) * 16000 / rate)) * rate / 16000,
            np.arange(len(pcm)),
            pcm,
        ).astype("<i2")
        out = io.BytesIO()
        with wave.open(out, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(samples.tobytes())
        execute(
            {
                "kind": "asr",
                "root": str(root),
                "audio": base64.b64encode(out.getvalue()).decode(),
            }
        )
        return {"ready": True}
    if task["kind"] == "tts":
        # 旧测试素材生成入口继续使用 Kokoro，生产 TTS 统一走独立 tts_worker。
        from .tts import SynthesisRequest
        from .tts_kokoro import KokoroProvider

        key = ("legacy-kokoro", str(root))
        if key not in MODELS:
            MODELS[key] = KokoroProvider(root / "kokoro-multi-lang-v1_0")
        voice = {45: "zh-girl", 50: "zh-boy", 3: "en-us", 20: "en-gb"}[task["sid"]]
        result = MODELS[key].synthesize(
            SynthesisRequest(
                task["text"],
                voice,
                "zh" if task["sid"] in (45, 50) else "en",
                task["speed"],
                "neutral",
                "",
            )
        )
        return {
            "audio": base64.b64encode(result.wav).decode(),
            "durationMs": result.duration_ms,
        }
    if task["kind"] == "align":
        # 独立于 ASR 后端选择：对齐始终使用本地 small，不删静音或改变时间轴。
        from .speech_alignment import _quiet, _read_pcm16

        if task.get("language") not in ("zh", "en"):
            return {"words": []}
        try:
            pcm = _read_pcm16(base64.b64decode(task["audio"], validate=True))
        except (binascii.Error, ValueError, TypeError, KeyError):
            return {"words": []}
        if pcm is None or _quiet(pcm.samples):
            return {"words": []}
        # Whisper 固定接收 16kHz；只重采样识别副本，返回时间仍对应原 WAV。
        samples = pcm.samples.astype(np.float32).mean(axis=1) / 32768
        if pcm.rate != 16000:
            samples = np.interp(
                np.arange(round(pcm.frames * 16000 / pcm.rate)) * pcm.rate / 16000,
                np.arange(pcm.frames),
                samples,
            ).astype(np.float32)
        from faster_whisper import WhisperModel

        key = ("asr", str(root))
        model = MODELS.get(key)
        if model is None:
            model = WhisperModel(
                str(root / "faster-whisper-small"),
                device="cpu",
                compute_type="int8",
                cpu_threads=4,
                local_files_only=True,
            )
            MODELS[key] = model
        segments, _ = model.transcribe(
            samples,
            language=task["language"],
            beam_size=5,
            word_timestamps=True,
            temperature=0,
            condition_on_previous_text=False,
            vad_filter=False,
        )
        return {
            "words": [
                {
                    "word": word.word,
                    "start": float(word.start),
                    "end": float(word.end),
                    "probability": float(word.probability),
                }
                for segment in segments
                for word in (segment.words or [])
            ]
        }
    if task["kind"] == "asr":
        with wave.open(io.BytesIO(base64.b64decode(task["audio"])), "rb") as wav:
            if (
                wav.getnchannels() != 1
                or wav.getsampwidth() != 2
                or wav.getframerate() != 16000
                or wav.getnframes() > 16000 * 30
            ):
                raise ValueError("wav format")
            samples = (
                np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(
                    np.float32
                )
                / 32768
            )
        if os.environ.get("ROBOT_ASR_BACKEND", "cpu") == "mlx":
            from faster_whisper.vad import get_speech_timestamps

            spans = get_speech_timestamps(samples, min_silence_duration_ms=400)
            if not spans:
                return {"text": ""}
            samples = np.concatenate([samples[s["start"] : s["end"]] for s in spans])
            path = root / "mlx-whisper-turbo-8bit"
            if not (path / "weights.safetensors").is_file():
                raise ValueError("local MLX weights missing")
            from .mlx_asr import recognize

            return {"text": recognize(samples, path)}
        if os.environ.get("ROBOT_ASR_BACKEND", "cpu") != "cpu":
            raise ValueError("unknown ASR backend")

        from faster_whisper import WhisperModel

        key = ("asr", str(root))
        model = MODELS.get(key)
        if model is None:
            model = WhisperModel(
                str(root / "faster-whisper-small"),
                device="cpu",
                compute_type="int8",
                cpu_threads=4,
                local_files_only=True,
            )
            MODELS[key] = model
        segments, _ = model.transcribe(
            samples,
            beam_size=5,
            initial_prompt="以下是简体中文和英语对话。英语，英文，儿歌，故事，小伙伴。",
            language=None,
            condition_on_previous_text=False,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 400},
        )
        return {"text": "".join(segment.text for segment in segments).strip()}
    raise ValueError("unsupported task")


if __name__ == "__main__":
    if "--persistent" in sys.argv:
        for line in sys.stdin:
            try:
                result = execute(json.loads(line))
            except Exception as exc:
                result = {"error": type(exc).__name__}
            sys.stdout.write(json.dumps(result, ensure_ascii=False) + "\n")
            sys.stdout.flush()
    else:
        try:
            sys.stdout.write(
                json.dumps(execute(json.load(sys.stdin)), ensure_ascii=False)
            )
        except Exception as exc:
            sys.stderr.write(type(exc).__name__)
            sys.exit(2)

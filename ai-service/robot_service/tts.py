"""本地 TTS 契约与能力目录。业务层不依赖模型的 speaker ID 或推理 API。"""

import io
import os
import re
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

STYLES = {
    "neutral": ("自然", "自然平和地说话，吐字清楚。"),
    "gentle": ("温柔陪伴", "用温暖柔和、耐心亲切的语气和孩子说话，表达自然。"),
    "cheerful": ("轻快开心", "用轻快开心、富有好奇心的语气说话，保持清晰和适度音量。"),
    "storytelling": (
        "讲故事",
        "用温暖生动的讲故事语气，随语义自然起伏，在句子间适当停顿。",
    ),
    "soothing": (
        "睡前舒缓",
        "用平静柔和、舒缓的语气说话，吐字清楚，避免夸张和突然提高音量。",
    ),
}
QWEN_DIR = "qwen3-tts-1.7b-customvoice-8bit"
QWEN_REVISION = "41d3337e8b7f2843a75841595fc14e4b9a7a4b96"
LEGACY_PROFILE = "kokoro-v1.0:sherpa-onnx-1.13.8:render-1"
QWEN_VOICES = [
    {"id": "Serena", "name": "苏瑶 · 温暖女声", "language": "zh"},
    {"id": "Vivian", "name": "十三 · 明亮女声", "language": "zh"},
    {"id": "Uncle_Fu", "name": "福伯 · 温和男声", "language": "zh"},
    {"id": "Dylan", "name": "晓东 · 北京口音", "language": "zh"},
    {"id": "Eric", "name": "程川 · 四川口音", "language": "zh"},
    {"id": "Ryan", "name": "Ryan · 英语男声", "language": "en"},
    {"id": "Aiden", "name": "Aiden · 英语男声", "language": "en"},
    {"id": "Ono_Anna", "name": "杏 · 日语女声", "language": "ja"},
    {"id": "Sohee", "name": "素熙 · 韩语女声", "language": "ko"},
]
KOKORO_VOICES = [
    {"id": "zh-girl", "name": "中文女声", "language": "zh"},
    {"id": "zh-boy", "name": "中文男声", "language": "zh"},
    {"id": "en-us", "name": "美式英语", "language": "en"},
    {"id": "en-gb", "name": "英式英语", "language": "en"},
]


@dataclass(frozen=True)
class SynthesisRequest:
    text: str
    voice: str
    language: str
    speed: float
    style: str
    instruction: str


@dataclass(frozen=True)
class SynthesisResult:
    wav: bytes
    duration_ms: int


class TTSProvider(Protocol):
    def synthesize(self, request: SynthesisRequest) -> SynthesisResult: ...


def backend_name():
    name = os.environ.get("ROBOT_TTS_BACKEND", "qwen3-mlx")
    if name not in ("qwen3-mlx", "kokoro"):
        raise ValueError("未知本地 TTS 引擎")
    return name


def render_profile():
    """发布版本固定声音实现；更换权重、推理库或语气模板时升级此指纹。"""
    if backend_name() == "kokoro":
        return LEGACY_PROFILE
    return f"qwen3-1.7b-customvoice-8bit:{QWEN_REVISION}:mlx-audio-0.5.5:render-1"


def worker_python():
    import sys

    if backend_name() == "kokoro":
        return sys.executable
    return os.environ.get(
        "ROBOT_TTS_PYTHON",
        str(Path(__file__).resolve().parents[1] / ".venv-tts/bin/python"),
    )


def model_path(root: Path):
    return root / (
        QWEN_DIR if backend_name() == "qwen3-mlx" else "kokoro-multi-lang-v1_0"
    )


def capabilities(root: Path):
    name = backend_name()
    qwen = name == "qwen3-mlx"
    required = (
        (
            "installed.json",
            "config.json",
            "model.safetensors",
            "speech_tokenizer/config.json",
            "speech_tokenizer/model.safetensors",
        )
        if qwen
        else ("model.onnx", "voices.bin", "tokens.txt", "lexicon-zh.txt")
    )
    return {
        "backend": name,
        "model": "Qwen3-TTS 1.7B CustomVoice · 8-bit" if qwen else "Kokoro v1.0",
        "ready": Path(worker_python()).is_file()
        and all((model_path(root) / f).is_file() for f in required),
        "localOnly": True,
        "supportsInstruction": qwen,
        "supportsStyle": qwen,
        "speedMode": "instruction" if qwen else "factor",
        "voices": QWEN_VOICES if qwen else KOKORO_VOICES,
        "styles": [{"id": k, "name": v[0]} for k, v in STYLES.items()] if qwen else [],
    }


def make_request(text, voice, story=False):
    """兼容已保存的 default/zh-girl 等旧配置；非法新音色明确报错。"""
    language = "zh" if re.search(r"[\u3400-\u9fff]", text) else "en"
    selected = voice.get("story", "default") if story else "default"
    if selected == "default":
        selected = voice.get(language, "default")
    if backend_name() == "qwen3-mlx":
        selected = {
            "default": "Serena" if language == "zh" else "Ryan",
            "zh-girl": "Serena",
            "zh-boy": "Uncle_Fu",
            "en-us": "Ryan",
            "en-gb": "Aiden",
        }.get(selected, selected)
        voices = QWEN_VOICES
    else:
        selected = {
            "default": "zh-girl" if language == "zh" else "en-us",
            "Serena": "zh-girl",
            "Vivian": "zh-girl",
            "Uncle_Fu": "zh-boy",
            "Dylan": "zh-boy",
            "Eric": "zh-boy",
            "Ryan": "en-us",
            "Aiden": "en-us",
            "Ono_Anna": "zh-girl" if language == "zh" else "en-us",
            "Sohee": "zh-girl" if language == "zh" else "en-us",
        }.get(selected, selected)
        voices = KOKORO_VOICES
    if selected not in {v["id"] for v in voices}:
        raise ValueError("当前引擎未安装所选音色")
    style = (
        voice.get("storyStyle", "storytelling")
        if story
        else voice.get("style", "gentle")
    )
    if style not in STYLES:
        raise ValueError("未知语气")
    speed = float(voice.get("speed", 1.0))
    instruction = voice.get("instruction", "").strip()
    if (
        not 0.7 <= speed <= 1.3
        or len(instruction) > 200
        or not 0 < len(text.strip()) <= 600
    ):
        raise ValueError("语音参数超出范围")
    return SynthesisRequest(text.strip(), selected, language, speed, style, instruction)


def wav_result(samples, sample_rate):
    import numpy as np

    samples = np.asarray(samples, dtype=np.float32).reshape(-1)
    if (
        not len(samples)
        or not np.isfinite(samples).all()
        or not 8000 <= sample_rate <= 192000
    ):
        raise ValueError("语音模型返回无效音频")
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    return SynthesisResult(output.getvalue(), int(len(samples) * 1000 / sample_rate))


def create_provider(root: Path) -> TTSProvider:
    if backend_name() == "qwen3-mlx":
        from .tts_qwen import QwenMLXProvider

        return QwenMLXProvider(model_path(root))
    from .tts_kokoro import KokoroProvider

    return KokoroProvider(model_path(root))

"""Qwen3-TTS 1.7B 的 MLX 适配器，仅从本地路径加载。"""

import hashlib
import json
import os
import re
import threading

from .speech_text import TEXT_PROFILE, normalize_text
from .tts import (
    QWEN_GENERATION,
    QWEN_LANGUAGE_INSTRUCTIONS,
    QWEN_PACE_GUARD,
    QWEN_REVISION,
    QWEN_SEED_VERSION,
    QWEN_VOICE_CONSTRAINT,
    STYLES,
    SynthesisRetryableError,
    wav_result,
)

# MLX 使用进程级 RNG；seed 和完整生成过程必须串行，避免并发请求互相扰动。
_GENERATION_LOCK = threading.Lock()


def voice_instruction(request):
    return " ".join(
        filter(
            None,
            (
                QWEN_LANGUAGE_INSTRUCTIONS[request.language],
                STYLES[request.style][1],
                request.instruction,
                QWEN_VOICE_CONSTRAINT,
            ),
        )
    )


def generation_seed(request):
    """相同标准化输入在同一实现中复现；不保证跨设备/库版本 bit exact。"""
    payload = {
        "version": QWEN_SEED_VERSION,
        "model": QWEN_REVISION,
        "textProfile": TEXT_PROFILE,
        "text": normalize_text(request.text, request.language),
        "speaker": request.voice,
        "language": request.language,
        "style": request.style,
        "instruct": voice_instruction(request),
        "generation": QWEN_GENERATION,
        "variant": request.variant,
    }
    digest = hashlib.sha256(
        json.dumps(
            payload, sort_keys=True, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
    ).digest()
    return int.from_bytes(digest[:4], "big")


def _attempt_seed(base_seed, attempt):
    if attempt == 0:
        return base_seed
    payload = f"{QWEN_PACE_GUARD['retry_seed']}:{base_seed}:{attempt}"
    return int.from_bytes(hashlib.sha256(payload.encode("ascii")).digest()[:4], "big")


def _abnormal_pace(samples, rate, request):
    """按语气筛查较长中文的异常拖长；豁免短句/英文/soothing/自定义指令。"""
    import numpy as np

    text = normalize_text(request.text, request.language)
    seconds_per_character = QWEN_PACE_GUARD["eligible_styles"].get(request.style)
    if (
        request.language != "zh"
        or seconds_per_character is None
        or request.instruction
        or re.search(r"[A-Za-z0-9]", text)
    ):
        return False
    characters = len(re.findall(r"[\u3400-\u9fff]", text))
    if characters < QWEN_PACE_GUARD["minimum_characters"]:
        return False
    signal = np.asarray(samples, dtype=np.float64).reshape(-1)
    frame_size = max(1, round(rate * QWEN_PACE_GUARD["frame_ms"] / 1000))
    starts = np.arange(0, len(signal), frame_size)
    lengths = np.minimum(frame_size, len(signal) - starts)
    rms = np.sqrt(np.add.reduceat(signal**2, starts) / lengths)
    active = rms >= 10 ** (QWEN_PACE_GUARD["active_floor_dbfs"] / 20)
    active_seconds = float(lengths[active].sum()) / rate
    limit = characters * seconds_per_character + QWEN_PACE_GUARD["slack_seconds"]
    return active_seconds > limit


class QwenMLXProvider:
    def __init__(self, path):
        for key in (
            "HF_HUB_OFFLINE",
            "TRANSFORMERS_OFFLINE",
            "HF_HUB_DISABLE_TELEMETRY",
        ):
            os.environ[key] = "1"
        if not path.is_dir():
            raise ValueError("请先安装本地 Qwen3-TTS 模型")
        config = json.loads((path / "config.json").read_text())
        if (
            config.get("tts_model_type") != "custom_voice"
            or config.get("tts_model_size") != "1b7"
        ):
            raise ValueError("需要支持语气指令的 1.7B CustomVoice 模型")
        installed = json.loads((path / "installed.json").read_text())
        if installed.get("revision") != QWEN_REVISION:
            raise ValueError(
                "TTS 模型版本与发布声音指纹不一致，请运行 install_tts.py 校验安装"
            )
        from mlx_audio.tts.utils import load_model

        self.model = load_model(str(path))

    def synthesize(self, request):
        import mlx.core as mx
        import numpy as np

        base_seed = generation_seed(request)
        with _GENERATION_LOCK:
            for attempt in range(QWEN_PACE_GUARD["maximum_attempts"]):
                parts = []
                rate = None
                mx.random.seed(_attempt_seed(base_seed, attempt))
                for result in self.model.generate_custom_voice(
                    text=normalize_text(request.text, request.language),
                    speaker=request.voice,
                    language="Chinese" if request.language == "zh" else "English",
                    instruct=voice_instruction(request),
                    **QWEN_GENERATION,
                ):
                    if rate is not None and rate != result.sample_rate:
                        raise ValueError("语音片段采样率不一致")
                    rate = result.sample_rate
                    # 转为 NumPy 会完成 MLX 惰性计算，仍在 RNG 锁范围内。
                    parts.append(np.asarray(result.audio))
                if not parts:
                    raise ValueError("语音模型没有生成音频")
                samples = np.concatenate(parts)
                audio = wav_result(samples, rate)
                if not _abnormal_pace(samples, rate, request):
                    return audio
        raise SynthesisRetryableError("语音节奏异常，请更换 variant 后重试")

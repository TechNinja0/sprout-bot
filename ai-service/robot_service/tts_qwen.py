"""Qwen3-TTS 1.7B 的 MLX 适配器，仅从本地路径加载。"""

import json
import os

from .tts import QWEN_REVISION, STYLES, wav_result


def voice_instruction(request):
    language = (
        "使用标准普通话，声调准确。"
        if request.language == "zh"
        else "Speak clear, natural English."
    )
    # MLX 的 Qwen speed 参数尚未实现。通过模型指令表达语速偏好，避免重采样改变音高。
    pace = (
        f"语速约为正常的 {request.speed:.2f} 倍，保持自然停顿。"
        if request.speed != 1
        else ""
    )
    return " ".join(
        filter(None, (language, STYLES[request.style][1], pace, request.instruction))
    )


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
        import numpy as np

        parts = []
        rate = None
        for result in self.model.generate_custom_voice(
            text=request.text,
            speaker=request.voice,
            language="Chinese" if request.language == "zh" else "English",
            instruct=voice_instruction(request),
            temperature=0.7,
            max_tokens=3072,
            verbose=False,
            stream=False,
        ):
            if rate is not None and rate != result.sample_rate:
                raise ValueError("语音片段采样率不一致")
            rate = result.sample_rate
            parts.append(np.asarray(result.audio))
        if not parts:
            raise ValueError("语音模型没有生成音频")
        return wav_result(np.concatenate(parts), rate)

"""30秒内语音的单窗口解码；语言检测和转写复用同一次音频编码。"""

from pathlib import Path

import numpy as np


def recognize(samples: np.ndarray, path: Path) -> str:
    import mlx.core as mx
    from mlx_whisper.audio import N_FRAMES, log_mel_spectrogram, pad_or_trim
    from mlx_whisper.decoding import DecodingOptions, decode
    from mlx_whisper.transcribe import ModelHolder

    if len(samples) > 30 * 16000:
        raise ValueError("ASR window exceeds 30 seconds")
    model = ModelHolder.get_model(str(path), mx.float16)
    mel = pad_or_trim(
        log_mel_spectrogram(samples, n_mels=model.dims.n_mels), N_FRAMES, axis=-2
    )
    result = decode(
        model,
        mel,
        DecodingOptions(
            language=None,
            temperature=0,
            without_timestamps=True,
            prompt="以下是简体中文和英语对话。英语，英文，儿歌，故事，小伙伴。",
        ),
    )
    if (
        result.no_speech_prob > 0.6 and result.avg_logprob < -1
    ) or result.compression_ratio > 2.4:
        return ""
    return result.text.strip()

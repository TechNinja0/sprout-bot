"""Qwen render-2 的主服务后处理；worker 导入指纹不会加载 av/NumPy。

仅接受单声道 PCM16 WAV。保留采样率，atempo 变速不变调；基于能量帧 RMS
做整段有界增益和峰值限制，不是 LUFS 响度保证。配置/算法变更须更新指纹。
"""

import io
import math
import numbers
import wave
from fractions import Fraction

AUDIO_PROFILE = {
    "version": "pcm16-atempo-rms-v2",
    "av": "18.1.0",
    "edge_guard_ms": 120,
    "minimum_trim_ms": 200,
    "tail_flush_ms": 200,
    "silence_pcm": 0,
    "peak_ceiling": 0.95,
    "rms_frame_ms": 20,
    "rms_floor_dbfs": -45.0,
    "rms_target_dbfs": -23.0,
    "maximum_gain_db": 3.0,
}


def _read_pcm(data):
    if not isinstance(data, bytes):
        raise ValueError("音频必须是 WAV bytes")
    try:
        with wave.open(io.BytesIO(data), "rb") as source:
            if source.getnchannels() != 1 or source.getsampwidth() != 2:
                raise ValueError("音频必须为单声道16位PCM WAV")
            rate, count = source.getframerate(), source.getnframes()
            if not 8000 <= rate <= 192000 or count < 1:
                raise ValueError("音频采样率或长度无效")
            pcm = source.readframes(count)
            if len(pcm) != count * 2:
                raise ValueError("WAV 音频数据不完整")
    except (wave.Error, EOFError) as exc:
        raise ValueError("无效 WAV 音频") from exc
    return pcm, rate


def _atempo(samples, rate, speed):
    import av
    import numpy as np

    # atempo 的分析窗会吞掉极短输入；补零用于 flush，之后只移除补出的静音。
    target_length = max(1, round(len(samples) / speed))
    padded = np.pad(samples, (0, round(rate * AUDIO_PROFILE["tail_flush_ms"] / 1000)))
    frame = av.AudioFrame.from_ndarray(
        padded.reshape(1, -1), format="flt", layout="mono"
    )
    frame.sample_rate, frame.time_base, frame.pts = rate, Fraction(1, rate), 0
    graph = av.filter.Graph()
    source = graph.add_abuffer(
        sample_rate=rate, format="flt", layout="mono", time_base=Fraction(1, rate)
    )
    tempo = graph.add("atempo", format(speed, ".17g"))
    sink = graph.add("abuffersink")
    source.link_to(tempo)
    tempo.link_to(sink)
    graph.configure()
    graph.push(frame)
    graph.push(None)
    parts = []
    while True:
        try:
            parts.append(graph.pull().to_ndarray().reshape(-1))
        except av.error.EOFError:
            break
    if not parts:
        raise ValueError("变速未生成音频")
    output = np.concatenate(parts)
    active = np.flatnonzero(output)
    # 不按理论时长硬截尾音；短片段可能有一个分析窗以内的时长偏差。
    end = max(target_length, int(active[-1]) + 1 if active.size else 0)
    return output[:end]


def _bounded_gain(signal, rate):
    import numpy as np

    frame_size = max(1, round(rate * AUDIO_PROFILE["rms_frame_ms"] / 1000))
    # 能量阈值只是有声帧近似，不宣称 VAD；不足一帧的尾部按实际长度计算。
    starts = np.arange(0, len(signal), frame_size)
    powers = np.add.reduceat(signal.astype(np.float64) ** 2, starts)
    lengths = np.minimum(frame_size, len(signal) - starts)
    rms = np.sqrt(powers / lengths)
    voiced = rms[rms >= 10 ** (AUDIO_PROFILE["rms_floor_dbfs"] / 20)]
    gain = 1.0
    if voiced.size:
        reference = float(np.median(voiced))
        gain_db = AUDIO_PROFILE["rms_target_dbfs"] - 20 * math.log10(reference)
        bound = AUDIO_PROFILE["maximum_gain_db"]
        gain = 10 ** (max(-bound, min(bound, gain_db)) / 20)
    peak = float(np.max(np.abs(signal)))
    if peak:
        gain = min(gain, AUDIO_PROFILE["peak_ceiling"] / peak)
    return gain


def finish_audio(data: bytes, speed: float = 1.0) -> bytes:
    """仅在主服务对 Qwen render-2 调用一次，随后才做 output_quality。

    speed 范围 0.7..1.3。合法全静音音频原样返回（不改变时长）；弱音保留。
    仅裁去超过保护垫的较长数字零静音；有底噪时可能不裁剪，以优先保护辅音。
    能量帧中位 RMS 决定整段增益（最多 ±3dB）；整体低于 -45dBFS 不提升。
    峰值限制优先，必要时可衰减超过 3dB；不做逐帧压缩或 LUFS 保证。
    """
    import numpy as np

    if (
        isinstance(speed, bool)
        or not isinstance(speed, numbers.Real)
        or not math.isfinite(speed)
        or not 0.7 <= speed <= 1.3
    ):
        raise ValueError("speed 必须在 0.7..1.3 范围内")
    pcm, rate = _read_pcm(data)
    samples = np.frombuffer(pcm, dtype="<i2")
    active = np.flatnonzero(samples)
    if not active.size:
        return data
    guard = round(rate * AUDIO_PROFILE["edge_guard_ms"] / 1000)
    minimum_trim = round(rate * AUDIO_PROFILE["minimum_trim_ms"] / 1000)
    start = max(0, int(active[0]) - guard)
    end = min(len(samples), int(active[-1]) + 1 + guard)
    if start < minimum_trim:
        start = 0
    if len(samples) - end < minimum_trim:
        end = len(samples)
    signal = samples[start:end].astype(np.float32) / 32768.0
    if speed != 1.0:
        signal = _atempo(signal, rate, float(speed))
    if not len(signal) or not np.isfinite(signal).all():
        raise ValueError("后处理生成无效音频")
    gain = _bounded_gain(signal, rate)
    if gain != 1.0:
        signal = signal * gain
    elif speed == 1.0 and start == 0 and end == len(samples):
        return data
    output = io.BytesIO()
    with wave.open(output, "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(rate)
        target.writeframes(np.rint(signal * 32768).astype("<i2").tobytes())
    return output.getvalue()

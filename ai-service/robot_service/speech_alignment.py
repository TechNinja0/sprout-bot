"""严格逐词对齐后切分同一次 TTS 的原始 PCM；任何不确定性均回退。

split_aligned_audio 的 words 使用 Whisper 的 word/start/end/probability 字段，
时间单位为秒。返回的 startMs/endMs 是原音频中采样点位置四舍五入后的毫秒值；
音频实际按采样帧切分，拼接各段 PCM 可逐字节还原输入 PCM（不含 WAV 容器）。
"""

import io
import math
import unicodedata
import wave
from dataclasses import dataclass

import numpy as np
from pypinyin import Style, lazy_pinyin

MAX_SECONDS = 30
MAX_TEXT_CHARS = 120
MIN_PROBABILITY = 0.6
# 保守的绝对阈值，避免用相对响度把轻辅音当作静音；单位为 PCM16 幅值。
QUIET_PEAK = 32
QUIET_RMS = 16
GUARD_SECONDS = 0.01  # 切点左右各至少 10ms，不能仅凭过零点切分。


@dataclass(frozen=True)
class _PCM:
    rate: int
    channels: int
    frames: int
    raw: bytes
    samples: np.ndarray


def _read_pcm16(audio: bytes) -> _PCM | None:
    if not isinstance(audio, bytes):
        return None
    try:
        with wave.open(io.BytesIO(audio), "rb") as wav:
            rate, channels, frames = (
                wav.getframerate(),
                wav.getnchannels(),
                wav.getnframes(),
            )
            if (
                wav.getsampwidth() != 2
                or wav.getcomptype() != "NONE"
                or channels not in (1, 2)
                or rate <= 0
                or not 0 < frames <= MAX_SECONDS * rate
            ):
                return None
            raw = wav.readframes(frames)
            if len(raw) != frames * channels * 2:
                return None
    except (wave.Error, EOFError, ValueError):
        return None
    return _PCM(
        rate, channels, frames, raw, np.frombuffer(raw, "<i2").reshape(-1, channels)
    )


def _quiet(samples: np.ndarray) -> bool:
    # 转浮点后计算，避免 -32768 的 abs 或平方溢出。每声道均须满足阈值。
    values = samples.astype(np.float64)
    return bool(
        values.size
        and np.max(np.abs(values)) <= QUIET_PEAK
        and np.max(np.sqrt(np.mean(values * values, axis=0))) <= QUIET_RMS
    )


def _safe_cut(pcm: _PCM, end: float, start: float) -> int | None:
    """只在合法词间空隙寻找保护窗，优先中点，否则选择距中点最近的安全窗。"""
    first, last = math.ceil(end * pcm.rate), math.floor(start * pcm.rate)
    guard = math.ceil(GUARD_SECONDS * pcm.rate)
    width = 2 * guard
    if last - first < width:
        return None
    middle = round((end + start) * pcm.rate / 2)
    if first + guard <= middle <= last - guard and _quiet(
        pcm.samples[middle - guard : middle + guard]
    ):
        return middle

    # 前缀和逐采样检查所有连续 >=20ms 的窗；不把离散安静采样拼成静音。
    # 每声道同时满足原有峰值和 RMS 门槛，且整个保护窗必须在两词之间。
    gap = pcm.samples[first:last].astype(np.float64)
    loud = np.concatenate(([0], np.cumsum(np.any(np.abs(gap) > QUIET_PEAK, axis=1))))
    energy = np.vstack((np.zeros((1, pcm.channels)), np.cumsum(gap * gap, axis=0)))
    safe = (loud[width:] - loud[:-width] == 0) & np.all(
        energy[width:] - energy[:-width] <= width * QUIET_RMS**2, axis=1
    )
    centers = np.flatnonzero(safe) + first + guard
    if not centers.size:
        return None
    cut = int(centers[np.argmin(np.abs(centers - middle))])
    return cut if _quiet(pcm.samples[cut - guard : cut + guard]) else None


def _normalize(text: str) -> str:
    return "".join(
        char
        for char in unicodedata.normalize("NFKC", text).casefold()
        if not char.isspace() and not unicodedata.category(char).startswith("P")
    )


def _pronunciation(text: str) -> list[tuple[str, str]] | None:
    # 保留标点分隔的词语上下文，不逐字调用，也不把不同页面拼成一个多音词。
    text = unicodedata.normalize("NFKC", text).casefold()
    syllables = lazy_pinyin(
        text,
        style=Style.TONE3,
        neutral_tone_with_five=True,
        tone_sandhi=False,
        errors=list,
    )
    if len(syllables) != len(text):
        return None  # 无法保持一字一位置时不推测边界。
    result = []
    for char, syllable in zip(text, syllables):
        if char.isspace() or unicodedata.category(char).startswith("P"):
            continue
        han = unicodedata.name(char, "").startswith(
            ("CJK UNIFIED IDEOGRAPH-", "CJK COMPATIBILITY IDEOGRAPH-")
        )
        numeric = unicodedata.numeric(char, None) is not None or char in "两兩幺"
        if han and not numeric and syllable != char:
            result.append(("pinyin", syllable))
        else:
            # 英文、数字、符号和词典未收录的字仍按原字符比较；拼音不与英文混淆。
            result.append(("literal", char))
    return result


def _same_pronunciation(reference: str, transcript: str) -> bool:
    if _normalize(reference) == _normalize(transcript):
        return True
    expected = _pronunciation(reference)
    return expected is not None and expected == _pronunciation(transcript)


def _number(value) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def split_aligned_audio(
    audio: bytes, texts: list[str], words: list[dict]
) -> list[dict] | None:
    """切分 <=30 秒 PCM16 WAV（单/双声道，保留采样率及全部采样帧）。

    所有页面经 NFKC、大小写、标点和空白归一化后必须非空、合计 <=120 字符，
    且与识别结果规范匹配，或中文在各自词语上下文中的拼音及声调完全相同。
    英文和数字不转写，不接受多音字候选读音或异音容错。页界必须是词界，且
    词间在原 PCM 中有至少 20ms 的连续低能量保护区；优先中点，否则选择
    距中点最近的安全位置。找不到则整组返回 None，不越过词的时间边界。
    """
    pcm = _read_pcm16(audio)
    if (
        pcm is None
        or _quiet(pcm.samples)
        or not isinstance(texts, list)
        or not texts
        or any(not isinstance(text, str) for text in texts)
        or not isinstance(words, list)
        or not words
    ):
        return None
    pages = [_normalize(text) for text in texts]
    if not all(pages) or sum(map(len, pages)) > MAX_TEXT_CHARS:
        return None

    recognized = []
    previous_end = 0.0
    for word in words:
        if not isinstance(word, dict) or not isinstance(word.get("word"), str):
            return None
        start, end, probability = (
            word.get("start"),
            word.get("end"),
            word.get("probability"),
        )
        if (
            not all(_number(value) for value in (start, end, probability))
            or not MIN_PROBABILITY <= probability <= 1
            or not previous_end <= start < end <= pcm.frames / pcm.rate
        ):
            return None
        previous_end = end
        text = _normalize(word["word"])
        if text:
            recognized.append((text, start, end, word["word"]))
        elif recognized:
            # 独立的标点 token 也要保留，防止拼音词典跨标点错误组词。
            previous = recognized[-1]
            recognized[-1] = (*previous[:3], previous[3] + word["word"])
    if sum(len(word[0]) for word in recognized) != sum(map(len, pages)):
        return None

    # 累计字符位置只用于验证词界，绝不用于估计时间或分摊音频。
    edges = {}
    offset = 0
    for index, word in enumerate(recognized):
        offset += len(word[0])
        edges[offset] = index + 1
    cuts = [0]
    offset = 0
    first_word = 0
    for page, reference in zip(pages, texts):
        offset += len(page)
        if offset not in edges:
            return None
        next_word = edges[offset]
        transcript = "".join(word[3] for word in recognized[first_word:next_word])
        if not _same_pronunciation(reference, transcript):
            return None
        first_word = next_word
        if next_word == len(recognized):
            continue
        end, start = recognized[next_word - 1][2], recognized[next_word][1]
        cut = _safe_cut(pcm, end, start)
        if cut is None or cut <= cuts[-1]:
            return None
        cuts.append(cut)
    cuts.append(pcm.frames)

    result = []
    for start, end in zip(cuts, cuts[1:]):
        if end <= start or _quiet(pcm.samples[start:end]):
            return None
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(pcm.channels)
            wav.setsampwidth(2)
            wav.setframerate(pcm.rate)
            wav.writeframes(pcm.raw[start * pcm.channels * 2 : end * pcm.channels * 2])
        result.append(
            {
                "audio": output.getvalue(),
                "startMs": round(start * 1000 / pcm.rate),
                "endMs": round(end * 1000 / pcm.rate),
            }
        )
    return result

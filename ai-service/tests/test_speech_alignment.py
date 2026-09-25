import base64
import io
import json
import sys
import wave
from types import SimpleNamespace

import numpy as np
import pytest
from robot_service import model_worker
from robot_service.speech_alignment import split_aligned_audio


def wav_bytes(samples, rate=16000, width=2):
    samples = np.asarray(samples, dtype="<i2")
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1 if samples.ndim == 1 else samples.shape[1])
        wav.setsampwidth(width)
        wav.setframerate(rate)
        wav.writeframes(samples.tobytes())
    return output.getvalue()


def pcm_bytes(audio):
    with wave.open(io.BytesIO(audio), "rb") as wav:
        return wav.getparams(), wav.readframes(wav.getnframes())


def word(text, start, end, probability=0.95):
    return {"word": text, "start": start, "end": end, "probability": probability}


def fixture(rate=16000, channels=1):
    # 两段波形之间的静音为 [0.4, 0.6)，首尾静音也必须完整保留。
    samples = np.zeros(rate, dtype="<i2")
    for start, end in ((0.1, 0.4), (0.6, 0.9)):
        first, last = round(start * rate), round(end * rate)
        samples[first:last] = np.round(
            8000 * np.sin(2 * np.pi * 220 * np.arange(last - first) / rate)
        ).astype("<i2")
    if channels == 2:
        samples = np.column_stack([samples, samples // 2])
    return samples, [word("你好", 0.1, 0.4), word("世界", 0.6, 0.9)]


@pytest.mark.parametrize("rate", [16000, 22050, 24000, 44100, 48000])
@pytest.mark.parametrize("channels", [1, 2])
def test_split_restores_original_pcm_bit_exact(rate, channels):
    samples, words = fixture(rate, channels)
    audio = wav_bytes(samples, rate)
    result = split_aligned_audio(audio, ["你好，", "世界！"], words)
    assert result is not None
    assert [(part["startMs"], part["endMs"]) for part in result] == [
        (0, 500),
        (500, 1000),
    ]
    chunks = [pcm_bytes(part["audio"]) for part in result]
    assert b"".join(raw for _, raw in chunks) == pcm_bytes(audio)[1]
    assert all(params.nchannels == channels for params, _ in chunks)
    assert all(
        params.sampwidth == 2 and params.framerate == rate for params, _ in chunks
    )


def test_three_pages_and_fractional_frame_midpoints():
    rate = 22050
    samples = np.zeros(rate * 2, dtype="<i2")
    words = [word("One", 0.1, 0.4), word("two", 0.603, 0.9), word("3", 1.104, 1.4)]
    for item in words:
        samples[round(item["start"] * rate) : round(item["end"] * rate)] = 4000
    audio = wav_bytes(samples, rate)
    result = split_aligned_audio(audio, ["ONE!", "Two.", "３"], words)
    assert result is not None and len(result) == 3
    cut1, cut2 = round(0.5015 * rate), round(1.002 * rate)
    assert [pcm_bytes(part["audio"])[0].nframes for part in result] == [
        cut1,
        cut2 - cut1,
        len(samples) - cut2,
    ]
    assert result[0]["endMs"] == result[1]["startMs"] == round(cut1 * 1000 / rate)
    assert result[1]["endMs"] == result[2]["startMs"] == round(cut2 * 1000 / rate)
    assert b"".join(pcm_bytes(part["audio"])[1] for part in result) == samples.tobytes()


@pytest.mark.parametrize(
    "texts,recognized",
    [
        (["“你好，１２３！”", " Hello, WORLD 42. "], ["你好123", "hello world42"]),
        (["It's—2026!", "第２页。"], ["IT’S 2026", "第2页"]),
        (["Hello\nworld", "再见……"], ["HELLO WORLD", "再见"]),
    ],
)
def test_only_case_nfkc_punctuation_and_whitespace_are_ignored(texts, recognized):
    samples, words = fixture()
    for item, text in zip(words, recognized):
        item["word"] = text
    assert split_aligned_audio(wav_bytes(samples), texts, words) is not None


@pytest.mark.parametrize(
    "texts,recognized",
    [
        (["你好", "世界"], ["您好", "世界"]),
        (["12", "世界"], ["twelve", "世界"]),
        (["十二", "世界"], ["12", "世界"]),
        (["1+2", "世界"], ["12", "世界"]),
        (["猫🐱", "世界"], ["猫", "世界"]),
        (["你好", "世界"], ["你好啊", "世界"]),
        (["你好呀", "世界"], ["你好", "世界"]),
    ],
)
def test_wrong_missing_extra_characters_numbers_and_symbols_fall_back(
    texts, recognized
):
    samples, words = fixture()
    for item, text in zip(words, recognized):
        item["word"] = text
    assert split_aligned_audio(wav_bytes(samples), texts, words) is None


@pytest.mark.parametrize(
    "reference,transcript",
    [
        ("它回家了", "他回家了"),
        ("她回家了", "它回家了"),
        ("頭", "头"),
        ("银航", "银行"),
        ("音月", "音乐"),
        ("虫新", "重新"),
        ("它有１２个 APP", "他有12个 app"),
    ],
)
def test_contextual_homophones_with_identical_tones_keep_pcm_bit_exact(
    reference, transcript
):
    samples, words = fixture()
    words[0]["word"] = transcript
    audio = wav_bytes(samples)
    parts = split_aligned_audio(audio, [reference, "世界"], words)
    assert parts is not None
    assert [(part["startMs"], part["endMs"]) for part in parts] == [
        (0, 500),
        (500, 1000),
    ]
    assert b"".join(pcm_bytes(part["audio"])[1] for part in parts) == samples.tobytes()


@pytest.mark.parametrize(
    "reference,transcript",
    [
        ("银行", "银形"),  # hang2 / xing2：不能枚举「行」的候选读音。
        ("行走", "航走"),
        ("长大", "常大"),  # 逐字读默认 chang2 会误放行。
        ("快乐", "快月"),
        ("重量", "虫量"),
        ("它", "塔"),  # 声母韵母相同，声调不同。
        ("来了", "来乐"),  # 轻声 le5 不能与 le4 混用。
        ("女", "努"),
        ("它", "ta1"),
        ("它是 right", "他是 write"),
        ("它有12个", "他有13个"),
        ("它有四个", "他有寺个"),
        ("它有十二个", "他有拾贰个"),
        ("它有两只", "他有俩只"),
        ("它是Ａ＋Ｂ", "他是a-b"),
    ],
)
def test_other_pronunciations_tones_english_and_numbers_are_not_relaxed(
    reference, transcript
):
    samples, words = fixture()
    words[0]["word"] = transcript
    assert split_aligned_audio(wav_bytes(samples), [reference, "世界"], words) is None


@pytest.mark.parametrize("reference,accepted", [("银航", True), ("银形", False)])
def test_context_spans_whisper_tokens(reference, accepted):
    samples, _ = fixture()
    words = [word("银", 0.1, 0.25), word("行", 0.25, 0.4), word("世界", 0.6, 0.9)]
    parts = split_aligned_audio(wav_bytes(samples), [reference, "世界"], words)
    assert (parts is not None) is accepted


@pytest.mark.parametrize(
    "reference,accepted", [("银，形走", True), ("银，航走", False)]
)
def test_context_does_not_cross_standalone_punctuation(reference, accepted):
    samples, _ = fixture()
    words = [
        word("银", 0.1, 0.2),
        word("，", 0.2, 0.21),
        word("行走", 0.21, 0.4),
        word("世界", 0.6, 0.9),
    ]
    parts = split_aligned_audio(wav_bytes(samples), [reference, "世界"], words)
    assert (parts is not None) is accepted


def test_context_does_not_cross_page_boundary():
    samples, _ = fixture()
    words = [word("银", 0.1, 0.4), word("行走", 0.6, 0.9)]
    assert split_aligned_audio(wav_bytes(samples), ["银", "形走"], words) is not None


@pytest.mark.parametrize(
    "unsafe", ["confidence", "overlap", "short_gap", "consonant", "word_span"]
)
def test_homophones_do_not_bypass_existing_safety_checks(unsafe):
    samples, _ = fixture()
    words = [word("他回", 0.1, 0.4), word("家了", 0.6, 0.9)]
    texts = ["它回", "家了"]
    if unsafe == "confidence":
        words[0]["probability"] = 0.599
    elif unsafe == "overlap":
        words[0]["end"] = 0.61
    elif unsafe == "short_gap":
        words[0]["end"], words[1]["start"] = 0.495, 0.505
    elif unsafe == "consonant":
        samples[6400:9600] = 80
        samples[8000] = 0
    elif unsafe == "word_span":
        texts = ["它", "回家了"]
    assert split_aligned_audio(wav_bytes(samples), texts, words) is None


def test_word_cannot_span_page_boundary_even_when_complete_text_matches():
    samples, words = fixture()
    assert split_aligned_audio(wav_bytes(samples), ["你", "好世界"], words) is None
    words = [word("hello", 0.1, 0.9)]
    assert split_aligned_audio(wav_bytes(samples), ["he", "llo"], words) is None


@pytest.mark.parametrize(
    "field,value",
    [
        ("probability", 0.599999),
        ("probability", 1.01),
        ("probability", float("nan")),
        ("probability", None),
        ("probability", True),
        ("probability", "0.9"),
        ("start", -0.01),
        ("start", float("nan")),
        ("start", 0.4),
        ("end", float("inf")),
        ("end", 1.01),
        ("end", 0.1),
        ("end", -1),
        ("word", None),
    ],
)
def test_invalid_word_fields_fall_back(field, value):
    samples, words = fixture()
    words[0][field] = value
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


def test_threshold_is_inclusive():
    samples, words = fixture()
    for item in words:
        item["probability"] = 0.6
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is not None


@pytest.mark.parametrize(
    "words",
    [
        [word("你", 0.1, 0.3), word("好", 0.2, 0.4), word("世界", 0.6, 0.9)],
        [word("你好", 0.1, 0.7), word("世界", 0.6, 0.9)],
        [word("你好", 0.6, 0.9), word("世界", 0.1, 0.4)],
        [word("你好", 0.1, 0.4), {"word": "世界", "start": 0.6, "end": 0.9}],
        [],
        [None],
    ],
)
def test_overlap_out_of_order_and_missing_words_fall_back(words):
    samples, _ = fixture()
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


def test_punctuation_only_tokens_do_not_change_character_mapping():
    samples, words = fixture()
    words.insert(1, word("，", 0.41, 0.42))
    assert (
        split_aligned_audio(wav_bytes(samples), ["你好，", "世界"], words) is not None
    )


@pytest.mark.parametrize(
    "texts", [[], ["", "你好世界"], ["你好", "！"], [None], "你好世界"]
)
def test_invalid_or_empty_pages_fall_back(texts):
    samples, words = fixture()
    assert split_aligned_audio(wav_bytes(samples), texts, words) is None


def test_character_and_duration_limits():
    samples, _ = fixture()
    audio = wav_bytes(samples)
    assert (
        split_aligned_audio(audio, ["a" * 120], [word("a" * 120, 0.1, 0.9)]) is not None
    )
    assert split_aligned_audio(audio, ["a" * 121], [word("a" * 121, 0.1, 0.9)]) is None
    long_samples = np.pad(samples, (0, 29 * 16000))
    assert (
        split_aligned_audio(wav_bytes(long_samples), ["a"], [word("a", 0.1, 0.9)])
        is not None
    )
    long_samples = np.append(long_samples, np.int16(0))
    assert (
        split_aligned_audio(wav_bytes(long_samples), ["a"], [word("a", 0.1, 0.9)])
        is None
    )


@pytest.mark.parametrize(
    "audio", [b"bad wav", b"", wav_bytes([]), wav_bytes(np.zeros(16000))]
)
def test_bad_empty_and_silent_audio_fall_back(audio):
    assert split_aligned_audio(audio, ["你好"], [word("你好", 0.1, 0.4)]) is None


def test_wrong_format_and_truncated_pcm_fall_back():
    samples, words = fixture()
    for audio in [wav_bytes(samples, width=1), wav_bytes(samples)[:-1]]:
        assert split_aligned_audio(audio, ["你好", "世界"], words) is None


@pytest.mark.parametrize("amplitude", [33, 80, 2000, -32768])
def test_no_safe_window_in_word_gap_must_not_cut_voiced_or_soft_consonants(amplitude):
    samples, words = fixture()
    # 模拟时间戳未覆盖的辅音，并在切点制造过零点。
    samples[6400:9600] = amplitude
    samples[8000] = 0
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


def test_low_energy_floor_is_allowed_but_sustained_energy_is_not():
    samples, words = fixture()
    samples[6400:9600] = 8
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is not None
    samples[6400:9600] = 20  # 峰值虽低于 32，但 RMS 已超过 16。
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


def test_voice_in_either_stereo_channel_blocks_cut():
    samples, words = fixture(channels=2)
    samples[6400:9600, 1] = 300
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


@pytest.mark.parametrize("channels", [1, 2])
def test_searches_nearest_safe_contiguous_window_without_touching_consonant(channels):
    samples, words = fixture(channels=channels)
    samples[6400:9600] = 80
    # 中点有弱辅音，只有 [0.45, 0.47) 这连续 20ms 安静。
    samples[7200:7520] = 8
    audio = wav_bytes(samples)
    parts = split_aligned_audio(audio, ["你好", "世界"], words)
    assert parts is not None
    assert parts[0]["endMs"] == parts[1]["startMs"] == 460
    assert pcm_bytes(parts[0]["audio"])[0].nframes == 7360
    assert b"".join(pcm_bytes(part["audio"])[1] for part in parts) == samples.tobytes()


def test_disconnected_quiet_samples_cannot_form_a_safe_window():
    samples, words = fixture()
    samples[6400:9600] = 0
    samples[6400:9600:160] = 80  # 每 10ms 一个峰值，没有连续 20ms 安静窗。
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


def test_safe_window_outside_word_gap_does_not_authorize_cut():
    samples, words = fixture()
    samples[6400:9600] = 80
    words[0]["end"], words[1]["start"] = 0.48, 0.52
    samples[7200:7520] = 0  # 虽有安全窗，但在前词结束之前。
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


@pytest.mark.parametrize("end,start", [(0.5, 0.5), (0.499, 0.501)])
def test_zero_or_tiny_word_gap_is_not_safe_even_when_pcm_is_silent(end, start):
    samples, words = fixture()
    words[0]["end"], words[1]["start"] = end, start
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


def test_silent_page_with_hallucinated_words_falls_back():
    samples, words = fixture()
    samples[8000:] = 0
    assert split_aligned_audio(wav_bytes(samples), ["你好", "世界"], words) is None


@pytest.fixture
def fake_whisper(monkeypatch):
    constructors, transcriptions = [], []

    class Model:
        def __init__(self, *args, **kwargs):
            constructors.append((args, kwargs))

        def transcribe(self, samples, **kwargs):
            transcriptions.append((samples, kwargs))
            segments = (
                SimpleNamespace(words=[SimpleNamespace(**item)])
                for item in [word("你好", 0.1, 0.4), word("世界", 0.6, 0.9)]
            )
            return segments, None

    monkeypatch.setitem(
        sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Model)
    )
    monkeypatch.setattr(model_worker, "MODELS", {})
    return constructors, transcriptions


def align_task(audio, root, language="zh"):
    return {
        "kind": "align",
        "root": str(root),
        "language": language,
        "audio": base64.b64encode(audio).decode(),
    }


@pytest.mark.parametrize("rate,language", [(16000, "zh"), (24000, "en"), (44100, "zh")])
def test_worker_uses_local_small_without_vad_and_reuses_asr_model(
    fake_whisper, tmp_path, monkeypatch, rate, language
):
    constructors, transcriptions = fake_whisper
    monkeypatch.setenv("ROBOT_ASR_BACKEND", "mlx")
    samples, words = fixture(rate)
    audio = wav_bytes(samples, rate)
    task = align_task(audio, tmp_path, language)
    result = model_worker.execute(task)
    assert result == {"words": words}
    json.dumps(result, allow_nan=False)
    assert split_aligned_audio(audio, ["你好", "世界"], result["words"]) is not None
    model_worker.execute(task)
    assert len(constructors) == 1
    assert constructors[0] == (
        (str(tmp_path / "faster-whisper-small"),),
        {
            "device": "cpu",
            "compute_type": "int8",
            "cpu_threads": 4,
            "local_files_only": True,
        },
    )
    assert ("asr", str(tmp_path)) in model_worker.MODELS
    received, options = transcriptions[0]
    assert len(received) == 16000 and received.dtype == np.float32
    assert options == {
        "language": language,
        "beam_size": 5,
        "word_timestamps": True,
        "temperature": 0,
        "condition_on_previous_text": False,
        "vad_filter": False,
    }
    assert np.all(received[6500:9500] == 0)  # 停顿原位保留，未通过 VAD 拼接。


def test_worker_reuses_a_previously_loaded_asr_model(fake_whisper, tmp_path):
    constructors, transcriptions = fake_whisper
    model_worker.MODELS[("asr", str(tmp_path))] = SimpleNamespace(
        transcribe=lambda samples, **kwargs: (iter([SimpleNamespace(words=None)]), None)
    )
    samples, _ = fixture()
    assert model_worker.execute(align_task(wav_bytes(samples), tmp_path)) == {
        "words": []
    }
    assert not constructors and not transcriptions


@pytest.mark.parametrize(
    "audio",
    [
        b"bad",
        wav_bytes([]),
        wav_bytes(np.zeros(16000)),
        wav_bytes(np.ones(16000, dtype="<i2") * 8),
        wav_bytes(np.ones(30 * 16000 + 1, dtype="<i2") * 4000),
        wav_bytes(np.ones(16000, dtype="<i2") * 4000, width=1),
    ],
)
def test_worker_invalid_silent_and_long_audio_skips_model(
    fake_whisper, tmp_path, audio
):
    assert model_worker.execute(align_task(audio, tmp_path)) == {"words": []}
    assert fake_whisper == ([], [])


@pytest.mark.parametrize(
    "change", [{"audio": "%%%"}, {"audio": None}, {"language": "auto"}]
)
def test_worker_bad_task_falls_back(fake_whisper, tmp_path, change):
    samples, _ = fixture()
    task = align_task(wav_bytes(samples), tmp_path)
    task.update(change)
    assert model_worker.execute(task) == {"words": []}
    assert fake_whisper == ([], [])

import base64
import io
import json
import sys
from dataclasses import replace
from types import SimpleNamespace

import numpy as np
import pytest
from robot_service import tts, tts_worker
from robot_service.speech_text import normalize_text
from robot_service.tts import make_request, render_profile, wav_result
from robot_service.tts_qwen import QwenMLXProvider, generation_seed, voice_instruction


@pytest.fixture(autouse=True)
def qwen(monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")


def test_explicit_language_pins_speaker_for_english_fragments():
    voice = {"zh": "Vivian", "en": "Ryan"}
    assert make_request("apple", voice).voice == "Ryan"
    assert make_request("apple", voice, language="zh").voice == "Vivian"
    assert make_request("苹果", voice, language="en").voice == "Ryan"
    assert make_request("Hello", voice, language=None).language == "en"


def test_defaults_follow_style_and_variant_is_zero():
    assert make_request("你好", {}).style == "neutral"
    assert make_request("你好", {}, story=True).style == "neutral"
    for style in ("neutral", "gentle", "cheerful"):
        request = make_request(
            "你好", {"style": style, "storyStyle": "default"}, story=True
        )
        assert request.style == style and request.variant == 0
    assert make_request("你好", {"style": "gentle"}, story=True).style == "gentle"


@pytest.mark.parametrize("variant", [-1, 2**31, True, False, 1.0, "1", None])
def test_invalid_variant(variant):
    with pytest.raises(ValueError, match="variant"):
        make_request("你好", {}, variant=variant)


@pytest.mark.parametrize("language", ["auto", "ja", "ZH", "", 1])
def test_invalid_language(language):
    with pytest.raises(ValueError, match="语言"):
        make_request("你好", {}, language=language)


def test_seed_is_based_on_normalized_text_and_variant_not_speed():
    request = make_request("有12个苹果。", {}, language="zh", variant=2**31 - 1)
    assert request.text == "有12个苹果。"
    assert generation_seed(request) == generation_seed(
        replace(request, text="有十二个苹果。")
    )
    assert generation_seed(request) == generation_seed(replace(request, speed=0.7))
    assert voice_instruction(request) == voice_instruction(replace(request, speed=1.3))
    for kwargs in (
        {"variant": 0},
        {"voice": "Vivian"},
        {"language": "en"},
        {"style": "gentle"},
        {"instruction": "自然停顿"},
        {"text": "再见"},
    ):
        assert generation_seed(request) != generation_seed(replace(request, **kwargs))


def test_profile_tracks_parameters_and_processing_preserves_legacy(monkeypatch):
    from robot_service import speech_audio, speech_text

    original = render_profile()
    assert ":render-2:" in original
    for mapping, key, value in (
        (tts.QWEN_GENERATION, "temperature", 0.6),
        (tts.QWEN_GENERATION, "top_p", 0.8),
        (tts.QWEN_PACE_GUARD["eligible_styles"], "gentle", 0.4),
        (tts.QWEN_PACE_GUARD, "eligible_styles", {"neutral": 0.34}),
        (speech_audio.AUDIO_PROFILE, "edge_guard_ms", 150),
        (tts.STYLES, "neutral", ("自然", "新的语气")),
    ):
        with monkeypatch.context() as patch:
            patch.setitem(mapping, key, value)
            assert render_profile() != original
    monkeypatch.setattr(speech_text, "TEXT_PROFILE", "next-version")
    assert render_profile() != original
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "kokoro")
    assert render_profile() == "kokoro-v1.0:sherpa-onnx-1.13.8:render-1"


def test_provider_seeds_every_call_and_passes_explicit_parameters(monkeypatch):
    events = []
    core = SimpleNamespace(
        random=SimpleNamespace(seed=lambda seed: events.append(("seed", seed)))
    )
    monkeypatch.setitem(sys.modules, "mlx", SimpleNamespace(core=core))
    monkeypatch.setitem(sys.modules, "mlx.core", core)

    def generate(**kwargs):
        events.append(("generate", kwargs))
        yield SimpleNamespace(audio=np.zeros(2400), sample_rate=24000)

    provider = QwenMLXProvider.__new__(QwenMLXProvider)
    provider.model = SimpleNamespace(generate_custom_voice=generate)
    request = make_request("这是3.5kg。", {"zh": "Eric"}, language="zh", variant=7)
    provider.synthesize(request)
    provider.synthesize(replace(request, speed=0.7))
    assert events[0] == events[2] == ("seed", generation_seed(request))
    assert events[1] == events[3]
    kwargs = events[1][1]
    assert kwargs["text"] == normalize_text(request.text, request.language)
    assert kwargs["speaker"] == "Eric" and kwargs["language"] == "Chinese"
    assert "speed" not in kwargs and "语速" not in kwargs["instruct"]
    assert {key: kwargs[key] for key in tts.QWEN_GENERATION} == tts.QWEN_GENERATION
    assert (
        kwargs["temperature"] == 0.5
        and kwargs["top_k"] == 50
        and kwargs["top_p"] == 0.9
    )


def test_worker_forwards_language_variant_and_continues_after_invalid_request(
    monkeypatch,
):
    calls = []
    audio = wav_result(np.zeros(240), 24000)

    def synthesize(request):
        calls.append(request)
        return audio

    monkeypatch.setattr(
        tts_worker,
        "create_provider",
        lambda root: SimpleNamespace(synthesize=synthesize),
    )
    tasks = [
        {"text": "apple", "root": ".", "language": "zh", "variant": 42},
        {"text": "apple", "root": ".", "variant": -1},
        {"text": "apple", "root": "."},
    ]
    monkeypatch.setattr(sys, "stdin", io.StringIO("\n".join(map(json.dumps, tasks))))
    output = io.StringIO()
    monkeypatch.setattr(sys, "stdout", output)
    tts_worker.main()
    results = list(map(json.loads, output.getvalue().splitlines()))
    assert (
        calls[0].language == "zh"
        and calls[0].voice == "Serena"
        and calls[0].variant == 42
    )
    assert calls[1].language == "en" and calls[1].variant == 0
    assert base64.b64decode(results[0]["audio"]) == audio.wav
    assert results[1] == {"error": "ValueError"}
    assert results[2]["durationMs"] == 10


def test_kokoro_request_keeps_text_speed_and_legacy_profile(monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "kokoro")
    request = make_request("重3.5kg。", {"speed": 0.7}, language="zh")
    assert request.text == "重3.5kg。" and request.speed == 0.7
    assert request.voice == "zh-girl" and render_profile() == tts.LEGACY_PROFILE

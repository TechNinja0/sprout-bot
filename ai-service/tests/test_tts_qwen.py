import io
import json
import sys
from dataclasses import replace
from types import SimpleNamespace

import numpy as np
import pytest
from robot_service import tts_worker
from robot_service.tts import SynthesisRetryableError, make_request
from robot_service.tts_qwen import (
    QwenMLXProvider,
    _abnormal_pace,
    _attempt_seed,
    generation_seed,
)

TEXT = "小兔子推开门，外面下雪了。它开心地笑了。"
RATE = 24000


@pytest.fixture
def request_data(monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    return make_request(TEXT, {}, language="zh", variant=1)


def signal(seconds):
    return np.full(round(RATE * seconds), 0.05, dtype=np.float32)


def test_duration_guard_uses_active_frames_not_total_length(request_data):
    assert not _abnormal_pace(signal(3.25), RATE, request_data)
    assert _abnormal_pace(signal(7.76), RATE, request_data)
    paused = np.concatenate([signal(1.5), np.zeros(RATE * 7), signal(1.75)])
    assert not _abnormal_pace(paused, RATE, request_data)


@pytest.mark.parametrize(
    "changes",
    [
        {"text": "小兔子来了。"},
        {"language": "en"},
        {"style": "soothing"},
        {"text": "小兔子说hello，然后开心地笑了。"},
        {"instruction": "请慢慢地读"},
    ],
)
def test_guard_does_not_force_short_english_or_expressive_reading(
    request_data, changes
):
    assert not _abnormal_pace(signal(12), RATE, replace(request_data, **changes))


def mock_provider(monkeypatch, lengths):
    seeds = []
    core = SimpleNamespace(random=SimpleNamespace(seed=seeds.append))
    monkeypatch.setitem(sys.modules, "mlx", SimpleNamespace(core=core))
    monkeypatch.setitem(sys.modules, "mlx.core", core)
    outputs = iter(lengths)

    def generate(**kwargs):
        yield SimpleNamespace(audio=signal(next(outputs)), sample_rate=RATE)

    provider = QwenMLXProvider.__new__(QwenMLXProvider)
    provider.model = SimpleNamespace(generate_custom_voice=generate)
    return provider, seeds


@pytest.mark.parametrize("style", ["neutral", "gentle", "cheerful", "storytelling"])
def test_retry_is_bounded_deterministic_and_speed_independent(
    monkeypatch, request_data, style
):
    request_data = replace(request_data, style=style)
    provider, seeds = mock_provider(monkeypatch, [7.76, 3.25, 7.76, 3.25])
    first = provider.synthesize(request_data)
    second = provider.synthesize(replace(request_data, speed=0.7))
    base = generation_seed(request_data)
    assert seeds == [base, _attempt_seed(base, 1)] * 2
    assert seeds[0] != seeds[1]
    assert first.wav == second.wav and first.duration_ms == 3250


@pytest.mark.parametrize(
    "style,limit",
    [("neutral", 6.58), ("gentle", 6.92), ("cheerful", 6.58), ("storytelling", 7.60)],
)
def test_style_threshold_allows_normal_expression_but_rejects_excess(
    request_data, style, limit
):
    request_data = replace(request_data, style=style)
    assert not _abnormal_pace(signal(limit - 0.04), RATE, request_data)
    assert _abnormal_pace(signal(limit + 0.04), RATE, request_data)
    assert not _abnormal_pace(
        signal(12), RATE, replace(request_data, instruction="请慢慢地读")
    )
    assert not _abnormal_pace(
        signal(12), RATE, replace(request_data, text="小兔子来了。")
    )
    assert not _abnormal_pace(signal(12), RATE, replace(request_data, language="en"))


@pytest.mark.parametrize("story,style", [(False, "gentle"), (True, "storytelling")])
def test_saved_default_styles_are_protected_without_migrating_config(
    monkeypatch, story, style
):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    saved_voice = {"zh": "Serena", "style": "gentle", "storyStyle": "storytelling"}
    original = saved_voice.copy()
    request_data = make_request(TEXT, saved_voice, story=story, language="zh")
    assert request_data.style == style
    provider, seeds = mock_provider(monkeypatch, [7.76, 3.25])
    assert provider.synthesize(request_data).duration_ms == 3250
    assert len(seeds) == 2
    assert saved_voice == original


def test_all_bad_candidates_raise_retryable_error_without_stretching(
    monkeypatch, request_data
):
    provider, seeds = mock_provider(monkeypatch, [7.76, 8.5])
    with pytest.raises(SynthesisRetryableError, match="更换 variant"):
        provider.synthesize(request_data)
    assert len(seeds) == 2


def test_normal_candidate_does_not_pay_retry_cost(monkeypatch, request_data):
    provider, seeds = mock_provider(monkeypatch, [3.25])
    assert provider.synthesize(request_data).duration_ms == 3250
    assert seeds == [generation_seed(request_data)]


def test_worker_reports_machine_readable_retryable_pace_error(
    monkeypatch, request_data
):
    def synthesize(request):
        raise SynthesisRetryableError("语音节奏异常，请更换 variant 后重试")

    monkeypatch.setattr(
        tts_worker,
        "create_provider",
        lambda root: SimpleNamespace(synthesize=synthesize),
    )
    monkeypatch.setattr(
        sys, "stdin", io.StringIO(json.dumps({"text": TEXT, "root": "."}) + "\n")
    )
    output = io.StringIO()
    monkeypatch.setattr(sys, "stdout", output)
    tts_worker.main()
    result = json.loads(output.getvalue())
    assert result["error"] == "SynthesisRetryableError"
    assert result["code"] == "tts_unstable_pace" and result["retryable"] is True
    assert "variant" in result["message"] and "audio" not in result

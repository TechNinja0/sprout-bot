import asyncio
import base64
import io
import json
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import numpy as np
import pytest
from robot_service.schemas import Voice
from robot_service.tts import capabilities, make_request, wav_result
from robot_service.tts_qwen import QwenMLXProvider, voice_instruction


def test_legacy_voice_config_uses_native_chinese_voice(monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    request = make_request("早上好。", Voice().model_dump())
    assert request.voice == "Serena" and request.language == "zh"
    assert request.style == "gentle"
    assert make_request("你好", {"zh": "zh-boy"}).voice == "Uncle_Fu"
    assert make_request("Hello.", {}).voice == "Ryan"
    assert make_request("apple，苹果。", {}).language == "zh"


def test_story_uses_its_own_voice_and_style(monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    voice = Voice(
        zh="Serena",
        story="Uncle_Fu",
        style="cheerful",
        storyStyle="soothing",
        instruction="每句话之间留一点停顿",
        speed=0.9,
    )
    normal = make_request("小兔子回家了。", voice.model_dump())
    story = make_request("小兔子回家了。", voice.model_dump(), True)
    assert (normal.voice, normal.style) == ("Serena", "cheerful")
    assert (story.voice, story.style) == ("Uncle_Fu", "soothing")
    instruction = voice_instruction(story)
    assert "标准普通话" in instruction and "0.90" in instruction
    assert "每句话之间" in instruction and "舒缓" in instruction


def test_provider_switch_keeps_config_and_reports_missing_features(
    monkeypatch, tmp_path
):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "kokoro")
    request = make_request("你好", {"zh": "Serena", "style": "gentle"})
    assert request.voice == "zh-girl"
    info = capabilities(tmp_path)
    assert not info["supportsInstruction"] and not info["ready"]
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    assert capabilities(tmp_path)["supportsInstruction"]
    with pytest.raises(ValueError, match="音色"):
        make_request("你好", {"zh": "nonexistent"})


def test_qwen_adapter_passes_style_without_speaking_it(monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    calls = []

    def generate(**kwargs):
        calls.append(kwargs)
        yield SimpleNamespace(audio=np.zeros(2400), sample_rate=24000)

    provider = QwenMLXProvider.__new__(QwenMLXProvider)
    provider.model = SimpleNamespace(generate_custom_voice=generate)
    result = provider.synthesize(make_request("早上好。", {"style": "cheerful"}))
    assert calls[0]["text"] == "早上好。"
    assert "轻快开心" in calls[0]["instruct"]
    assert calls[0]["language"] == "Chinese"
    assert result.duration_ms == 100
    with wave.open(io.BytesIO(result.wav)) as wav:
        assert wav.getframerate() == 24000 and wav.getsampwidth() == 2


def test_qwen_rejects_wrong_model_before_import(tmp_path):
    (tmp_path / "config.json").write_text(
        json.dumps({"tts_model_size": "0b6", "tts_model_type": "custom_voice"})
    )
    with pytest.raises(ValueError, match="1.7B"):
        QwenMLXProvider(tmp_path)


def test_model_lock_matches_published_render_fingerprint():
    from robot_service.tts import QWEN_DIR, QWEN_REVISION

    lock = json.loads(
        (
            Path(__file__).resolve().parents[2] / "scripts/tts-model.lock.json"
        ).read_text()
    )
    assert lock["directory"] == QWEN_DIR
    assert lock["revision"] == QWEN_REVISION


def test_qwen_rejects_unpinned_weights_before_import(tmp_path):
    (tmp_path / "config.json").write_text(
        json.dumps({"tts_model_size": "1b7", "tts_model_type": "custom_voice"})
    )
    (tmp_path / "installed.json").write_text(json.dumps({"revision": "unverified"}))
    with pytest.raises(ValueError, match="模型版本"):
        QwenMLXProvider(tmp_path)


@pytest.mark.parametrize("data", [[], [float("nan")], [float("inf")]])
def test_invalid_waveform_rejected(data):
    with pytest.raises(ValueError):
        wav_result(data, 24000)


def test_preview_and_reply_use_same_provider_and_preserve_settings(system, monkeypatch):
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    client, _, _, rh, _, ph = system
    sample = wav_result(np.zeros(2400), 24000).wav
    channel = client.app.state.tts_worker
    monkeypatch.setattr(
        channel,
        "run",
        AsyncMock(return_value={"audio": base64.b64encode(sample).decode()}),
    )
    payload = {
        "text": "小兔子回家了。",
        "voice": {
            "zh": "Serena",
            "story": "Uncle_Fu",
            "storyStyle": "soothing",
            "instruction": "轻声说",
        },
        "story": True,
    }
    for route, headers in [("preview", ph), ("reply", rh)]:
        response = client.post(f"/v1/speech/{route}", headers=headers, json=payload)
        assert response.status_code == 200 and response.content == sample
        task = channel.run.call_args.args[0]
        assert task["story"] and task["voice"]["instruction"] == "轻声说"
        assert task["voice"]["storyStyle"] == "soothing"
    assert channel.run.await_count == 2
    assert (
        client.post("/v1/speech/preview", headers=rh, json=payload).status_code == 403
    )
    payload["voice"]["instruction"] = "a" * 201
    assert client.post("/v1/speech/reply", headers=rh, json=payload).status_code == 422


def test_unknown_voice_fails_before_inference(system, monkeypatch):
    client, _, _, rh, _, _ = system
    run = AsyncMock()
    monkeypatch.setattr(client.app.state.tts_worker, "run", run)
    response = client.post(
        "/v1/speech/reply",
        headers=rh,
        json={"text": "你好", "voice": {"zh": "missing"}},
    )
    assert response.status_code == 422
    run.assert_not_called()


def test_isolated_tts_worker_uses_offline_environment(monkeypatch):
    from robot_service.worker_channel import SpeechWorker

    async def exercise():
        process = SimpleNamespace(returncode=None)
        process.stdin = SimpleNamespace(write=lambda data: None, drain=AsyncMock())
        process.stdout = SimpleNamespace(
            readline=AsyncMock(return_value=b'{"audio":"ok"}\n')
        )
        process.kill = lambda: setattr(process, "returncode", -9)
        process.wait = AsyncMock()
        spawn = AsyncMock(return_value=process)
        monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
        worker = SpeechWorker("robot_service.tts_worker", "/local/tts-python")
        try:
            assert await worker.run({"text": "你好"}, 1) == {"audio": "ok"}
            assert spawn.call_args.args[:3] == (
                "/local/tts-python",
                "-m",
                "robot_service.tts_worker",
            )
            assert spawn.call_args.kwargs["env"]["HF_HUB_OFFLINE"] == "1"
        finally:
            await worker.close()

    asyncio.run(exercise())


@pytest.mark.parametrize("legacy", [False, True])
def test_published_voice_stays_fixed_across_engine_switch(system, monkeypatch, legacy):
    from robot_service.tts import LEGACY_PROFILE, render_profile
    from test_library import make_book, publish

    client, store, _, rh, _, ph = system
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "kokoro")
    book = make_book(client, ph)
    rid = book["id"]
    revision = publish(client, ph, book).json()["revisionId"]
    manifest = client.get(f"/v1/resources/{rid}/manifest", headers=rh).json()
    assert manifest["ttsProfile"] == LEGACY_PROFILE
    sid = manifest["segments"][0]["id"]
    url = f"/v1/resources/{rid}/audio/{sid}?revisionId={revision}"
    if legacy:
        # 升级前的发布记录没有 ttsProfile，仍按原 Kokoro 解释。
        with store.transaction() as db:
            body = json.loads(
                db.execute(
                    "SELECT body FROM revisions WHERE id=?", (revision,)
                ).fetchone()[0]
            )
            body.pop("ttsProfile")
            db.execute(
                "UPDATE revisions SET body=? WHERE id=?", (json.dumps(body), revision)
            )
    run = AsyncMock(return_value={"audio": base64.b64encode(b"new-qwen-wave").decode()})
    monkeypatch.setattr(client.app.state.tts_worker, "run", run)
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    # 旧版未缓存段落不能突然改用新声音。
    assert client.get(url, headers=rh).status_code == 409
    run.assert_not_called()
    path = store.root / "audio" / revision / f"{sid}.wav"
    store.write_content(path, b"old-kokoro-wave")
    assert client.get(url, headers=rh).content == b"old-kokoro-wave"
    run.assert_not_called()
    # 显式重发产生新 revision；新旧缓存互不覆盖。
    new_revision = publish(client, ph, book).json()["revisionId"]
    assert new_revision != revision
    current = client.get(f"/v1/resources/{rid}/manifest", headers=rh).json()
    assert current["ttsProfile"] == render_profile()
    assert (
        client.get(
            f"/v1/resources/{rid}/audio/{sid}?revisionId={new_revision}", headers=rh
        ).content
        == b"new-qwen-wave"
    )
    assert path.read_bytes() == b"old-kokoro-wave"
    run.assert_awaited_once()


def test_changed_model_revision_cannot_fill_previous_cache(system, monkeypatch):
    import robot_service.tts as tts
    from test_library import make_book, publish

    monkeypatch.setenv("ROBOT_TTS_BACKEND", "qwen3-mlx")
    client, _, _, rh, _, ph = system
    book = make_book(client, ph)
    publish(client, ph, book)
    manifest = client.get(f"/v1/resources/{book['id']}/manifest", headers=rh).json()
    monkeypatch.setattr(tts, "QWEN_REVISION", "different-model-commit")
    response = client.get(
        f"/v1/resources/{book['id']}/audio/{manifest['segments'][0]['id']}?revisionId={manifest['revisionId']}",
        headers=rh,
    )
    assert response.status_code == 409

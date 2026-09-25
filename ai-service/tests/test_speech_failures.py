import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from robot_service.worker_channel import SpeechWorker, UnstableSpeechError


def test_rejected_candidate_keeps_model_and_next_protocol_response(monkeypatch):
    async def exercise():
        process = SimpleNamespace(returncode=None)
        process.stdin = SimpleNamespace(write=lambda data: None, drain=AsyncMock())
        process.stdout = SimpleNamespace(
            readline=AsyncMock(
                side_effect=[
                    b'{"error":"SynthesisRetryableError","code":"tts_unstable_pace","retryable":true}\n',
                    b'{"audio":"next-candidate"}\n',
                ]
            )
        )
        process.kill = lambda: setattr(process, "returncode", -9)
        process.wait = AsyncMock()
        spawn = AsyncMock(return_value=process)
        monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
        worker = SpeechWorker()
        try:
            with pytest.raises(UnstableSpeechError) as error:
                await worker.run({"kind": "tts"}, 1)
            assert error.value.retryable and error.value.code == "tts_unstable_pace"
            assert process.returncode is None
            assert await worker.run({"kind": "tts", "variant": 1}, 1) == {
                "audio": "next-candidate"
            }
            spawn.assert_awaited_once()
        finally:
            await worker.close()

    asyncio.run(exercise())


def test_pace_error_is_explained_and_does_not_automatically_repeat_same_seed(
    system, monkeypatch
):
    from test_library import make_book, publish

    c, _, _, rh, _, ph = system
    run = AsyncMock(side_effect=UnstableSpeechError())
    monkeypatch.setattr(c.app.state.tts_worker, "run", run)
    reply = c.post(
        "/v1/speech/reply", headers=rh, json={"text": "小兔子推开门，外面下雪了。"}
    )
    assert reply.status_code == 503
    assert reply.headers["X-Speech-Error"] == "tts_unstable_pace"
    assert "重新生成" in reply.json()["detail"]
    book = make_book(c, ph)
    publish(c, ph, book)
    manager = c.app.state.audio_preparation
    assert c.portal.call(manager.run_once)
    status = c.get(f"/v1/resources/{book['id']}/audio-preparation", headers=ph).json()
    assert status["state"] == "failed"
    before = run.await_count
    assert not c.portal.call(manager.run_once)
    assert run.await_count == before

import asyncio
import base64
import io
import json
import time
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException
from PIL import Image
from robot_service.intelligence import worker
from robot_service.priority_slot import PrioritySlot


def test_media_intent_respects_english_words_and_negative_commands():
    from robot_service.intelligence import route

    assert route("I am already ready.") == "chat"
    assert route("Please read the red cat book.") == "media"
    assert route("不要播放儿歌") == "stop"
    assert route("不想听故事了") == "stop"


def test_original_story_is_opt_in_labeled_and_never_published(system, monkeypatch):
    import httpx
    import robot_service.intelligence as module

    c, store, robot, rh, _, _ = system
    calls = []

    class LocalClient:
        def __init__(self, *args, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, *args, **kwargs):
            calls.append(kwargs["json"])
            return httpx.Response(
                200,
                json={
                    "message": {
                        "content": "小猫找到一片叶子。它把叶子送给小兔。还要再听吗？"
                    }
                },
                request=httpx.Request("POST", "http://127.0.0.1:11435/api/chat"),
            )

    monkeypatch.setattr(module.httpx, "AsyncClient", LocalClient)
    payload = {"sessionId": "original-story-0001", "text": "编一个原创故事"}
    disabled = c.post("/v1/turns", headers=rh, json=payload)
    assert disabled.status_code == 200 and "开启原创" in disabled.json()["text"]
    assert not calls
    config = json.loads(
        store.one("SELECT body FROM configs WHERE robot_id=?", (robot["deviceId"],))[
            "body"
        ]
    )
    config["originalStories"] = True
    with store.transaction() as db:
        db.execute(
            "UPDATE configs SET body=? WHERE robot_id=?",
            (json.dumps(config), robot["deviceId"]),
        )
    result = c.post("/v1/turns", headers=rh, json=payload).json()
    assert result["text"].startswith("这是我编的小故事。")
    assert "再听吗" not in result["text"] and len(calls) == 1
    assert not store.read("SELECT * FROM resources")
    dangerous = c.post(
        "/v1/turns",
        headers=rh,
        json={"sessionId": payload["sessionId"], "text": "这个药能吃吗"},
    ).json()
    assert "爸爸妈妈" in dangerous["text"] and len(calls) == 1


def test_mlx_silence_stops_before_model_loading(monkeypatch, tmp_path):
    from robot_service.model_worker import execute

    audio = io.BytesIO()
    with wave.open(audio, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(bytes(64000))
    monkeypatch.setenv("ROBOT_ASR_BACKEND", "mlx")
    # 模型目录故意为空；静音必须在加载模型之前直接结束。
    assert execute(
        {
            "kind": "asr",
            "root": str(tmp_path),
            "audio": base64.b64encode(audio.getvalue()).decode(),
        }
    ) == {"text": ""}


def test_oversized_image_rejected_before_decode(system, monkeypatch):
    from unittest.mock import Mock

    from robot_service.extract import ocr
    from robot_service.imports import validate_file

    picture = SimpleNamespace(width=6000, height=5000, load=Mock(), verify=Mock())
    monkeypatch.setattr(Image, "open", lambda *args, **kwargs: picture)
    with pytest.raises(ValueError):
        validate_file(b"header", ".png")
    with pytest.raises(ValueError):
        ocr(b"header")
    c, _, _, rh, _, _ = system
    response = c.post(
        "/v1/turns",
        headers=rh,
        json={
            "sessionId": "oversized-image-1",
            "text": "看看这个",
            "image": base64.b64encode(b"header").decode(),
            "imageAgeMs": 0,
        },
    )
    assert response.status_code == 422
    picture.load.assert_not_called()
    picture.verify.assert_not_called()


def test_camera_guards_and_explicit_routes(system, monkeypatch):
    c, store, robot, rh, _, ph = system
    image = io.BytesIO()
    Image.new("RGB", (8, 8), "red").save(image, "PNG")
    payload = {
        "sessionId": "camera-guard-test",
        "text": "看看这个",
        "image": base64.b64encode(image.getvalue()).decode(),
        "imageAgeMs": 2001,
    }
    assert c.post("/v1/turns", headers=rh, json=payload).status_code == 409
    assert (
        c.post(
            "/v1/books/recognize?imageAgeMs=2001",
            headers=rh,
            files={"file": ("cover.png", image.getvalue())},
        ).status_code
        == 409
    )
    config = json.loads(
        store.one("SELECT body FROM configs WHERE robot_id=?", (robot["deviceId"],))[
            "body"
        ]
    )
    config["cameraAllowed"] = False
    with store.transaction() as db:
        db.execute(
            "UPDATE configs SET body=? WHERE robot_id=?",
            (json.dumps(config), robot["deviceId"]),
        )
    payload["imageAgeMs"] = 0
    assert c.post("/v1/turns", headers=rh, json=payload).status_code == 409
    assert (
        c.post(
            "/v1/books/recognize",
            headers=rh,
            files={"file": ("cover.png", image.getvalue())},
        ).status_code
        == 409
    )
    for text, action in [
        ("休息吧", "rest"),
        ("不要拍了", "camera_off"),
        ("别看了", "camera_off"),
        ("下一章", "next_chapter"),
    ]:
        result = c.post(
            "/v1/turns",
            headers=rh,
            json={"sessionId": "explicit-actions-1", "text": text},
        )
        assert result.json()["action"] == action
    result = c.post(
        "/v1/turns",
        headers=rh,
        json={"sessionId": "explicit-actions-1", "text": "记住我喜欢红色"},
    )
    assert result.status_code == 200
    assert c.get("/v1/memories", headers=ph).json()[0]["state"] == "pending"


@pytest.mark.parametrize("kind", ["asr", "tts"])
def test_worker_timeout_kills_and_releases_slot(monkeypatch, kind):
    from robot_service.worker_channel import SpeechWorker

    async def exercise():
        process = SimpleNamespace(returncode=None)

        async def readline():
            await asyncio.sleep(5)

        process.stdin = SimpleNamespace(write=lambda data: None, drain=AsyncMock())
        process.stdout = SimpleNamespace(readline=readline)
        process.kill = lambda: setattr(process, "returncode", -9)
        process.wait = AsyncMock()
        monkeypatch.setattr(
            asyncio, "create_subprocess_exec", AsyncMock(return_value=process)
        )
        slots = PrioritySlot() if kind == "tts" else asyncio.Semaphore(1)
        channel = SpeechWorker()
        request = SimpleNamespace(
            app=SimpleNamespace(
                state=SimpleNamespace(
                    model_slots=slots,
                    speech_worker=channel,
                    tts_slots=slots,
                    tts_worker=channel,
                    store=SimpleNamespace(root=Path("/tmp/unused-test")),
                )
            )
        )
        started = time.monotonic()
        with pytest.raises(HTTPException) as error:
            await worker(request, {"kind": kind}, timeout=0.01)
        assert error.value.status_code == 504 and process.returncode == -9
        assert time.monotonic() - started < 1 and not slots.locked()
        assert channel.process is None
        process.wait.assert_awaited_once()

    asyncio.run(exercise())


def test_memory_edit_expiry_and_playlist_publication_gate(system):
    c, store, robot, rh, _, ph = system
    mid = c.post("/v1/memories", headers=ph, json={"content": "喜欢猫"}).json()["id"]
    expires = time.time() + 86400
    assert (
        c.put(
            "/v1/memories/" + mid,
            headers=ph,
            json={"state": "approved", "content": "喜欢小猫", "expires": expires},
        ).status_code
        == 200
    )
    memory = c.get("/v1/memories", headers=ph).json()[0]
    assert (
        memory["body"]["content"] == "喜欢小猫" and memory["body"]["expires"] == expires
    )
    from test_library import make_book, publish

    book = make_book(c, ph)
    pid = c.post(
        "/v1/playlists",
        headers=ph,
        json={"name": "睡前小书", "resources": [book["id"]]},
    ).json()["id"]
    c.post("/v1/heartbeat", headers=rh, json={"status": "standby", "appliedVersion": 0})
    control = {
        "requestId": "playlist-test-0001",
        "action": "playlist",
        "playlistId": pid,
    }
    url = "/v1/robots/" + robot["deviceId"] + "/control"
    assert c.post(url, headers=ph, json=control).status_code == 409
    publish(c, ph, book)
    assert c.post(url, headers=ph, json=control).status_code == 200
    assert c.post(url, headers=rh, json=control).status_code == 403


def test_deleted_memory_cancels_inflight_answer(system, monkeypatch):
    import httpx
    import robot_service.intelligence as module

    c, _, _, rh, _, _ = system
    app = c.app

    class LocalClient:
        def __init__(self, *args, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, *args, **kwargs):
            app.state.memory_epoch = "changed-during-inference"
            app.state.sessions.clear()
            return httpx.Response(
                200,
                json={"message": {"content": "旧的偏好内容。"}},
                request=httpx.Request("POST", "http://127.0.0.1:11435/api/chat"),
            )

    monkeypatch.setattr(module.httpx, "AsyncClient", LocalClient)
    response = c.post(
        "/v1/turns",
        headers=rh,
        json={"sessionId": "memory-race-00001", "text": "聊聊我们喜欢的颜色"},
    )
    assert response.status_code == 409
    assert not app.state.sessions


def test_background_tts_does_not_block_child_asr(tmp_path):
    async def exercise():
        entered = asyncio.Event()
        release = asyncio.Event()

        async def slow_library(task, timeout):
            assert task["threads"] == 1
            entered.set()
            await release.wait()
            return {"audio": "library"}

        realtime = AsyncMock(return_value={"text": "hello"})
        state = SimpleNamespace(
            store=SimpleNamespace(root=tmp_path),
            model_slots=asyncio.Semaphore(1),
            library_slots=asyncio.Semaphore(1),
            tts_slots=PrioritySlot(),
            speech_worker=SimpleNamespace(run=realtime),
            library_worker=SimpleNamespace(run=slow_library),
            tts_worker=SimpleNamespace(run=slow_library),
        )
        request = SimpleNamespace(app=SimpleNamespace(state=state))
        background = asyncio.create_task(
            worker(request, {"kind": "tts"}, background=True)
        )
        await entered.wait()
        try:
            result = await asyncio.wait_for(worker(request, {"kind": "asr"}), 0.2)
            assert result == {"text": "hello"}
            assert state.tts_slots.locked()
            assert realtime.call_args.args[0]["threads"] == 2
        finally:
            release.set()
            await background
        assert not state.model_slots.locked() and not state.library_slots.locked()

    asyncio.run(exercise())


def test_short_games_stop_after_three_rounds_and_respect_safety(system, monkeypatch):
    import robot_service.games as games

    c, _, robot, rh, _, _ = system

    def say(text, session="short-game-session"):
        return c.post(
            "/v1/turns", headers=rh, json={"sessionId": session, "text": text}
        ).json()

    assert "第一题" in say("玩猜谜小游戏")["text"]
    assert "第二题" in say("小猫")["text"]
    assert "第三题" in say("不知道")["text"]
    assert "结束" in say("dog")["text"]
    assert not c.app.state.games
    assert "One, two" in say("数数游戏")["text"]
    assert say("停止")["action"] == "stop"
    assert not c.app.state.games
    assert "red" in say("颜色游戏")["text"]
    assert "爸爸妈妈" in say("这个药能吃吗")["text"]
    # 游戏时间使用单调时钟，到期不让孩子无限继续。
    now = games.time.monotonic()
    monkeypatch.setattr(games.time, "monotonic", lambda: now + 121)
    assert "休息" in games.respond(
        c.app.state.games, (robot["deviceId"], "short-game-session"), "red", 5
    )
    assert not c.app.state.games


def test_asr_accepts_full_thirty_seconds_and_rejects_truncation(system):
    c, _, _, rh, _, _ = system
    c.app.state.speech_worker.run = AsyncMock(return_value={"text": "完整发言"})

    def wav(seconds):
        output = io.BytesIO()
        with wave.open(output, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(bytes(int(seconds * 16000) * 2))
        return output.getvalue()

    full = wav(30)
    assert (
        c.post(
            "/v1/speech/recognize",
            headers=rh,
            files={"file": ("full.wav", full, "audio/wav")},
        ).status_code
        == 200
    )
    for invalid in (wav(30.1), full[:-100]):
        assert (
            c.post(
                "/v1/speech/recognize",
                headers=rh,
                files={"file": ("bad.wav", invalid, "audio/wav")},
            ).status_code
            == 422
        )
    assert c.app.state.speech_worker.run.await_count == 1

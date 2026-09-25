import asyncio
import base64
import io
import time
import wave
from unittest.mock import AsyncMock

import pytest
from fastapi import Request
from robot_service.store import StorageCapacityError
from test_library import make_book, publish


def sample_audio():
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(bytes(3200))
    return output.getvalue()


def setup_book(system, monkeypatch, *, excerpt=False):
    client, store, _, rh, _, ph = system
    audio = sample_audio()
    run = AsyncMock(return_value={"audio": base64.b64encode(audio).decode()})
    monkeypatch.setattr(client.app.state.tts_worker, "run", run)
    book = make_book(client, ph)
    draft = book["draft"]
    draft["pages"].append({"id": "page-two", "text": "小兔说晚安。", "reviewed": True})
    if excerpt:
        draft["complete"] = False
        draft["excerpt"] = "第一页到第二页"
    book = client.put(
        f"/v1/resources/{book['id']}",
        headers=ph,
        json={"expectedVersion": book["draft_version"], "draft": draft},
    ).json()
    # 同时兼容仍要求显式试听确认的已提交服务版本。
    book["draft"]["auditioned"] = True
    book = client.put(
        f"/v1/resources/{book['id']}",
        headers=ph,
        json={"expectedVersion": book["draft_version"], "draft": book["draft"]},
    ).json()
    rev = publish(client, ph, book).json()["revisionId"]
    return client, store, rh, ph, book, rev, run, audio


def drive(client):
    return client.portal.call(client.app.state.audio_preparation.run_once)


def status(client, ph, rid):
    return client.get(f"/v1/resources/{rid}/audio-preparation", headers=ph).json()


def test_publish_prepares_all_audio_without_playback_and_reuses_cache(
    system, monkeypatch
):
    c, store, rh, ph, book, rev, run, audio = setup_book(
        system, monkeypatch, excerpt=True
    )
    rid = book["id"]
    assert status(c, ph, rid)["state"] == "queued"
    for _ in range(3):
        assert drive(c)
    assert status(c, ph, rid) == {
        "revisionId": rev,
        "state": "ready",
        "completed": 3,
        "total": 3,
        "error": "",
    }
    assert not drive(c)
    manifest = c.get(f"/v1/resources/{rid}/manifest", headers=rh).json()
    for sid in ["scope-notice", *[s["id"] for s in manifest["segments"]]]:
        assert (
            c.get(
                f"/v1/resources/{rid}/audio/{sid}?revisionId={rev}", headers=rh
            ).content
            == audio
        )
    assert run.await_count == 3
    assert len(list((store.root / "audio" / rev).glob("*.wav"))) == 3


def test_restart_backfills_old_books_and_skips_completed_segments(system, monkeypatch):
    c, store, _, ph, book, rev, run, _ = setup_book(system, monkeypatch)
    drive(c)
    with store.transaction() as db:
        db.execute("DELETE FROM audio_jobs")  # 模拟升级前只有惰性缓存的书。
    c.app.state.audio_preparation.recover()
    assert status(c, ph, book["id"])["completed"] == 1
    with store.transaction() as db:
        db.execute("UPDATE audio_jobs SET state='processing'")
    c.app.state.audio_preparation.recover()
    assert status(c, ph, book["id"])["state"] == "queued"
    drive(c)
    assert run.await_count == 2
    assert status(c, ph, book["id"])["state"] == "ready"
    # 声称 ready 但文件丢失的版本会被修复。
    next((store.root / "audio" / rev).glob("*.wav")).unlink()
    c.app.state.audio_preparation.recover()
    drive(c)
    assert run.await_count == 3
    assert status(c, ph, book["id"])["state"] == "ready"


def test_concurrent_background_and_listeners_share_one_synthesis_and_cancel_independently(
    system, monkeypatch
):
    c, _, _, _, book, rev, run, audio = setup_book(system, monkeypatch)

    async def exercise():
        started, release = asyncio.Event(), asyncio.Event()

        async def synth(*args):
            started.set()
            await release.wait()
            return {"audio": base64.b64encode(audio).decode()}

        run.side_effect = synth
        manager = c.app.state.audio_preparation
        request = Request({"type": "http", "app": c.app})
        bg = asyncio.create_task(manager.run_once())
        await started.wait()
        sid = next(iter(manager.inflight))[1]
        first = asyncio.create_task(manager.ensure(request, book["id"], rev, sid))
        second = asyncio.create_task(manager.ensure(request, book["id"], rev, sid))
        await asyncio.sleep(0)
        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        release.set()
        assert await second == audio
        await bg
        assert run.await_count == 1
        assert not manager.inflight

    c.portal.call(exercise)


@pytest.mark.parametrize("operation", ["unlist", "delete"])
def test_withdrawal_during_synthesis_cannot_recreate_audio(
    system, monkeypatch, operation
):
    c, store, _, ph, book, rev, run, audio = setup_book(system, monkeypatch)
    import threading

    started, release = threading.Event(), threading.Event()

    async def synth(*args):
        started.set()
        await asyncio.to_thread(release.wait, 3)
        return {"audio": base64.b64encode(audio).decode()}

    run.side_effect = synth
    future = c.portal.start_task_soon(c.app.state.audio_preparation.run_once)
    assert started.wait(2)
    rid = book["id"]
    if operation == "unlist":
        assert c.post(f"/v1/resources/{rid}/unlist", headers=ph).status_code == 200
    else:
        assert c.delete(f"/v1/resources/{rid}", headers=ph).status_code == 200
    release.set()
    future.result(3)
    assert not (store.root / "audio" / rev).exists()
    assert not drive(c)


def test_transient_failures_back_off_and_manual_retry_is_version_and_role_guarded(
    system, monkeypatch
):
    c, store, rh, ph, book, rev, run, _ = setup_book(system, monkeypatch)
    rid = book["id"]
    run.side_effect = RuntimeError("model unavailable")
    for attempt in range(1, 6):
        drive(c)
        row = store.one("SELECT * FROM audio_jobs WHERE revision_id=?", (rev,))
        assert row["attempts"] == attempt
        assert row["retry_at"] > time.time()
        assert not drive(c)
        with store.transaction() as db:
            db.execute("UPDATE audio_jobs SET retry_at=0")
    assert status(c, ph, rid)["state"] == "failed"
    retry = f"/v1/resources/{rid}/audio-preparation/retry?revisionId={rev}"
    assert c.post(retry, headers=rh).status_code == 403
    assert c.post(retry, headers=ph).json()["state"] == "queued"
    assert store.one("SELECT attempts FROM audio_jobs")["attempts"] == 0
    publish(c, ph, book)
    assert c.post(retry, headers=ph).status_code == 409
    assert (
        store.one("SELECT state FROM audio_jobs WHERE revision_id=?", (rev,))["state"]
        == "cancelled"
    )


def test_busy_does_not_exhaust_retries_or_run_multiple_models(system, monkeypatch):
    c, store, _, ph, book, _, run, _ = setup_book(system, monkeypatch)
    monkeypatch.setattr(
        c.app.state.tts_slots, "acquire", AsyncMock(side_effect=TimeoutError)
    )
    drive(c)
    row = store.one("SELECT * FROM audio_jobs")
    assert row["attempts"] == 0 and row["retry_at"] > time.time()
    assert status(c, ph, book["id"])["state"] == "queued"
    run.assert_not_awaited()


def test_capacity_failure_keeps_existing_audio_and_can_retry(system, monkeypatch):
    c, store, _, ph, book, _, run, _ = setup_book(system, monkeypatch)
    drive(c)
    monkeypatch.setattr(
        store,
        "write_content",
        lambda *a: (_ for _ in ()).throw(StorageCapacityError("磁盘空间不足")),
    )
    drive(c)
    result = status(c, ph, book["id"])
    assert result["state"] == "failed" and result["completed"] == 1
    assert "磁盘" in result["error"]


def test_changed_engine_never_mixes_audio_in_same_revision(system, monkeypatch):
    c, _, _, ph, book, _, run, _ = setup_book(system, monkeypatch)
    monkeypatch.setenv("ROBOT_TTS_BACKEND", "kokoro")
    drive(c)
    assert status(c, ph, book["id"])["state"] == "failed"
    assert "语音引擎" in status(c, ph, book["id"])["error"]
    run.assert_not_awaited()


def test_task_is_durable_and_publish_receipt_is_idempotent(system, monkeypatch):
    c, store, _, ph, book, _, _, _ = setup_book(system, monkeypatch)
    body = {
        "expectedVersion": book["draft_version"],
        "requestId": "durable-publish-request",
    }
    one = c.post(f"/v1/resources/{book['id']}/publish", headers=ph, json=body)
    two = c.post(f"/v1/resources/{book['id']}/publish", headers=ph, json=body)
    assert one.json() == two.json()
    rows = store.read("SELECT * FROM audio_jobs WHERE state='queued'")
    assert len(rows) == 1 and rows[0]["revision_id"] == one.json()["revisionId"]


def test_scheduler_orders_reply_then_playback_then_bulk():
    from robot_service.priority_slot import PrioritySlot

    async def exercise():
        slot = PrioritySlot()
        await slot.acquire()
        order = []

        async def waiter(label, **kwargs):
            await slot.acquire(**kwargs)
            order.append(label)
            slot.release()

        jobs = []
        for name, args in [
            ("bulk", {"bulk": True}),
            ("playback", {"background": True}),
            ("reply", {}),
        ]:
            jobs.append(asyncio.create_task(waiter(name, **args)))
            await asyncio.sleep(0)
        slot.release()
        await asyncio.gather(*jobs)
        assert order == ["reply", "playback", "bulk"]

    asyncio.run(exercise())

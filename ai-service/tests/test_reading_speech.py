import base64
import io
import wave
from unittest.mock import AsyncMock

from robot_service.reading_speech import segments, semantic_chunks
from test_library import make_book, publish


def draft(*texts, mode="continuous"):
    return {
        "language": "zh",
        "readingMode": mode,
        "pages": [
            {"id": f"p{i}", "text": text, "reviewed": True}
            for i, text in enumerate(texts)
        ],
    }


def wav():
    out = io.BytesIO()
    with wave.open(out, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(24000)
        f.writeframes(bytes(4800))
    return out.getvalue()


def test_short_pages_group_without_losing_original_pages():
    data = draft("小兔推开门，", "看见白茫茫的一片。", "下雪啦！")
    result = segments(data)
    assert len(result) == 3
    assert len({s["groupId"] for s in result}) == 1
    assert [s["pageId"] for s in result] == ["p0", "p1", "p2"]
    assert [s["text"] for s in result] == [p["text"] for p in data["pages"]]
    data["readingMode"] = "follow_pages"
    assert len({s["groupId"] for s in segments(data)}) == 3


def test_scene_chapter_skipped_and_long_pages_are_group_boundaries():
    for alteration in ({"breakBefore": True}, {"chapter": "第二章"}, {"skip": True}):
        data = draft("第一句话。", "第二句话。", "第三句话。")
        data["pages"][1].update(alteration)
        result = segments(data)
        assert result[0]["groupId"] != result[1]["groupId"]
    data = draft("很长的一句话。" * 70, "下一页。")
    result = segments(data)
    assert all(len(s["text"]) <= 180 for s in result)
    assert result[-1]["groupId"] != result[-2]["groupId"]
    assert "".join(s["text"] for s in result) == "".join(
        p["text"] for p in data["pages"]
    )


def test_semantic_splitting_preserves_decimal_abbreviation_and_closing_quote():
    text = "Dr. Fox paid 3.14 dollars. “Then we went home!” " * 12
    chunks = semantic_chunks(text, 100)
    assert "".join(chunks) == text
    assert all(len(c) <= 100 for c in chunks)
    assert not any(c.endswith(("Dr.", "3.")) for c in chunks)
    assert not any(c.startswith("”") for c in chunks)


def test_bilingual_pages_keep_one_language_profile():
    data = draft("苹果。", "Apple.")
    data["language"] = "bilingual"
    assert {s["language"] for s in segments(data)} == {"zh"}


def setup_preview(system, monkeypatch):
    c, store, _, rh, _, ph = system
    run = AsyncMock(return_value={"audio": base64.b64encode(wav()).decode()})
    monkeypatch.setattr(c.app.state.tts_worker, "run", run)
    book = make_book(c, ph)
    book["draft"]["pages"] = draft("小兔推开门，", "看见白茫茫的一片。", "下一章。")[
        "pages"
    ]
    book["draft"]["pages"][-1]["chapter"] = "新章节"
    book["draft"]["readingMode"] = "continuous"
    book = c.put(
        f"/v1/resources/{book['id']}",
        headers=ph,
        json={"expectedVersion": book["draft_version"], "draft": book["draft"]},
    ).json()
    return c, store, rh, ph, book, run


def plan(c, ph, book):
    r = c.get(
        f"/v1/resources/{book['id']}/speech-plan?expectedVersion={book['draft_version']}",
        headers=ph,
    )
    assert r.status_code == 200, r.text
    return r.json()


def preview(c, ph, book, sid):
    return c.get(
        f"/v1/resources/{book['id']}/speech-preview/{sid}?expectedVersion={book['draft_version']}",
        headers=ph,
    )


def test_preview_publish_reuses_exact_audio_and_keeps_page_navigation(
    system, monkeypatch
):
    c, store, rh, ph, book, run = setup_preview(system, monkeypatch)
    parts = plan(c, ph, book)["segments"]
    first = preview(c, ph, book, parts[0]["id"])
    assert first.status_code == 200, first.text
    assert first.headers["X-Speech-Grouping"] == "fallback"  # fixture has no model
    assert run.await_count == 2
    second = preview(c, ph, book, parts[1]["id"])
    assert run.await_count == 2
    revision = publish(c, ph, book).json()["revisionId"]
    for seg, data in zip(parts, (first.content, second.content)):
        r = c.get(
            f"/v1/resources/{book['id']}/audio/{seg['id']}?revisionId={revision}",
            headers=rh,
        )
        assert r.status_code == 200 and r.content == data
    assert run.await_count == 2
    body = c.get(f"/v1/resources/{book['id']}/manifest", headers=rh).json()
    assert [s["pageId"] for s in body["segments"]] == ["p0", "p1", "p2"]
    assert (
        store.root / "audio" / revision / (parts[0]["id"] + ".alignment.json")
    ).exists()
    # Lost/corrupted group member is restored from the exact audition cache.
    (store.root / "audio" / revision / (parts[1]["id"] + ".wav")).write_bytes(
        b"partial"
    )
    restored = c.get(
        f"/v1/resources/{book['id']}/audio/{parts[1]['id']}?revisionId={revision}",
        headers=rh,
    )
    assert restored.content == second.content and run.await_count == 2


def test_regeneration_only_invalidates_its_group_and_stale_preview_is_rejected(
    system, monkeypatch
):
    c, _, rh, ph, book, run = setup_preview(system, monkeypatch)
    parts = plan(c, ph, book)["segments"]
    for seg in parts:
        assert preview(c, ph, book, seg["id"]).status_code == 200
    assert run.await_count == 3
    old = book
    book["draft"]["pages"][0]["synthesisVariant"] = 1
    book = c.put(
        f"/v1/resources/{book['id']}",
        headers=ph,
        json={"expectedVersion": book["draft_version"], "draft": book["draft"]},
    ).json()
    assert preview(c, ph, old, parts[0]["id"]).status_code == 409
    assert preview(c, rh, book, parts[0]["id"]).status_code == 403
    for seg in plan(c, ph, book)["segments"]:
        assert preview(c, ph, book, seg["id"]).status_code == 200
    assert run.await_count == 5
    assert run.call_args_list[-2].args[0]["variant"] == 1


def test_shared_voice_frozen_until_saved_and_explicit_custom_is_preserved(system):
    import json

    c, store, robot, rh, _, ph = system
    book = make_book(c, ph)
    assert book["draft"]["voiceSource"] == "shared"
    with store.transaction() as db:
        row = db.execute(
            "SELECT body FROM configs WHERE robot_id=?", (robot["deviceId"],)
        ).fetchone()
        config = json.loads(row[0])
        config["voice"]["zh"] = "Uncle_Fu"
        db.execute(
            "UPDATE configs SET body=? WHERE robot_id=?",
            (json.dumps(config), robot["deviceId"]),
        )
    assert publish(c, ph, book).status_code == 409
    updated = c.put(
        f"/v1/resources/{book['id']}",
        headers=ph,
        json={"expectedVersion": book["draft_version"], "draft": book["draft"]},
    ).json()
    assert updated["draft"]["voice"]["zh"] == "Uncle_Fu"
    assert publish(c, ph, updated).status_code == 200
    updated["draft"]["voiceSource"] = "custom"
    updated["draft"]["voice"]["zh"] = "Serena"
    custom = c.put(
        f"/v1/resources/{book['id']}",
        headers=ph,
        json={"expectedVersion": updated["draft_version"], "draft": updated["draft"]},
    ).json()
    assert custom["draft"]["voice"]["zh"] == "Serena"


def test_actual_group_pipeline_uses_one_synthesis_and_bit_exact_page_slices(
    system, monkeypatch
):
    import numpy as np
    from robot_service.tts import wav_result

    c, store, _, rh, _, ph = system
    model = store.root / "models/faster-whisper-small/model.bin"
    model.parent.mkdir(parents=True)
    model.touch()
    samples = np.zeros(24000)
    samples[2400:7200] = 0.1 * np.sin(np.arange(4800) * 2 * np.pi * 440 / 24000)
    samples[14400:19200] = 0.1 * np.sin(np.arange(4800) * 2 * np.pi * 440 / 24000)
    combined = wav_result(samples, 24000).wav
    speech = AsyncMock(return_value=combined)
    monkeypatch.setattr("robot_service.intelligence.speech", speech)
    align = AsyncMock(
        return_value={
            "words": [
                {"word": "小兔", "start": 0.1, "end": 0.3, "probability": 0.99},
                {"word": "门", "start": 0.6, "end": 0.8, "probability": 0.99},
            ]
        }
    )
    monkeypatch.setattr(c.app.state.library_worker, "run", align)
    book = c.post(
        "/v1/resources",
        headers=ph,
        json={
            "kind": "book",
            "draft": {
                "title": "合成分组测试",
                "complete": True,
                **draft("小兔。", "门。"),
            },
        },
    ).json()
    parts = plan(c, ph, book)["segments"]
    replies = [preview(c, ph, book, s["id"]) for s in parts]
    assert all(
        r.status_code == 200 and r.headers["X-Speech-Grouping"] == "aligned"
        for r in replies
    )
    speech.assert_awaited_once()
    assert speech.call_args.args[1] == "小兔。门。"

    def pcm(data):
        with wave.open(io.BytesIO(data)) as w:
            return w.readframes(w.getnframes())

    assert b"".join(pcm(r.content) for r in replies) == pcm(combined)
    revision = publish(c, ph, book).json()["revisionId"]
    for seg, r in zip(parts, replies, strict=True):
        audio = c.get(
            f"/v1/resources/{book['id']}/audio/{seg['id']}?revisionId={revision}",
            headers=rh,
        )
        assert audio.content == r.content
    speech.assert_awaited_once()


def test_follow_pages_pause_keeps_all_speech_samples():
    import numpy as np
    from robot_service.reading_audio import page_pause
    from robot_service.tts import wav_result

    original = wav_result(np.full(2400, 0.01), 24000).wav
    paused = page_pause(original)
    with wave.open(io.BytesIO(paused)) as w:
        assert w.getnframes() == 2400 + 10800
        raw = w.readframes(w.getnframes())
    with wave.open(io.BytesIO(original)) as w:
        assert raw.startswith(w.readframes(w.getnframes()))
    assert page_pause(paused) == paused


def test_preview_does_not_resurrect_deleted_book_audio(system, monkeypatch):
    import threading

    c, store, _, ph, book, run = setup_preview(system, monkeypatch)
    entered, release = threading.Event(), threading.Event()

    async def synthesis(*args):
        import asyncio

        entered.set()
        await asyncio.to_thread(release.wait, 3)
        return {"audio": base64.b64encode(wav()).decode()}

    run.side_effect = synthesis
    sid = plan(c, ph, book)["segments"][0]["id"]
    responses = []
    t = threading.Thread(target=lambda: responses.append(preview(c, ph, book, sid)))
    t.start()
    try:
        assert entered.wait(2)
        assert c.delete(f"/v1/resources/{book['id']}", headers=ph).status_code == 200
    finally:
        release.set()
        t.join(5)
    assert not t.is_alive()
    assert responses[0].status_code == 404
    assert not (store.root / "audio" / ("previews-" + book["id"])).exists()


def test_changed_segmentation_profile_cannot_fill_old_missing_audio(
    system, monkeypatch
):
    import json

    c, store, rh, ph, book, run = setup_preview(system, monkeypatch)
    revision = publish(c, ph, book).json()["revisionId"]
    with store.transaction() as db:
        body = json.loads(
            db.execute("SELECT body FROM revisions WHERE id=?", (revision,)).fetchone()[
                0
            ]
        )
        body["segmentationProfile"] = "future-unsupported-rules"
        db.execute(
            "UPDATE revisions SET body=? WHERE id=?", (json.dumps(body), revision)
        )
    sid = body["segments"][0]["id"]
    r = c.get(
        f"/v1/resources/{book['id']}/audio/{sid}?revisionId={revision}", headers=rh
    )
    assert r.status_code == 409 and "分段版本" in r.json()["detail"]
    run.assert_not_awaited()

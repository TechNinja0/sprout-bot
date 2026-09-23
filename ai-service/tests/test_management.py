import io
import json
import time
import zipfile

import pytest
from robot_service.management import export_library, restore_library
from robot_service.store import Store, digest, dumps


def test_family_summary_aggregates_without_dialogue_text(system):
    from test_library import make_book, publish

    c, _, _, rh, _, ph = system
    book = make_book(c, ph)
    revision = publish(c, ph, book).json()["revisionId"]
    path = "/v1/resources/" + book["id"]
    segment = c.get(path + "/manifest", headers=rh).json()["segments"][0]["id"]
    c.put(
        path + "/progress",
        headers=rh,
        json={"revisionId": revision, "segmentId": segment, "offsetMs": 400, "seq": 1},
    )
    c.post("/v1/memories", headers=ph, json={"content": "喜欢小猫"})
    assert (
        c.post(
            "/v1/heartbeat",
            headers=rh,
            json={"serviceFailures": {"turn": 2, "network": 1}},
        ).status_code
        == 200
    )
    assert c.get("/v1/usage/summary", headers=rh).status_code == 403
    result = c.get("/v1/usage/summary", headers=ph).json()
    assert result["readCategories"] == [{"kind": "book", "resources": 1}]
    assert result["pendingMemories"] == 1 and result["serviceFailures"]["turn"] == 2
    assert "喜欢小猫" not in json.dumps(result, ensure_ascii=False)
    assert (
        c.post(
            "/v1/heartbeat",
            headers=rh,
            json={"serviceFailures": {"rawText": "不应该收集"}},
        ).status_code
        == 422
    )
    c.delete(path, headers=ph)
    assert c.get("/v1/usage/summary", headers=ph).json()["readCategories"] == []


def create(c, ph):
    return c.post(
        "/v1/resources",
        headers=ph,
        json={
            "kind": "book",
            "draft": {
                "title": "原创测试书",
                "complete": True,
                "auditioned": True,
                "pages": [{"id": "p1", "text": "Hello, little cat.", "reviewed": True}],
            },
        },
    ).json()["id"]


def test_import_review_gate_and_source_scope(system):
    c, store, _, rh, _, ph = system
    rid = create(c, ph)
    response = c.post(
        f"/v1/resources/{rid}/assets?purpose=pages&expectedVersion=1",
        headers=ph,
        files={"file": ("sample.txt", "第二页。\f第三页。".encode(), "text/plain")},
    )
    assert response.status_code == 200
    job = response.json()["jobId"]
    for _ in range(100):
        state = store.one("SELECT state FROM jobs WHERE id=?", (job,))["state"]
        if state not in ("queued", "processing"):
            break
        time.sleep(0.03)
    assert state == "needs_review"
    resource = c.get(f"/v1/resources/{rid}", headers=ph).json()
    assert resource["draft_version"] == 2
    assert len(resource["draft"]["pages"]) == 3
    assert not resource["draft"]["pages"][1]["reviewed"]
    assert c.get(
        "/v1/assets/" + response.json()["assetId"], headers=rh
    ).status_code in (403, 409)
    assert (
        c.post(
            f"/v1/resources/{rid}/publish",
            headers=ph,
            json={"expectedVersion": 2, "requestId": "import-publish-0001"},
        ).status_code
        == 422
    )


def test_restore_never_revives_deleted_and_no_credentials(system, tmp_path):
    c, store, _, rh, _, ph = system
    rid = create(c, ph)
    mid = c.post(
        "/v1/memories", headers=rh, json={"content": "喜欢猫", "source": "孩子明确说出"}
    ).json()["id"]
    c.put("/v1/memories/" + mid, headers=ph, json={"state": "approved"})
    backup = export_library(store)
    with zipfile.ZipFile(io.BytesIO(backup)) as z:
        d = json.loads(z.read("data.json"))
        assert "devices" not in d
    c.delete("/v1/resources/" + rid, headers=ph)
    c.post("/v1/memories/forget", headers=rh)
    assert restore_library(store, backup)["restoredDrafts"] == 0
    assert (
        store.one("SELECT state FROM memories WHERE id=?", (mid,))["state"] == "deleted"
    )
    clean = Store(tmp_path / "other")
    assert restore_library(clean, backup)["restoredDrafts"] == 1
    restored = clean.one("SELECT * FROM resources WHERE id=?", (rid,))
    assert restored["status"] == "draft" and restored["published_id"] is None
    draft = json.loads(restored["draft"])
    assert not draft["auditioned"] and not draft["pages"][0]["reviewed"]
    assert (
        clean.one("SELECT state FROM memories WHERE id=?", (mid,))["state"] == "pending"
    )


def test_restore_rejects_tampering_and_traversal(system):
    _, store, *_ = system
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        raw = b"danger"
        z.writestr("../escape", raw)
        z.writestr(
            "manifest.json", dumps({"version": 1, "hashes": {"../escape": digest(raw)}})
        )
    with pytest.raises(ValueError):
        restore_library(store, b.getvalue())


def test_memory_parent_approval_and_usage_order(system):
    c, store, _, rh, _, ph = system
    mid = c.post("/v1/memories", headers=rh, json={"content": "喜欢蓝色"}).json()["id"]
    assert (
        c.put("/v1/memories/" + mid, headers=rh, json={"state": "approved"}).status_code
        == 403
    )
    assert c.get("/v1/memories", headers=rh).status_code == 403
    assert (
        c.put("/v1/memories/" + mid, headers=ph, json={"state": "approved"}).status_code
        == 200
    )
    assert c.post(
        "/v1/usage", headers=rh, json={"day": "2026-09-22", "seconds": 100, "seq": 2}
    ).json()["accepted"]
    assert not c.post(
        "/v1/usage", headers=rh, json={"day": "2026-09-22", "seconds": 90, "seq": 3}
    ).json()["accepted"]
    assert c.get("/v1/usage", headers=ph).json()[0]["seconds"] == 100


def test_local_intent_routes_cannot_execute_model_commands(system):
    c, _, _, rh, _, ph = system
    for text, action in [
        ("停止", "stop"),
        ("下一页", "next_page"),
        ("读这本书", "book"),
        ("猜谜", "speak"),
    ]:
        response = c.post(
            "/v1/turns",
            headers=rh,
            json={"sessionId": "session-00000001", "text": text},
        )
        assert response.status_code == 200 and response.json()["action"] == action
    assert (
        c.post(
            "/v1/turns",
            headers=ph,
            json={"sessionId": "session-00000001", "text": "hello"},
        ).status_code
        == 403
    )
    assert (
        c.post(
            "/v1/speech/recognize", headers=rh, files={"file": ("bad.wav", b"not wav")}
        ).status_code
        == 422
    )
    assert c.get("/v1/models", headers=ph).json()["cloudEnabled"] is False


def test_retry_uses_new_job_generation(system, monkeypatch):
    c, store, _, rh, _, ph = system
    rid = create(c, ph)
    # 保留一个排队任务，模拟取消与旧计算尚未返回。
    monkeypatch.setattr(c.app.state.import_pool, "submit", lambda *args: None)
    uploaded = c.post(
        f"/v1/resources/{rid}/assets?purpose=pages&expectedVersion=1",
        headers=ph,
        files={"file": ("sample.txt", b"hello", "text/plain")},
    ).json()
    old = uploaded["jobId"]
    c.post("/v1/jobs/" + old + "/cancel", headers=ph)
    retried = c.post("/v1/jobs/" + old + "/retry", headers=ph).json()
    assert retried["jobId"] != old
    assert (
        store.one("SELECT state FROM jobs WHERE id=?", (old,))["state"] == "cancelled"
    )
    assert (
        store.one("SELECT state FROM jobs WHERE id=?", (retried["jobId"],))["state"]
        == "queued"
    )


def test_offline_keyword_and_forget_clears_context(system):
    c, store, _, rh, _, ph = system
    rid = create(c, ph)
    c.post(
        "/v1/resources/" + rid + "/publish",
        headers=ph,
        json={"requestId": "offline-manifest-01", "expectedVersion": 1},
    )
    m = c.get("/v1/resources/" + rid + "/manifest", headers=rh).json()
    assert "book_" + rid in m["offlineKeywords"]
    c.app.state.sessions[("robot", "session")] = {
        "history": [{"content": "旧个人偏好"}]
    }
    assert c.post("/v1/memories/forget", headers=rh).status_code == 200
    assert not c.app.state.sessions


def test_compressed_audio_is_seekable_and_validated(tmp_path):
    import av
    import numpy as np
    from robot_service.imports import validate_file

    for extension, codec, format_name in [
        (".mp3", "libmp3lame", "mp3"),
        (".m4a", "aac", "mp4"),
    ]:
        path = tmp_path / ("original" + extension)
        with av.open(str(path), "w", format=format_name) as output:
            stream = output.add_stream(codec, rate=24000)
            stream.layout = "mono"
            samples = (
                (np.sin(np.arange(24000) * 2 * np.pi * 440 / 24000) * 0.1)
                .astype(np.float32)
                .reshape(1, -1)
            )
            frame = av.AudioFrame.from_ndarray(samples, format="fltp", layout="mono")
            frame.sample_rate = 24000
            for packet in stream.encode(frame):
                output.mux(packet)
            for packet in stream.encode():
                output.mux(packet)
        validate_file(path.read_bytes(), extension)
    with pytest.raises(Exception):
        validate_file(b"not audio", ".mp3")

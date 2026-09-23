import json

import httpx
from robot_service.management import export_library, restore_library
from robot_service.store import Store


def say(client, headers, text, session="memory-withdrawal-session"):
    response = client.post(
        "/v1/turns", headers=headers, json={"sessionId": session, "text": text}
    )
    assert response.status_code == 200, response.text
    return response.json()


def approved(client, ph, text):
    mid = client.post("/v1/memories", headers=ph, json={"content": text}).json()["id"]
    assert (
        client.put(
            f"/v1/memories/{mid}", headers=ph, json={"state": "approved"}
        ).status_code
        == 200
    )
    return mid


def test_immediate_withdrawal_deletes_only_new_candidate_and_reports_without_content(
    system,
):
    c, store, _, rh, _, ph = system
    unrelated = approved(c, ph, "我喜欢天文")
    say(c, rh, "记住我喜欢汽车")
    candidate = next(
        r for r in store.read("SELECT * FROM memories") if r["id"] != unrelated
    )
    backup = export_library(store)
    c.app.state.sessions[("unused", "session")] = {
        "at": 1,
        "history": [{"content": "汽车"}],
    }
    assert "刚刚那条偏好已经删除" in say(c, rh, "不要记这个")["text"]
    removed = store.one("SELECT * FROM memories WHERE id=?", (candidate["id"],))
    assert removed["state"] == "deleted" and json.loads(removed["body"]) == {}
    assert (
        store.one("SELECT * FROM tombstones WHERE id=?", (candidate["id"],))["kind"]
        == "memory"
    )
    assert (
        store.one("SELECT * FROM memories WHERE id=?", (unrelated,))["state"]
        == "approved"
    )
    assert not c.app.state.sessions and not c.app.state.memory_referents
    events = c.get("/v1/memory-actions", headers=ph).json()
    assert events[0]["action"] == "related_deleted" and events[0]["count"] == 1
    assert "汽车" not in str(events) and "天文" not in str(events)
    assert c.get("/v1/memory-actions", headers=rh).status_code == 403
    restore_library(store, backup)
    assert (
        store.one("SELECT state FROM memories WHERE id=?", (candidate["id"],))["state"]
        == "deleted"
    )


def test_uncertain_reference_suspends_without_deleting_and_never_reuses_context(
    system, monkeypatch
):
    import robot_service.intelligence as module

    c, store, _, rh, _, ph = system
    mid = approved(c, ph, "独特兴趣紫色风筝")
    c.app.state.sessions[("unused", "session")] = {
        "at": 1,
        "history": [{"content": "独特兴趣紫色风筝"}],
    }
    say(c, rh, "不要记这个")
    restarted = Store(store.root)
    row = restarted.one("SELECT * FROM memories WHERE id=?", (mid,))
    assert row["state"] == "suspended" and "紫色风筝" in row["body"]
    assert not c.app.state.sessions
    assert (
        c.get("/v1/memory-actions", headers=ph).json()[0]["action"]
        == "preferences_suspended"
    )
    assert c.get("/v1/usage/summary", headers=ph).json()["pendingMemories"] == 1
    captured = []

    class Model:
        def __init__(self, *args, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, url, **kwargs):
            captured.append(kwargs["json"])
            return httpx.Response(
                200,
                json={"message": {"content": "你好。"}},
                request=httpx.Request("POST", url),
            )

    monkeypatch.setattr(module.httpx, "AsyncClient", Model)
    say(c, rh, "今天天气怎么样")
    assert "紫色风筝" not in str(captured)
    assert (
        c.put(f"/v1/memories/{mid}", headers=ph, json={"state": "approved"}).status_code
        == 200
    )
    say(c, rh, "你好呀")
    assert "紫色风筝" in str(captured[-1])


def test_an_intervening_turn_or_other_session_cannot_delete_stale_reference(system):
    c, store, _, rh, _, ph = system
    say(c, rh, "记住我喜欢汽车")
    say(c, rh, "停止")
    say(c, rh, "不要记这个")
    assert store.read("SELECT state FROM memories") == [{"state": "suspended"}]
    say(c, rh, "记住我喜欢恐龙", session="different-memory-session")
    say(c, rh, "不要记这个")
    assert all(
        row["state"] == "suspended" for row in store.read("SELECT state FROM memories")
    )
    say(c, rh, "删除全部记忆")
    assert all(
        row["state"] == "deleted" for row in store.read("SELECT state FROM memories")
    )
    assert c.get("/v1/memory-actions", headers=ph).json()[0]["action"] == "all_deleted"

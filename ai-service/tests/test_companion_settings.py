from unittest.mock import patch

import httpx


def activate(c, rid, rh, ph, changes):
    c.post("/v1/heartbeat", headers=rh, json={})
    v = c.get(f"/v1/robots/{rid}/config", headers=ph).json()
    v["config"].update(changes)
    command = "configuration-req-" + str(v["version"])
    r = c.post(
        f"/v1/robots/{rid}/config",
        headers=ph,
        json={
            "requestId": command,
            "expectedVersion": v["version"],
            "config": v["config"],
        },
    )
    assert r.status_code == 200, r.text
    r = c.post(
        f"/v1/commands/{command}/ack",
        headers=rh,
        json={"applied": True, "version": v["version"] + 1},
    )
    assert r.status_code == 200, r.text


def test_local_settings_and_prompt_versions(system):
    c, store, r, rh, p, ph = system
    rid = r["deviceId"]
    c.post("/v1/heartbeat", headers=rh, json={})
    v = c.get(f"/v1/robots/{rid}/config", headers=ph).json()["version"]
    body = {
        "requestId": "local-settings-test1",
        "expectedVersion": v,
        "settings": {"policy": {"manualBlocked": False}},
    }
    assert c.post("/v1/local/settings", headers=rh, json=body).status_code == 403
    body["settings"] = {"nickname": "小蜜桃"}
    result = c.post("/v1/local/settings", headers=rh, json=body)
    assert result.status_code == 200, result.text
    assert c.post("/v1/local/settings", headers=ph, json=body).status_code == 403
    c.post(
        "/v1/commands/local-settings-test1/ack",
        headers=rh,
        json={"applied": True, "version": v + 1},
    )
    assert (
        c.get(f"/v1/robots/{rid}/config", headers=ph).json()["config"]["nickname"]
        == "小蜜桃"
    )
    templates = c.get("/v1/prompts/defaults", headers=ph).json()
    templates["daily"] = "你叫{{robot_name}}，请简短回答。"
    activate(c, rid, rh, ph, {"prompts": templates})
    assert len(c.get("/v1/prompts/versions", headers=ph).json()) == 1
    templates["daily"] = "{{unknown}}"
    body["requestId"] = "invalid-prompts-001"
    body["expectedVersion"] = v + 2
    body["settings"] = {"prompts": templates}
    assert c.post("/v1/local/settings", headers=rh, json=body).status_code == 422


def test_history_opt_in_retention_scope_delete(system):
    from robot_service.companion import record_turn

    c, store, r, rh, p, ph = system
    rid = r["deviceId"]
    user = store.one("SELECT * FROM devices WHERE id=?", (rid,))
    assert record_turn(store, user, "session-1234567890", "问", "答") == ""
    activate(c, rid, rh, ph, {"history": {"enabled": True, "days": 7}})
    ident = record_turn(store, user, "session-1234567890", "问", "答")
    assert c.get("/v1/records", headers=ph).json()[0]["id"] == ident
    assert c.get("/v1/records", headers=rh).status_code == 403
    assert c.delete("/v1/records/" + ident, headers=rh).status_code == 403
    assert c.delete("/v1/records/" + ident, headers=ph).status_code == 200
    assert c.get("/v1/records", headers=ph).json() == []
    ident = record_turn(store, user, "session-1234567890", "问", "答")
    with store.transaction() as db:
        db.execute("UPDATE conversations SET created=0 WHERE id=?", (ident,))
    assert c.get("/v1/records", headers=ph).json() == []


def test_companion_turns_visible_only_after_history_is_applied(system):
    c, store, r, rh, p, ph = system
    rid = r["deviceId"]

    def ask(session):
        response = c.post(
            "/v1/turns",
            headers=rh,
            json={"sessionId": session, "text": "为什么会下雨"},
        )
        assert response.status_code == 200, response.text
        assert response.json()["text"]
        return response.json()["text"]

    with patch("httpx.AsyncClient.post", side_effect=AssertionError("禁止调用模型")):
        ask("history-disabled-session")
        assert c.get("/v1/records?kind=companion", headers=ph).json() == []
        activate(c, rid, rh, ph, {"history": {"enabled": True, "days": 30}})
        answer = ask("history-enabled-session")
        rows = c.get("/v1/records?kind=companion", headers=ph).json()
        assert len(rows) == 1
        assert rows[0]["session_id"] == "history-enabled-session"
        assert rows[0]["question"] == "为什么会下雨"
        assert rows[0]["answer"] == answer
        assert c.get("/v1/records?kind=debug", headers=ph).json() == []
        activate(c, rid, rh, ph, {"history": {"enabled": False, "days": 30}})
        ask("history-disabled-again")
        assert c.get("/v1/records?kind=companion", headers=ph).json() == rows


def test_debug_draft_no_child_side_effects(system):
    c, store, r, rh, p, ph = system
    templates = c.get("/v1/prompts/defaults", headers=ph).json()
    templates["daily"] = "测试昵称{{robot_name}}"

    async def answer(self, url, **kw):
        assert "测试昵称小伙伴" in kw["json"]["messages"][0]["content"]
        return httpx.Response(
            200,
            json={"message": {"content": "早上好"}},
            request=httpx.Request("POST", url),
        )

    with patch("httpx.AsyncClient.post", answer):
        result = c.post(
            "/v1/debug/turn",
            headers=ph,
            json={
                "sessionId": "debug-session-123456",
                "text": "你好",
                "prompts": templates,
            },
        )
    assert result.status_code == 200, result.text
    assert result.json()["draft"] and result.json()["text"] == "早上好"
    assert len(c.get("/v1/records?kind=debug", headers=ph).json()) == 1
    assert c.get("/v1/records?kind=debug", headers=rh).json() == []
    assert c.get("/v1/records", headers=ph).json() == []
    assert c.get("/v1/memories", headers=ph).json() == []


def test_prompt_session_snapshot(system):
    c, store, r, rh, p, ph = system
    seen = []

    async def answer(self, url, **kw):
        seen.append(kw["json"]["messages"][0]["content"])
        return httpx.Response(
            200,
            json={"message": {"content": "天上有云。"}},
            request=httpx.Request("POST", url),
        )

    with patch("httpx.AsyncClient.post", answer):
        assert (
            c.post(
                "/v1/turns",
                headers=rh,
                json={"sessionId": "child-session-0001", "text": "聊聊晴天"},
            ).status_code
            == 200
        )
        templates = c.get("/v1/prompts/defaults", headers=ph).json()
        templates["daily"] = "新的提示词独特标记"
        activate(c, r["deviceId"], rh, ph, {"prompts": templates})
        assert (
            c.post(
                "/v1/turns",
                headers=rh,
                json={"sessionId": "child-session-0001", "text": "聊聊云朵"},
            ).status_code
            == 200
        )
        assert (
            c.post(
                "/v1/turns",
                headers=rh,
                json={"sessionId": "child-session-0002", "text": "聊聊晴天"},
            ).status_code
            == 200
        )
    assert "新的提示词独特标记" not in seen[0] + seen[1]
    assert "新的提示词独特标记" in seen[2]


def test_output_quality_is_real_sample_rate_conversion():
    import io
    import math
    import struct
    import wave

    from robot_service.tts import output_quality

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(24000)
        out.writeframes(
            b"".join(
                struct.pack("<h", int(math.sin(i / 24000 * 440 * math.tau) * 1000))
                for i in range(24000)
            )
        )
    raw = buffer.getvalue()
    assert output_quality(raw, "high") == raw
    with wave.open(io.BytesIO(output_quality(raw, "standard")), "rb") as result:
        assert result.getframerate() == 16000
        assert abs(result.getnframes() - 16000) <= 2
        assert result.getnchannels() == 1


def test_heartbeat_playback_and_real_wake_state(system):
    c, store, r, rh, p, ph = system
    result = c.post(
        "/v1/heartbeat",
        headers=rh,
        json={
            "playback": {
                "state": "playing",
                "title": "小兔回家",
                "positionMs": 1234,
                "segment": 1,
                "total": 3,
            },
            "wakeName": "小蜜蜂",
            "wakeVersion": 7,
        },
    )
    assert result.status_code == 200
    status = next(
        x for x in c.get("/v1/devices", headers=ph).json() if x["id"] == r["deviceId"]
    )["status"]
    assert status["playback"]["positionMs"] == 1234
    assert status["wakeName"] == "小蜜蜂"
    assert (
        c.post(
            "/v1/heartbeat", headers=rh, json={"playback": {"state": "not-real"}}
        ).status_code
        == 422
    )


def test_history_speech_authorization_and_delete_during_synthesis(system):
    from robot_service.companion import record_turn

    c, store, r, rh, p, ph = system
    user = store.one("SELECT * FROM devices WHERE id=?", (p["deviceId"],))
    ident = record_turn(store, user, "debug-session-0001", "问题", "回答", "debug")
    assert c.post(f"/v1/records/{ident}/speech", headers=rh).status_code == 403
    assert c.delete(f"/v1/records/{ident}", headers=rh).status_code == 403
    assert c.get("/v1/records?kind=debug").status_code == 401

    async def deleting_speech(*args, **kwargs):
        with store.transaction() as db:
            db.execute("DELETE FROM conversations WHERE id=?", (ident,))
        return b"cancelled-audio"

    with patch("robot_service.intelligence.speech", deleting_speech):
        assert c.post(f"/v1/records/{ident}/speech", headers=ph).status_code == 404


def test_debug_model_failure_leaves_no_record_or_child_state(system):
    c, store, r, rh, p, ph = system

    async def fail_request(self, url, **kwargs):
        raise httpx.ConnectError("model unavailable")

    with patch("httpx.AsyncClient.post", fail_request):
        response = c.post(
            "/v1/debug/turn",
            headers=ph,
            json={"sessionId": "debug-failed-0001", "text": "你好"},
        )
    assert response.status_code == 503
    assert c.get("/v1/records?kind=debug", headers=ph).json() == []
    assert c.get("/v1/memories", headers=ph).json() == []
    assert c.app.state.dialogue_slots.locked() is False

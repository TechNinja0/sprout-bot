import io
import json
import wave

from robot_service.intelligence import route
from test_library import make_book, publish


def test_explicit_requests_do_not_capture_questions_or_negative_commands():
    for text in (
        "读《小兔子》",
        "请读小兔子",
        "给我念一下小兔子",
        "给我讲《小兔子》",
        "唱首歌",
        "讲个故事",
    ):
        assert route(text) == "media"
    for text in (
        "这本书里的小兔为什么伤心",
        "读书有什么好处",
        "What is the story about?",
    ):
        assert route(text) == "chat"
    for text in (
        "读这本书",
        "请给我读一下这本书",
        "讲这本书",
        "给我讲一下这本书",
        "Please read this book",
    ):
        assert route(text) == "book"
    assert route("不要读这本书") == "stop"
    assert route("不要讲这本书") == "stop"
    assert route("换一个") == "media_change"


def turn(c, rh, text, **kwargs):
    response = c.post(
        "/v1/turns",
        headers=rh,
        json={"sessionId": "media-request-test-0001", "text": text, **kwargs},
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_named_read_without_camera_reuses_safe_book_lookup(system):
    c, store, robot, rh, _, ph = system
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
    first = make_book(c, ph, "小兔子")
    response = turn(c, rh, "请读《小兔子》")
    assert response["action"] == "book_result"
    assert response["result"]["reason"] == "UNPUBLISHED"
    assert first["id"] not in str(response) and first["draft"]["pages"][0][
        "text"
    ] not in str(response)
    assert publish(c, ph, first).status_code == 200
    assert turn(c, rh, "讲《小兔子》")["result"]["status"] == "MATCH"
    result = turn(c, rh, "给我念一下小兔子")["result"]
    assert (
        result["status"] == "MATCH"
        and result["candidates"][0]["resourceId"] == first["id"]
    )
    second = make_book(c, ph, "小兔子", "第二版")
    assert publish(c, ph, second).status_code == 200
    assert turn(c, rh, "读小兔子")["result"]["status"] == "AMBIGUOUS"
    assert turn(c, rh, "读《未入库测试书》")["result"]["status"] == "NOT_FOUND"


def test_change_one_requires_available_context_and_excludes_current_resource(system):
    c, _, _, rh, _, ph = system
    first = make_book(c, ph, "第一本测试书")
    assert publish(c, ph, first).status_code == 200
    assert turn(c, rh, "换一个")["action"] == "speak"
    assert turn(c, rh, "换一个", currentResourceId=first["id"])["action"] == "speak"
    second = make_book(c, ph, "第二本测试书")
    assert publish(c, ph, second).status_code == 200
    result = turn(c, rh, "换一个", currentResourceId=first["id"])
    assert result["action"] == "play" and result["resourceId"] == second["id"]
    c.post(f"/v1/resources/{second['id']}/unlist", headers=ph)
    assert turn(c, rh, "换一个", currentResourceId=first["id"])["action"] == "speak"
    c.post(f"/v1/resources/{first['id']}/unlist", headers=ph)
    assert "没有正在读" in turn(c, rh, "换一个", currentResourceId=first["id"])["text"]


def test_sing_request_plays_only_reviewed_original_audio(system):
    c, _, _, rh, _, ph = system
    assert turn(c, rh, "唱首歌")["action"] == "speak"
    resource = c.post(
        "/v1/resources",
        headers=ph,
        json={"kind": "song", "draft": {"title": "原创儿歌夹具"}},
    ).json()
    rid = resource["id"]
    audio = io.BytesIO()
    with wave.open(audio, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(bytes(3200))
    assert (
        c.post(
            f"/v1/resources/{rid}/assets?purpose=audio&expectedVersion=1",
            headers=ph,
            files={"file": ("original.wav", audio.getvalue(), "audio/wav")},
        ).status_code
        == 200
    )
    current = c.get(f"/v1/resources/{rid}", headers=ph).json()
    current["draft"].update(complete=True, auditioned=True)
    current = c.put(
        f"/v1/resources/{rid}",
        headers=ph,
        json={"expectedVersion": current["draft_version"], "draft": current["draft"]},
    ).json()
    current["draft"]["auditioned"] = True
    current = c.put(
        f"/v1/resources/{rid}",
        headers=ph,
        json={"expectedVersion": current["draft_version"], "draft": current["draft"]},
    ).json()
    assert turn(c, rh, "唱首歌")["action"] == "speak"
    assert publish(c, ph, current).status_code == 200
    assert turn(c, rh, "唱首歌")["resourceId"] == rid

import pytest
from test_library import make_book, publish


def test_missing_draft_empty_unlisted_and_published_are_distinct(system):
    client, _, _, robot_headers, _, parent_headers = system

    def lookup(title):
        return client.post(
            "/v1/books/lookup", headers=robot_headers, json={"text": title}
        ).json()

    assert lookup("尚未录入的图书")["status"] == "NOT_FOUND"
    book = make_book(client, parent_headers)
    title = book["draft"]["title"]
    assert lookup(title) == {
        "status": "NOT_READABLE",
        "reason": "UNPUBLISHED",
        "candidates": [],
    }
    empty = client.post(
        "/v1/resources",
        headers=parent_headers,
        json={"kind": "book", "draft": {"title": "只有封面的书"}},
    ).json()
    response = lookup("只有封面的书")
    assert response["reason"] == "NO_TEXT"
    assert response["candidates"] == []
    assert empty["id"] not in str(response)
    assert book["draft"]["pages"][0]["text"] not in str(lookup(title))
    publish(client, parent_headers, book)
    assert lookup(title)["status"] == "MATCH"
    client.post(f"/v1/resources/{book['id']}/unlist", headers=parent_headers)
    assert lookup(title)["reason"] == "UNLISTED"
    client.delete(f"/v1/resources/{book['id']}", headers=parent_headers)
    assert lookup(title)["status"] == "NOT_FOUND"


@pytest.mark.parametrize("ocr_failure", [False, True])
def test_cover_errors_distinguish_retake_and_service_failure(
    system, monkeypatch, ocr_failure
):
    from robot_service import intelligence

    client, _, _, robot_headers, _, _ = system

    def ocr(_):
        if ocr_failure:
            raise ValueError("test OCR failure")
        return {"text": "  ", "blocks": []}

    monkeypatch.setattr(intelligence, "ocr", ocr)
    import io

    from PIL import Image

    source = io.BytesIO()
    Image.new("RGB", (20, 30), "white").save(source, format="PNG")
    response = client.post(
        "/v1/books/recognize",
        headers=robot_headers,
        files={"file": ("cover.png", source.getvalue(), "image/png")},
    )
    assert response.status_code == 200
    assert response.json() == {
        "status": "UNAVAILABLE" if ocr_failure else "RETAKE",
        "reason": "OCR_UNAVAILABLE" if ocr_failure else "NO_COVER_TEXT",
        "candidates": [],
    }


def test_isbn_and_edition_evidence_take_priority_even_when_unpublished(system):
    from test_library import make_book, publish

    c, _, _, rh, _, ph = system
    old = make_book(c, ph, edition="第一版")
    publish(c, ph, old)
    new = make_book(c, ph, edition="第二版")
    draft = new["draft"]
    draft["isbn"] = "9781234567890"
    edited = c.put(
        "/v1/resources/" + new["id"],
        headers=ph,
        json={"expectedVersion": 1, "draft": draft},
    ).json()
    for text in ("小熊的雨伞 978-1-234-56789-0", "小熊的雨伞 第二版"):
        found = c.post("/v1/books/lookup", headers=rh, json={"text": text}).json()
        assert found["status"] == "NOT_READABLE" and found["reason"] == "UNPUBLISHED"
    edited["draft"]["auditioned"] = True
    edited = c.put(
        "/v1/resources/" + new["id"],
        headers=ph,
        json={"expectedVersion": edited["draft_version"], "draft": edited["draft"]},
    ).json()
    assert publish(c, ph, edited).status_code == 200
    found = c.post(
        "/v1/books/lookup", headers=rh, json={"text": "小熊的雨伞 第二版"}
    ).json()
    assert (
        found["status"] == "MATCH" and found["candidates"][0]["resourceId"] == new["id"]
    )


def test_excerpt_notice_separate_from_original_and_bound_to_revision(
    system, monkeypatch
):
    from unittest.mock import AsyncMock

    from test_library import make_book, publish

    c, store, _, rh, _, ph = system
    book = make_book(c, ph)
    draft = book["draft"]
    draft.update(complete=False, excerpt="第2到4页")
    path = "/v1/resources/" + book["id"]
    book = c.put(path, headers=ph, json={"expectedVersion": 1, "draft": draft}).json()
    book["draft"]["auditioned"] = True
    book = c.put(
        path, headers=ph, json={"expectedVersion": 2, "draft": book["draft"]}
    ).json()
    revision = publish(c, ph, book).json()["revisionId"]
    manifest = c.get(path + "/manifest", headers=rh).json()
    assert manifest["scopeNotice"]["id"] == "scope-notice"
    assert "第2到4页" in manifest["scopeNotice"]["text"]
    assert "".join(s["text"] for s in manifest["segments"]) == draft["pages"][0]["text"]
    speech = AsyncMock(return_value=b"fixed notice test audio")
    monkeypatch.setattr("robot_service.intelligence.speech", speech)
    audio = c.get(path + "/audio/scope-notice?revisionId=" + revision, headers=rh)
    assert audio.status_code == 200 and audio.content == b"fixed notice test audio"
    assert speech.call_args.args[1] == manifest["scopeNotice"]["text"]
    # 范围提示不可成为阅读正文进度。
    assert (
        c.put(
            path + "/progress",
            headers=rh,
            json={
                "revisionId": revision,
                "segmentId": "scope-notice",
                "offsetMs": 0,
                "seq": 1,
            },
        ).status_code
        == 422
    )
    assert c.post(path + "/unlist", headers=ph).status_code == 200
    assert (
        c.get(
            path + "/audio/scope-notice?revisionId=" + revision, headers=rh
        ).status_code
        == 409
    )


def test_invalid_cover_does_not_call_ocr(system, monkeypatch):
    c, _, _, rh, _, _ = system

    def unexpected(_):
        pytest.fail("不应把损坏图片交给OCR")

    monkeypatch.setattr("robot_service.intelligence.ocr", unexpected)
    response = c.post(
        "/v1/books/recognize",
        headers=rh,
        files={"file": ("bad.jpg", b"bad image", "image/jpeg")},
    )
    assert response.json() == {
        "status": "RETAKE",
        "reason": "INVALID_IMAGE",
        "candidates": [],
    }

import json

import pytest
from robot_service.library import page_order_warnings
from test_library import make_book, publish


@pytest.mark.parametrize("mode", ["follow_pages", "continuous"])
def test_saved_order_controls_preview_and_publication_not_printed_labels(system, mode):
    c, store, _, rh, _, ph = system
    book = make_book(c, ph)
    rid = book["id"]
    draft = book["draft"]
    draft["readingMode"] = mode
    draft["pages"] = [
        {"id": "three", "label": "3", "text": "第三页。", "reviewed": True},
        {"id": "one", "label": "1", "text": "第一页。", "reviewed": True},
        {"id": "blank", "label": "版权页", "text": "", "skip": True, "reviewed": True},
        {"id": "two", "label": "2", "text": "第二页。", "reviewed": True},
    ]

    def save(current):
        response = c.put(
            f"/v1/resources/{rid}",
            headers=ph,
            json={
                "expectedVersion": current["draft_version"],
                "draft": current["draft"],
            },
        )
        assert response.status_code == 200, response.text
        return response.json()

    def page_ids(body):
        return [part["pageId"] for part in body["segments"]]

    def preview(current):
        response = c.get(
            f"/v1/resources/{rid}/speech-plan",
            headers=ph,
            params={"expectedVersion": current["draft_version"]},
        )
        assert response.status_code == 200, response.text
        return response.json()

    book = save(book)
    assert page_ids(preview(book)) == ["three", "one", "two"]
    original_revision = publish(c, ph, book).json()["revisionId"]
    original = c.get(f"/v1/resources/{rid}/manifest", headers=rh).json()
    assert page_ids(original) == ["three", "one", "two"]

    # Merely editing printed labels must never silently move book pages.
    book["draft"]["pages"][0]["label"] = "第 30 页"
    book = save(book)
    assert page_ids(preview(book)) == ["three", "one", "two"]
    before = {p["id"]: p for p in book["draft"]["pages"]}
    book["draft"]["pages"] = [before[i] for i in ["blank", "one", "two", "three"]]
    book["draft"]["auditioned"] = True
    book = save(book)
    assert book["draft"]["auditioned"] is False
    reloaded = c.get(f"/v1/resources/{rid}", headers=ph).json()
    assert [p["id"] for p in reloaded["draft"]["pages"]] == [
        "blank",
        "one",
        "two",
        "three",
    ]
    assert {p["id"]: p for p in reloaded["draft"]["pages"]} == before
    assert page_ids(preview(book)) == ["one", "two", "three"]
    assert c.get(f"/v1/resources/{rid}/manifest", headers=rh).json() == original

    response = publish(c, ph, book)
    assert response.status_code == 200, response.text
    revision = response.json()["revisionId"]
    assert revision != original_revision
    new = c.get(f"/v1/resources/{rid}/manifest", headers=rh).json()
    assert page_ids(new) == ["one", "two", "three"]
    assert new["segments"] == preview(book)["segments"]
    old = c.get(
        f"/v1/resources/{rid}/manifest",
        headers=rh,
        params={"revisionId": original_revision},
    ).json()
    assert page_ids(old) == ["three", "one", "two"]
    snapshot = json.loads(
        store.one("SELECT body FROM revisions WHERE id=?", (revision,))["body"]
    )
    assert snapshot["pages"] == reloaded["draft"]["pages"]


def test_numeric_printed_gaps_and_repeated_order_are_advisory():
    pages = [{"label": x} for x in ["1", "第 3页", "3", "2"]]
    result = page_order_warnings(pages)
    assert len(result) == 3
    assert "可能缺第2—2页" in result[0]
    assert "重复或逆序" in result[1]
    assert "重复或逆序" in result[2]
    assert pages[1]["label"] == "第 3页"


def test_unknown_labels_and_chapters_do_not_invent_missing_pages():
    assert (
        page_order_warnings([{"label": "1"}, {"label": "插图"}, {"label": "4"}]) == []
    )
    assert (
        page_order_warnings(
            [{"label": "1", "chapter": "前言"}, {"label": "4", "chapter": "正文"}]
        )
        == []
    )
    assert page_order_warnings([{"label": "999999999999"}, {"label": "4"}]) == []


def test_parent_detail_exposes_hint_but_robot_cannot_read_draft(system):
    client, _, _, rh, _, ph = system
    rid = client.post(
        "/v1/resources",
        headers=ph,
        json={
            "kind": "book",
            "draft": {
                "title": "原创缺页检查",
                "pages": [
                    {"id": "a", "label": "1", "text": "甲"},
                    {"id": "b", "label": "4", "text": "乙"},
                ],
            },
        },
    ).json()["id"]
    detail = client.get(f"/v1/resources/{rid}", headers=ph).json()
    assert len(detail["pageOrderWarnings"]) == 1
    assert detail["status"] == "draft"
    assert client.get(f"/v1/resources/{rid}", headers=rh).status_code == 403

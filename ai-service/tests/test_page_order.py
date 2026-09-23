from robot_service.library import page_order_warnings


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

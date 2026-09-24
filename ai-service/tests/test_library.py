import secrets


def make_book(c, ph, title="小熊的雨伞", edition="第一版"):
    return c.post(
        "/v1/resources",
        headers=ph,
        json={
            "kind": "book",
            "draft": {
                "title": title,
                "edition": edition,
                "complete": True,
                "auditioned": True,
                "pages": [
                    {
                        "id": "page-one",
                        "text": "小熊撑起雨伞。小兔说谢谢。",
                        "reviewed": True,
                    }
                ],
            },
        },
    ).json()


def publish(c, ph, r):
    return c.post(
        "/v1/resources/" + r["id"] + "/publish",
        headers=ph,
        json={
            "expectedVersion": r["draft_version"],
            "requestId": secrets.token_hex(16),
        },
    )


def test_draft_publish_exact_read_and_ambiguous(system):
    c, s, r, rh, p, ph = system
    a = make_book(c, ph)
    assert (
        c.post("/v1/books/lookup", headers=rh, json={"text": "小熊的雨伞"}).json()[
            "status"
        ]
        == "NOT_READABLE"
    )
    result = publish(c, ph, a)
    assert result.status_code == 200
    match = c.post(
        "/v1/books/lookup", headers=rh, json={"text": "这是小熊的雨伞 第一版"}
    ).json()
    assert match["status"] == "MATCH"
    m = c.get("/v1/resources/" + a["id"] + "/manifest", headers=rh).json()
    assert "".join(x["text"] for x in m["segments"]) == a["draft"]["pages"][0]["text"]
    b = make_book(c, ph, edition="第二版")
    publish(c, ph, b)
    assert (
        c.post("/v1/books/lookup", headers=rh, json={"text": "小熊的雨伞"}).json()[
            "status"
        ]
        == "AMBIGUOUS"
    )
    assert (
        c.post(
            "/v1/books/lookup",
            headers=rh,
            json={"text": "小熊的雨伞", "edition": "第二版"},
        ).json()["candidates"][0]["resourceId"]
        == b["id"]
    )


def test_song_requires_actual_audio(system):
    c, _, _, _, _, ph = system
    book = make_book(c, ph)
    song = c.post(
        "/v1/resources", headers=ph, json={"kind": "song", "draft": book["draft"]}
    ).json()
    response = publish(c, ph, song)
    assert response.status_code == 422
    assert "实际音频" in response.text


def test_edits_do_not_overwrite_published_and_stale_fails(system):
    c, s, r, rh, p, ph = system
    a = make_book(c, ph)
    old = publish(c, ph, a).json()["revisionId"]
    path = "/v1/resources/" + a["id"]
    draft = a["draft"]
    draft["pages"][0]["text"] = "新的正文。"
    result = c.put(path, headers=ph, json={"expectedVersion": 1, "draft": draft})
    assert result.status_code == 200
    assert result.json()["draft"]["auditioned"] is False
    assert (
        c.put(path, headers=ph, json={"expectedVersion": 1, "draft": draft}).status_code
        == 409
    )
    assert c.get(path + "/manifest", headers=rh).json()["revisionId"] == old
    released = publish(c, ph, result.json())
    assert released.status_code == 200
    assert released.json()["revisionId"] != old


def test_unlist_revokes_old_manifest_and_delete_removes_text(system):
    c, s, r, rh, p, ph = system
    a = make_book(c, ph)
    pub = publish(c, ph, a).json()
    path = "/v1/resources/" + a["id"]
    assert c.post(path + "/unlist", headers=ph).status_code == 200
    assert (
        c.get(
            path + "/manifest?revisionId=" + pub["revisionId"], headers=rh
        ).status_code
        == 409
    )
    assert (
        c.post(
            "/v1/books/lookup", headers=rh, json={"text": a["draft"]["title"]}
        ).json()["status"]
        == "NOT_READABLE"
    )
    assert c.delete(path, headers=ph).status_code == 200
    assert s.one("SELECT draft FROM resources WHERE id=?", (a["id"],))["draft"] == "{}"
    assert not s.read("SELECT * FROM revisions WHERE resource_id=?", (a["id"],))


def test_revocations_distinguish_unlisted_versions_from_normal_republication(system):
    c, _, _, rh, _, ph = system
    book = make_book(c, ph)
    rid = book["id"]
    revoked = publish(c, ph, book).json()["revisionId"]
    assert c.post(f"/v1/resources/{rid}/unlist", headers=ph).status_code == 200
    reading = publish(c, ph, book).json()["revisionId"]
    latest = publish(c, ph, book).json()["revisionId"]
    item = next(
        item
        for item in c.get("/v1/catalog/revocations", headers=rh).json()["items"]
        if item["id"] == rid
    )
    assert item["activeRevision"] == latest
    # 不能把“不是最新版”当作“已撤销”，否则一次历史下架会破坏之后所有普通改版。
    assert item["revokedRevisionIds"] == [revoked]
    assert reading not in item["revokedRevisionIds"]
    assert c.get(
        f"/v1/resources/{rid}/manifest?revisionId={reading}", headers=rh
    ).status_code == 200
    assert c.post(f"/v1/resources/{rid}/unlist", headers=ph).status_code == 200
    item = next(
        item
        for item in c.get("/v1/catalog/revocations", headers=rh).json()["items"]
        if item["id"] == rid
    )
    assert item["activeRevision"] == ""
    assert set(item["revokedRevisionIds"]) == {revoked, reading, latest}


def test_progress_rejects_wrong_segment_and_stale_sequence(system):
    c, s, r, rh, p, ph = system
    a = make_book(c, ph)
    publish(c, ph, a)
    path = "/v1/resources/" + a["id"]
    m = c.get(path + "/manifest", headers=rh).json()
    body = {
        "revisionId": m["revisionId"],
        "segmentId": m["segments"][0]["id"],
        "offsetMs": 500,
        "seq": 1,
    }
    assert c.put(path + "/progress", headers=rh, json=body).json()["accepted"]
    assert not c.put(path + "/progress", headers=rh, json=body).json()["accepted"]
    assert (
        c.put(
            path + "/progress",
            headers=rh,
            json={**body, "segmentId": "invented", "seq": 2},
        ).status_code
        == 422
    )


def test_cover_only_never_readable_and_bad_refs_rejected(system):
    c, s, r, rh, p, ph = system
    book = c.post(
        "/v1/resources",
        headers=ph,
        json={
            "kind": "book",
            "draft": {"title": "只有封面", "complete": True, "auditioned": True},
        },
    ).json()
    assert publish(c, ph, book).status_code == 422
    assert (
        c.post(
            "/v1/resources",
            headers=ph,
            json={"kind": "book", "draft": {"title": "偷素材", "coverAsset": "other"}},
        ).status_code
        == 422
    )


def test_publish_requires_blank_page_to_be_explicitly_skipped(system):
    c, store, robot, rh, parent, ph = system
    resource = make_book(c, ph)
    draft = resource["draft"]
    draft["pages"].append({"id": "blank", "text": "  ", "reviewed": True})
    resource = c.put("/v1/resources/" + resource["id"], headers=ph,
        json={"expectedVersion": resource["draft_version"], "draft": draft}).json()
    draft = resource["draft"]
    draft["auditioned"] = True
    resource = c.put("/v1/resources/" + resource["id"], headers=ph,
        json={"expectedVersion": resource["draft_version"], "draft": draft}).json()
    result = publish(c, ph, resource)
    assert result.status_code == 422
    assert "空白页" in result.json()["detail"]
    draft = resource["draft"]
    draft["pages"][1]["skip"] = True
    resource = c.put("/v1/resources/" + resource["id"], headers=ph,
        json={"expectedVersion": resource["draft_version"], "draft": draft}).json()
    assert resource["draft"]["auditioned"] is False
    draft = resource["draft"]
    draft["auditioned"] = True
    resource = c.put("/v1/resources/" + resource["id"], headers=ph,
        json={"expectedVersion": resource["draft_version"], "draft": draft}).json()
    assert publish(c, ph, resource).status_code == 200


def test_publish_without_audition_still_checks_review_and_scope(system):
    c, store, robot, rh, parent, ph = system
    r = c.post("/v1/resources", headers=ph, json={"kind": "book", "draft": {
        "title": "可选试听", "complete": True, "pages": [{"id": "p", "text": "小兔回家。", "reviewed": True}]
    }}).json()
    assert r["draft"]["auditioned"] is False
    assert publish(c, ph, r).status_code == 200
    draft = r["draft"]
    draft["pages"][0]["reviewed"] = False
    r = c.put("/v1/resources/" + r["id"], headers=ph, json={"expectedVersion": r["draft_version"], "draft": draft}).json()
    assert publish(c, ph, r).status_code == 422
    draft = r["draft"]
    draft["pages"][0]["reviewed"] = True
    draft["complete"] = False
    r = c.put("/v1/resources/" + r["id"], headers=ph, json={"expectedVersion": r["draft_version"], "draft": draft}).json()
    assert publish(c, ph, r).status_code == 422

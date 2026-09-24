import time
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import pytest
from robot_service.knowledge import MISS, Knowledge, normalize
from robot_service.management import export_library, restore_library
from robot_service.store import Store


def create(c, ph, **changes):
    card = dict(
        question="我的积木在哪里",
        aliases=["积木放哪儿"],
        answer="积木放在客厅的蓝色盒子里。玩完后，我们一起把它放回原处。",
        briefAnswer="在客厅的蓝色盒子里。",
        detailAnswer="积木放在客厅的蓝色盒子里。先找到小书架，再看看最下面一层，就能找到它。",
        kind="family",
        minAge=3,
        maxAge=18,
    )
    card.update(changes)
    response = c.post("/v1/knowledge", headers=ph, json={"draft": card})
    assert response.status_code == 200, response.text
    return response.json()


def publish(c, ph, row):
    response = c.post(
        f"/v1/knowledge/{row['id']}/publish",
        headers=ph,
        json={"expectedVersion": row["version"], "reviewed": True},
    )
    assert response.status_code == 200, response.text
    return response.json()


def turn(c, rh, text, session="knowledge-test-session"):
    response = c.post(
        "/v1/turns", headers=rh, json={"sessionId": session, "text": text}
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_parent_permissions_and_seed_integrity(system):
    c, store, robot, rh, _, ph = system
    assert c.get("/v1/knowledge").status_code == 401
    assert c.get("/v1/knowledge", headers=rh).status_code == 403
    assert (
        c.post(
            "/v1/knowledge/preview", headers=rh, json={"text": "为什么会下雨"}
        ).status_code
        == 403
    )
    rows = c.get("/v1/knowledge", headers=ph).json()["items"]
    assert len(rows) == 30
    for row in rows:
        card = c.get("/v1/knowledge/" + row["id"], headers=ph).json()["draft"]
        assert card["source"] and card["sourceUrl"].startswith("https://")
        assert 60 <= len(card["answer"]) <= 550
        result = c.app.state.knowledge.query(card["question"])
        assert result["status"] == "matched" and result["text"] == card["answer"]
        for alias in card["aliases"]:
            assert c.app.state.knowledge.query(alias)["knowledge"]["id"] == row["id"]
    row = create(c, ph)
    path = "/v1/knowledge/" + row["id"]
    for method, suffix, body in [
        ("put", "", {"expectedVersion": 1, "draft": row["draft"]}),
        ("post", "/publish", {"expectedVersion": 1, "reviewed": True}),
        ("post", "/disable", {"expectedVersion": 1}),
    ]:
        assert (
            getattr(c, method)(path + suffix, headers=rh, json=body).status_code == 403
        )
    assert c.delete(path + "?expectedVersion=1", headers=rh).status_code == 403


def test_draft_publish_edit_disable_delete_and_followups(system):
    c, store, robot, rh, _, ph = system
    manager = c.app.state.knowledge
    row = create(c, ph)
    path = "/v1/knowledge/" + row["id"]
    assert manager.query(row["draft"]["question"])["status"] == "miss"
    result = c.post(
        "/v1/knowledge/preview",
        headers=ph,
        json={"text": "积木放哪儿", "draft": row["draft"]},
    ).json()
    assert result["draft"] and result["text"] == row["draft"]["answer"]
    row = publish(c, ph, row)
    with patch(
        "httpx.AsyncClient.post", side_effect=AssertionError("知识路径不许调用模型")
    ):
        assert turn(c, rh, "积木放哪儿")["text"] == row["draft"]["answer"]
        assert turn(c, rh, "再详细讲讲")["text"] == row["draft"]["detailAnswer"]
        assert turn(c, rh, "简单一点")["text"] == row["draft"]["briefAnswer"]
        original = row["draft"]["answer"]
        row["draft"]["answer"] = "积木现在放在卧室的绿色盒子里。"
        changed = c.put(
            path,
            headers=ph,
            json={"expectedVersion": row["version"], "draft": row["draft"]},
        ).json()
        assert changed["hasChanges"]
        assert manager.query("积木放哪儿")["text"] == original
        assert (
            c.put(
                path,
                headers=ph,
                json={"expectedVersion": row["version"], "draft": row["draft"]},
            ).status_code
            == 409
        )
        row = publish(c, ph, changed)
        assert turn(c, rh, "再讲一遍")["text"] == row["draft"]["answer"]
        disabled = c.post(
            path + "/disable", headers=ph, json={"expectedVersion": row["version"]}
        ).json()
        assert turn(c, rh, "再讲一遍")["text"] == MISS
        row = publish(c, ph, disabled)
        assert (
            c.delete(
                path + f"?expectedVersion={row['version']}", headers=ph
            ).status_code
            == 200
        )
        assert c.get(path, headers=ph).status_code == 404
        assert Knowledge(store).query("积木放哪儿")["status"] == "miss"


def test_matching_preserves_meaning_and_does_not_make_up_answers(system):
    c, _, _, rh, _, ph = system
    manager = c.app.state.knowledge
    assert normalize("请问，为什么会下雨？") == "为什么会下雨"
    assert normalize("再详细讲讲") == "再详细讲讲"
    with patch("httpx.AsyncClient.post", side_effect=AssertionError("禁止联网")):
        result = turn(c, rh, "请问，为什么会下雨？")
        assert result["knowledge"]["source"] and result["lookupMs"] < 100
        for q in [
            "为什么天空是绿色的",
            "太阳为什么不会发光",
            "太阳系有九大行星吗",
            "苹果是什么？",
        ]:
            result = manager.query(q)
            assert result["status"] != "matched"
        assert manager.query("苹果是什么？")["status"] == "miss"
        assert turn(c, rh, "宇宙里到底有多少个星球")["text"] == MISS
        assert "knowledge" not in turn(c, rh, "停止")


def test_age_duplicates_review_validation_and_preview(system):
    c, _, _, _, _, ph = system
    row = create(c, ph, minAge=7, maxAge=10)
    path = "/v1/knowledge/" + row["id"]
    assert (
        c.post(
            path + "/publish",
            headers=ph,
            json={"expectedVersion": 1, "reviewed": False},
        ).status_code
        == 422
    )
    publish(c, ph, row)
    assert c.app.state.knowledge.query("积木放哪儿", 5)["status"] == "miss"
    assert (
        c.app.state.knowledge.query("积木放哪儿", 8)["text"]
        == row["draft"]["detailAnswer"]
    )
    duplicate = publish(c, ph, create(c, ph))
    assert c.app.state.knowledge.query("积木放哪儿", 8)["status"] == "ambiguous"
    assert (
        c.app.state.knowledge.query("积木放哪儿", 5)["knowledge"]["id"]
        == duplicate["id"]
    )
    bad = create(c, ph, kind="encyclopedia", source="")
    assert (
        c.post(
            "/v1/knowledge/" + bad["id"] + "/publish",
            headers=ph,
            json={"expectedVersion": 1, "reviewed": True},
        ).status_code
        == 422
    )
    assert (
        c.post(
            "/v1/knowledge/preview",
            headers=ph,
            json={"text": "hi", "draft": {"question": "你好"}},
        ).status_code
        == 422
    )
    assert (
        c.post(
            "/v1/knowledge",
            headers=ph,
            json={"draft": {"question": "测试", "minAge": 10, "maxAge": 3}},
        ).status_code
        == 422
    )
    assert (
        c.post(
            "/v1/knowledge",
            headers=ph,
            json={"draft": {"question": "测试", "sourceUrl": "javascript:alert(1)"}},
        ).status_code
        == 422
    )


def test_busy_model_does_not_delay_knowledge_or_debug(system):
    c, _, _, rh, _, ph = system
    with patch("httpx.AsyncClient.post", side_effect=AssertionError("不得调用模型")):
        # 清空可用名额；若触碰该锁，将等待1秒并报429。
        c.app.state.dialogue_slots._value = 0
        start = time.perf_counter()
        result = turn(c, rh, "为什么会下雨")
        assert time.perf_counter() - start < 0.5
        assert result["knowledgeStatus"] == "matched"
        debug = c.post(
            "/v1/debug/turn",
            headers=ph,
            json={"sessionId": "knowledge-debug-001", "text": "为什么会下雨"},
        )
        assert debug.status_code == 200 and debug.json()["knowledgeStatus"] == "matched"
        assert (
            c.post(
                "/v1/debug/turn",
                headers=ph,
                json={"sessionId": "knowledge-debug-001", "text": "简单一点"},
            ).json()["text"]
            != result["text"]
        )
        c.app.state.dialogue_slots._value = 1


def test_backup_restore_drafts_preserves_deletions_and_existing_edits(system, tmp_path):
    c, store, _, _, _, ph = system
    parent = publish(c, ph, create(c, ph))
    seed = c.get("/v1/knowledge", headers=ph).json()["items"][-1]
    assert (
        c.delete(
            "/v1/knowledge/" + seed["id"] + "?expectedVersion=1", headers=ph
        ).status_code
        == 200
    )
    raw = export_library(store)
    target = Store(tmp_path / "restored")
    Knowledge(target)
    result = restore_library(target, raw)
    assert result["restoredKnowledgeDrafts"] == 30
    manager = Knowledge(target)
    assert not manager.snapshot.entries
    assert (
        target.one("SELECT state FROM knowledge_cards WHERE id=?", (seed["id"],))[
            "state"
        ]
        == "deleted"
    )
    assert (
        target.one("SELECT state FROM knowledge_cards WHERE id=?", (parent["id"],))[
            "state"
        ]
        == "draft"
    )
    assert restore_library(store, raw)["restoredKnowledgeDrafts"] == 29
    assert Knowledge(store).query("积木放哪儿")["status"] == "matched"


def test_concurrent_publish_uses_version_check(system):
    c, _, _, _, _, ph = system
    row = create(c, ph)

    def commit(_):
        return c.post(
            "/v1/knowledge/" + row["id"] + "/publish",
            headers=ph,
            json={"expectedVersion": 1, "reviewed": True},
        ).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(commit, range(2))) == [200, 409]


def test_lookup_reads_no_database(system, monkeypatch):
    c, store, *_ = system
    monkeypatch.setattr(
        store, "read", lambda *a, **k: pytest.fail("检索不能查询数据库")
    )
    monkeypatch.setattr(store, "one", lambda *a, **k: pytest.fail("检索不能查询数据库"))
    assert c.app.state.knowledge.query("为什么会下雨")["status"] == "matched"
    assert c.app.state.knowledge.query("没有收录的内容是什么")["status"] == "miss"


def test_numeric_qualifiers_and_disabled_followups(system):
    c, _, _, rh, _, ph = system
    assert normalize("1.5米") != normalize("15米")
    assert normalize("零下-5度") != normalize("零下5度")
    assert normalize("20%") != normalize("20")
    row = publish(c, ph, create(c, ph))
    turn(c, rh, "积木放哪儿")
    c.post(
        "/v1/knowledge/" + row["id"] + "/disable",
        headers=ph,
        json={"expectedVersion": row["version"]},
    )
    with patch(
        "httpx.AsyncClient.post",
        side_effect=AssertionError("停用的知识不能转给模型编造"),
    ):
        assert turn(c, rh, "再讲一遍")["text"] == MISS
        assert turn(c, rh, "再详细讲讲")["text"] == MISS


def test_knowledge_change_clears_old_context_and_cancels_inflight_answer(system):
    import asyncio
    import threading

    import httpx

    c, _, robot, rh, _, ph = system
    row = publish(c, ph, create(c, ph))
    turn(c, rh, "积木放哪儿")
    started, release = threading.Event(), threading.Event()

    async def answer(self, url, **kwargs):
        assert row["draft"]["answer"] in str(kwargs["json"]["messages"])
        started.set()
        await asyncio.to_thread(release.wait, 5)
        return httpx.Response(
            200,
            json={"message": {"content": "这里复述了旧知识。"}},
            request=httpx.Request("POST", url),
        )

    with (
        patch("httpx.AsyncClient.post", answer),
        ThreadPoolExecutor(max_workers=1) as pool,
    ):
        pending = pool.submit(
            c.post,
            "/v1/turns",
            headers=rh,
            json={"sessionId": "knowledge-test-session", "text": "再聊聊刚才的话题"},
        )
        assert started.wait(5)
        c.post(
            "/v1/knowledge/" + row["id"] + "/disable",
            headers=ph,
            json={"expectedVersion": row["version"]},
        )
        release.set()
        assert pending.result().status_code == 409
    assert (
        c.app.state.sessions[(robot["deviceId"], "knowledge-test-session")]["history"]
        == []
    )


def test_clarification_confirmation_rechecks_current_publication(system):
    c, _, _, rh, _, ph = system
    row = publish(c, ph, create(c, ph, question="我的积木放在哪里", aliases=[]))
    with patch(
        "httpx.AsyncClient.post", side_effect=AssertionError("确认问法不调用模型")
    ):
        assert turn(c, rh, "我的积木放在哪里呢")["status"] == "clarify"
        assert turn(c, rh, "是的")["text"] == row["draft"]["answer"]
        assert turn(c, rh, "我的积木放在哪里呢")["status"] == "clarify"
        assert turn(c, rh, "不是")["text"] == "好的，请把想问的问题完整说一遍。"
        assert turn(c, rh, "我的积木放在哪里呢")["status"] == "clarify"
        c.post(
            "/v1/knowledge/" + row["id"] + "/disable",
            headers=ph,
            json={"expectedVersion": row["version"]},
        )
        assert turn(c, rh, "对")["text"] == MISS

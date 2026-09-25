"""知识未收录不能阻断模型；模拟调用只验证路由，真实质量另行抽查。"""

import json

import httpx
import pytest
from robot_service.reply_policy import (
    ANSWER_GUIDANCE,
    turn_guidance,
    unavailable_fact_reply,
)


@pytest.mark.parametrize("debug", [False, True])
@pytest.mark.parametrize(
    "question", ["苹果是什么？", "宇宙里到底有多少个星球？", "为什么鱼能在水里呼吸？"]
)
def test_unlisted_question_reaches_local_model_with_existing_prompts(
    system, monkeypatch, debug, question
):
    c, store, robot, rh, _, ph = system
    # 现有家庭保存的模板也要得到本轮策略，不依赖恢复默认提示词。
    config = json.loads(
        store.one("SELECT body FROM configs WHERE robot_id=?", (robot["deviceId"],))[
            "body"
        ]
    )
    config["prompts"]["daily"] = "你是孩子的陪伴伙伴。"
    with store.transaction() as db:
        db.execute(
            "UPDATE configs SET body=? WHERE robot_id=?",
            (json.dumps(config), robot["deviceId"]),
        )
    calls = []
    answer_text = "我们还不知道宇宙中星球的确切总数，因为我们只能观测到其中一部分。"

    async def answer(self, url, **kwargs):
        assert url == "http://127.0.0.1:11435/api/chat"
        calls.append(kwargs["json"])
        return httpx.Response(
            200,
            json={"message": {"content": answer_text}},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", answer)
    response = c.post(
        "/v1/debug/turn" if debug else "/v1/turns",
        headers=ph if debug else rh,
        json={"sessionId": "fallback-existing-prompts", "text": question},
    )
    assert response.status_code == 200, response.text
    assert response.json()["text"] == answer_text
    assert "knowledge" not in response.json()
    assert len(calls) == 1
    assert ANSWER_GUIDANCE in calls[0]["messages"][0]["content"]


@pytest.mark.parametrize(
    "question,explanation",
    [
        ("这个药能吃吗", "用途"),
        ("插座为什么危险", "导电"),
        ("我想自己点火", "烫伤"),
        ("这个蘑菇能吃吗", "不能确认"),
    ],
)
def test_safety_reply_explains_reason_without_unsafe_model_advice(
    system, monkeypatch, question, explanation
):
    c, _, _, rh, _, _ = system

    async def unexpected(*args, **kwargs):
        pytest.fail("实际安全操作不能放行给模型决定")

    monkeypatch.setattr(httpx.AsyncClient, "post", unexpected)
    response = c.post(
        "/v1/turns",
        headers=rh,
        json={"sessionId": "fallback-safety-check", "text": question},
    )
    assert response.status_code == 200, response.text
    text = response.json()["text"]
    assert explanation in text and "爸爸妈妈" in text
    assert len(text) > 50


def test_preview_miss_does_not_pretend_to_be_final_answer(system, monkeypatch):
    c, _, _, _, _, ph = system

    async def unexpected(*args, **kwargs):
        pytest.fail("知识库预览仍然不调用模型")

    monkeypatch.setattr(httpx.AsyncClient, "post", unexpected)
    response = c.post(
        "/v1/knowledge/preview", headers=ph, json={"text": "苹果是什么？"}
    )
    assert response.status_code == 200
    assert response.json()["status"] == "miss"
    assert "知识卡片" in response.json()["text"]
    assert "爸爸妈妈" not in response.json()["text"]


@pytest.mark.parametrize("debug", [False, True])
def test_live_weather_without_source_cannot_be_invented(system, monkeypatch, debug):
    c, _, _, rh, _, ph = system

    async def unexpected(*args, **kwargs):
        pytest.fail("没有实时数据时不能让模型编造天气")

    monkeypatch.setattr(httpx.AsyncClient, "post", unexpected)
    response = c.post(
        "/v1/debug/turn" if debug else "/v1/turns",
        headers=ph if debug else rh,
        json={"sessionId": "fallback-current-weather", "text": "今天北京天气怎么样？"},
    )
    assert response.status_code == 200, response.text
    assert "没有接入实时天气" in response.json()["text"]
    assert "爸爸妈妈" not in response.json()["text"]


@pytest.mark.parametrize(
    "question",
    [
        "天气预报是什么？",
        "什么是天气预报？",
        "天气预报怎么知道明天会下雨？",
        "今天北京天气怎么样用英语怎么说？",
        "为什么会下雨？",
    ],
)
def test_weather_concept_or_translation_is_not_a_request_for_live_data(question):
    assert unavailable_fact_reply(question) is None


@pytest.mark.parametrize(
    "question,story",
    [
        ("一句话告诉我苹果是什么", False),
        ("苹果是什么", False),
        ("苹果用英文怎么说", False),
        ("编一个小兔子的故事", True),
    ],
)
def test_answering_and_uncertainty_policy_applies_to_all_text_modes(question, story):
    assert ANSWER_GUIDANCE in turn_guidance(question, story=story)

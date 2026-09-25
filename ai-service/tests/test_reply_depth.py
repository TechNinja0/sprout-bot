import json

import httpx
import pytest
from robot_service.intelligence import Speak
from robot_service.reply_policy import MAX_REPLY_CHARS, STORY_PREFIX, bound_reply

EXPLANATION = (
    "雨来自云里的小水滴。太阳晒着地面，河里和地上的一些水变成看不见的水蒸气，进入空气。"
    "带着水蒸气的空气上升后变冷，水蒸气就变成很小的水滴，许多小水滴聚在一起形成云。"
    "云里的小水滴碰在一起，会合成更大的水滴。水滴大到空气托不住时，就落下来成为雨。"
    "所以云里早就有许多小水滴，等它们聚得够大，才会从天空落下来。"
)
STORY = (
    "小兔团团在花园里找一个能装落叶的篮子。它想把漂亮的落叶送给奶奶，让奶奶也看看秋天的颜色。"
    "它先拿来一个小杯子，可是叶子太宽，怎么也装不进去。团团有点着急，坐在石头边想办法。"
    "小熊问：“用我的篮子好吗？”团团看见篮子又宽又浅，连忙点点头。"
    "它们一起把叶子平平地放好，再用细细的草绳轻轻固定。一路上，两人轮流提篮子，还提醒对方慢慢走。"
    "到了奶奶家，叶子一片也没有弄皱。奶奶把叶子摆在窗边，阳光照得红叶透亮。"
    "团团看着小熊笑了，原来换一个合适的容器，再一起想办法，礼物就能完整地送到。"
)


@pytest.mark.parametrize("story,raw", [(False, EXPLANATION), (True, STORY)])
def test_complete_reply_reaches_history_and_speech(system, monkeypatch, story, raw):
    client, store, robot, headers, _, _ = system
    if story:
        config = json.loads(
            store.one(
                "SELECT body FROM configs WHERE robot_id=?", (robot["deviceId"],)
            )["body"]
        )
        config["originalStories"] = True
        with store.transaction() as db:
            db.execute(
                "UPDATE configs SET body=? WHERE robot_id=?",
                (json.dumps(config), robot["deviceId"]),
            )
    calls = []

    async def answer(self, url, **kwargs):
        calls.append(kwargs["json"])
        return httpx.Response(
            200,
            json={"message": {"content": raw + "还想继续听吗？"}},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", answer)
    session = "reply-depth-regression"
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={
            "sessionId": session,
            "text": "编一个小兔子的故事" if story else "请解释下雨的过程",
        },
    )
    assert response.status_code == 200, response.text
    text = response.json()["text"]
    assert text == (STORY_PREFIX if story else "") + raw
    assert len(text) > 120
    assert Speak(text=text).text == text
    assert (
        client.app.state.sessions[(robot["deviceId"], session)]["history"][-1][
            "content"
        ]
        == text
    )
    assert calls[0]["options"]["num_predict"] >= 1024
    assert calls[0]["options"]["num_ctx"] >= 8192


@pytest.mark.parametrize(
    "kind,question",
    [
        ("daily", "请解释下雨的过程"),
        ("daily", "讲个小兔子的故事"),
        ("story", "小兔子送礼物"),
        ("english", "下雨用英文怎么说？"),
    ],
)
def test_debug_uses_daily_guidance_and_full_generation_budget(
    system, monkeypatch, kind, question
):
    client, _, _, _, _, headers = system
    calls = []

    async def answer(self, url, **kwargs):
        calls.append(kwargs["json"])
        return httpx.Response(
            200,
            json={"message": {"content": STORY}},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", answer)
    response = client.post(
        "/v1/debug/turn",
        headers=headers,
        json={
            "sessionId": "debug-depth-regression",
            "text": question,
            "promptKind": kind,
        },
    )
    assert response.status_code == 200, response.text
    assert response.json()["text"] == STORY
    system_prompt = calls[0]["messages"][0]["content"]
    assert "两三步讲清原因" in system_prompt
    if kind == "story" or "故事" in question:
        assert "通常350—500字" in system_prompt
    assert calls[0]["options"]["num_predict"] >= 1024


def test_overlong_reply_stops_at_complete_sentence_and_fits_speech():
    raw = EXPLANATION * 5
    text = bound_reply(raw)
    assert len(text) <= MAX_REPLY_CHARS
    assert text.endswith("。")
    assert raw.startswith(text)
    assert Speak(text=text)
    assert len(bound_reply("字" * 1000)) == MAX_REPLY_CHARS


def test_story_prefix_fits_speech_limit():
    text = STORY_PREFIX + bound_reply(STORY * 4, MAX_REPLY_CHARS - len(STORY_PREFIX))
    assert Speak(text=text)
    assert len(text) <= MAX_REPLY_CHARS

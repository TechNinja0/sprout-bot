import base64
import io
import json

import httpx
from PIL import Image


def test_visual_usage_and_clarification_keep_two_sentences_and_text_only_context(
    system, monkeypatch
):
    import robot_service.intelligence as module

    client, _, _, headers, _, _ = system
    calls = []
    responses = iter(
        [
            json.dumps(
                {
                    "objectCount": 1,
                    "objects": ["勺子"],
                    "unclear": False,
                    "needsClarification": False,
                    "answer": "看起来是一把勺子。",
                    "generalUse": "一般用来舀东西。",
                }
            ),
            "勺子一般用来舀东西。",
            json.dumps(
                {
                    "objectCount": 2,
                    "objects": ["红色圆", "蓝色方块"],
                    "unclear": False,
                    "needsClarification": False,
                    "answer": "这里有两个东西。",
                    "generalUse": "",
                }
            ),
            "红色的是圆形，一般可以用来认识形状。",
        ]
    )

    class LocalClient:
        def __init__(self, *args, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, *args, **kwargs):
            calls.append(kwargs["json"])
            return httpx.Response(
                200,
                json={"message": {"content": next(responses)}},
                request=httpx.Request("POST", "http://127.0.0.1:11435/api/chat"),
            )

    monkeypatch.setattr(module.httpx, "AsyncClient", LocalClient)
    image = io.BytesIO()
    Image.new("RGB", (16, 16), "red").save(image, "PNG")
    payload = {
        "sessionId": "visual-dialogue-1",
        "text": "这个怎么用",
        "visualRequest": True,
        "imageAgeMs": 0,
        "image": base64.b64encode(image.getvalue()).decode(),
    }
    response = client.post("/v1/turns", headers=headers, json=payload)
    assert response.status_code == 200
    assert response.json()["text"] == "看起来是勺子。一般用来舀东西。"
    assert calls[0]["model"] == "qwen3.5:4b"
    assert calls[0]["messages"][-1]["images"] == [payload["image"]]
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={"sessionId": payload["sessionId"], "text": "它怎么用"},
    )
    assert response.status_code == 200
    assert calls[-1]["model"] == "qwen3.5:2b"
    history = calls[-1]["messages"]
    assert "最近已明确的视觉对象" in history[0]["content"]
    assert "勺子" in history[0]["content"]
    assert any("勺子" in row["content"] for row in history)
    assert all("images" not in row for row in history)
    payload["text"] = "这是什么"
    response = client.post("/v1/turns", headers=headers, json=payload)
    # 安静性格不能删除必要的对象消歧问题。
    assert response.json()["text"].endswith("你指的是哪一个？")
    assert len(calls[-1]["messages"]) == 2  # 新画面不被上轮视觉猜测污染。
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={"sessionId": payload["sessionId"], "text": "刚才那个怎么用"},
    )
    assert response.status_code == 200
    assert response.json()["text"].endswith("你指的是哪一个？")
    assert len(calls) == 3  # 未消歧的“它”不能被模型猜成机器人或其他对象。
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={"sessionId": payload["sessionId"], "text": "红色那个怎么用"},
    )
    assert response.status_code == 200 and len(calls) == 4
    assert "红色圆" in calls[-1]["messages"][0]["content"]


def test_nearly_black_frame_requests_better_view_before_model_call(system, monkeypatch):
    import robot_service.intelligence as module

    client, _, _, headers, _, _ = system

    def unexpected_model(*args, **kwargs):
        raise AssertionError("不可将缺少物体证据的黑图交给模型猜测")

    monkeypatch.setattr(module.httpx, "AsyncClient", unexpected_model)
    image = io.BytesIO()
    Image.new("RGB", (64, 64), (3, 3, 3)).save(image, "PNG")
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={
            "sessionId": "dark-frame-test-0001",
            "text": "这是什么？",
            "visualRequest": True,
            "imageAgeMs": 0,
            "image": base64.b64encode(image.getvalue()).decode(),
        },
    )
    assert response.status_code == 200
    assert "太暗" in response.json()["text"]
    followup = client.post(
        "/v1/turns",
        headers=headers,
        json={"sessionId": "dark-frame-test-0001", "text": "它怎么用"},
    )
    assert "太暗" in followup.json()["text"]


def test_missing_new_frame_invalidates_previous_visual_reference(system, monkeypatch):
    import time

    import robot_service.intelligence as module

    client, _, robot, headers, _, _ = system
    session = "missing-new-frame-0001"
    client.app.state.sessions[(robot["deviceId"], session)] = {
        "at": time.monotonic(),
        "history": [],
        "vision": {"resolved": True, "objects": ["勺子"], "text": "勺子"},
    }

    def unexpected_model(*args, **kwargs):
        raise AssertionError("没有新画面不能沿用上一次勺子回答")

    monkeypatch.setattr(module.httpx, "AsyncClient", unexpected_model)
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={"sessionId": session, "text": "看看现在", "visualRequest": True},
    )
    assert "没有清晰的新画面" in response.json()["text"]
    response = client.post(
        "/v1/turns",
        headers=headers,
        json={"sessionId": session, "text": "刚才那个怎么用"},
    )
    assert "没有清晰的新画面" in response.json()["text"]


def test_visual_output_rejects_unstructured_or_unbounded_answers():
    import pytest
    from robot_service.vision import render

    with pytest.raises(ValueError):
        render("我猜是书架", "这是什么")
    with pytest.raises(ValueError):
        render(
            json.dumps(
                {
                    "objectCount": 4,
                    "objects": ["物品"] * 5,
                    "unclear": False,
                    "needsClarification": False,
                    "answer": "物品",
                    "generalUse": "",
                }
            ),
            "这是什么",
        )
    assert "没看清" in render(
        json.dumps(
            {
                "objectCount": 0,
                "objects": [],
                "unclear": True,
                "needsClarification": False,
                "answer": "",
                "generalUse": "",
            }
        ),
        "这是什么",
    )

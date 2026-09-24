import httpx
import pytest


@pytest.mark.parametrize(
    "question,raw,expected",
    [
        (
            "请介绍小兔子。",
            "Hello, I am Rabbit.  \nI have long ears.",
            "Hello, I am Rabbit.I have long ears.",
        ),
        (
            "用英语和我打个招呼，再告诉我中文意思。",
            "Hello, how are you? 你好，你怎么样？",
            "Hello, how are you? 你好，你怎么样？",
        ),
        (
            "Teach me to greet someone in English.",
            "How are you? 你好吗？Want to learn more?",
            "How are you? 你好吗？",
        ),
        ("苹果用英语怎么说？", "Apple 是苹果。你想继续学吗？", "Apple 是苹果。"),
        ("苹果用英语怎么说？", "Apple. Want to learn more?", "Apple."),
        ("苹果是什么？", "苹果是一种水果。你喜欢吗？", "苹果是一种水果。"),
        ("我不想学英语。", "How are you? 你好吗？", None),
        ("今天好吗？", "想继续吗？还要听吗？", None),
        ("今天好吗？", "***\n###", None),
        ("今天好吗？", "。。。", None),
    ],
)
def test_quiet_reply_preserves_requested_teaching_but_never_returns_empty(
    system, monkeypatch, question, raw, expected
):
    import robot_service.intelligence as module

    client, _, robot, headers, _, _ = system

    class LocalClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, url, **kwargs):
            return httpx.Response(
                200,
                json={"message": {"content": raw}},
                request=httpx.Request("POST", url),
            )

    monkeypatch.setattr(module.httpx, "AsyncClient", LocalClient)
    session = "quiet-reply-regression"
    response = client.post(
        "/v1/turns", headers=headers, json={"sessionId": session, "text": question}
    )
    if expected is None:
        assert response.status_code == 503
        assert response.json()["detail"] == "没有生成可朗读回答"
        assert (robot["deviceId"], session) not in client.app.state.sessions
    else:
        assert response.status_code == 200
        assert response.json()["text"] == expected

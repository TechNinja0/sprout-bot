import asyncio
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx


def test_text_model_alone_does_not_advertise_visual_capability(system, monkeypatch):
    import robot_service.intelligence as module

    client, _, _, headers, _, _ = system

    class LocalClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def get(self, url):
            return httpx.Response(200, json={"models": [{"name": "qwen3.5:2b"}]})

    monkeypatch.setattr(module.httpx, "AsyncClient", LocalClient)
    result = client.get("/v1/models", headers=headers).json()
    assert result["llm"] is True
    assert result["vlm"] is False
    assert result["vlmModel"] == "qwen3.5:4b"


def test_preload_warms_actual_vision_encoder_with_generated_image(monkeypatch):
    import robot_service.preload as module

    calls = []

    class LocalClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, url, **kwargs):
            calls.append(kwargs["json"])
            return httpx.Response(200, request=httpx.Request("POST", url))

    monkeypatch.setattr(module.httpx, "AsyncClient", LocalClient)

    async def run():
        state = SimpleNamespace(
            store=SimpleNamespace(root=Path("unused")),
            tts_slots=asyncio.Semaphore(1),
            model_slots=asyncio.Semaphore(1),
            dialogue_slots=asyncio.Semaphore(1),
            tts_worker=SimpleNamespace(
                run=AsyncMock(return_value={"audio": "fixture"})
            ),
            speech_worker=SimpleNamespace(run=AsyncMock()),
        )
        await module.preload(SimpleNamespace(state=state))
        assert state.model_warmup == "ready"

    asyncio.run(run())
    assert [call["model"] for call in calls] == ["qwen3.5:2b", "qwen3.5:4b"]
    assert "images" not in calls[0]["messages"][0]
    assert calls[1]["messages"][0]["images"]

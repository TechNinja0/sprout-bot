"""可选启动预热；本机原创短句，不读取家庭资料。"""

import asyncio
import base64
import io
import os

import httpx
from PIL import Image

from .local_models import TEXT_MODEL, VISION_MODEL


async def preload(app):
    app.state.model_warmup = "warming"
    try:
        model_root = os.environ.get(
            "ROBOT_MODELS", str(app.state.store.root / "models")
        )
        async with app.state.tts_slots:
            audio = await app.state.tts_worker.run(
                {
                    "kind": "tts",
                    "root": model_root,
                    "text": "你好，小伙伴。",
                    "voice": {},
                },
                90,
            )
        async with app.state.model_slots:
            await app.state.speech_worker.run(
                {
                    "kind": "warm",
                    "root": model_root,
                    "audio": audio["audio"],
                },
                90,
            )
        async with app.state.dialogue_slots:
            async with httpx.AsyncClient(trust_env=False, timeout=60) as client:
                picture = io.BytesIO()
                Image.new("RGB", (64, 64), "red").save(picture, "PNG")
                for model, message in (
                    (TEXT_MODEL, {"role": "user", "content": "你好"}),
                    (
                        VISION_MODEL,
                        {
                            "role": "user",
                            "content": "这是什么颜色？",
                            "images": [base64.b64encode(picture.getvalue()).decode()],
                        },
                    ),
                ):
                    response = await asyncio.wait_for(
                        client.post(
                            "http://127.0.0.1:11435/api/chat",
                            json={
                                "model": model,
                                "stream": False,
                                "think": False,
                                "keep_alive": "30m",
                                "options": {"num_ctx": 4096, "num_predict": 8},
                                "messages": [message],
                            },
                        ),
                        60,
                    )
                    response.raise_for_status()
        app.state.model_warmup = "ready"
    except (RuntimeError, OSError, ValueError, TimeoutError, httpx.HTTPError):
        app.state.model_warmup = "failed"

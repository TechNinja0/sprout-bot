import asyncio
import os
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from .audio_preparation import AudioPreparation
from .audio_preparation import router as audio_preparation_router
from .auth import router as auth_router
from .companion import router as companion_router
from .diagnostics import router as diagnostics_router
from .imports import router as imports_router
from .intelligence import router as intelligence_router
from .keywords import router as keywords_router
from .knowledge import Knowledge
from .knowledge import router as knowledge_router
from .library import router as library_router
from .management import router as management_router
from .preload import preload
from .priority_slot import PrioritySlot
from .store import StorageCapacityError, Store
from .tts import worker_python
from .worker_channel import SpeechWorker


class BoundedBody:
    def __init__(self, app, limit=33 * 1024 * 1024):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        try:
            length = int(headers.get(b"content-length", b"0"))
        except ValueError:
            length = self.limit + 1
        if length > self.limit:
            return await JSONResponse(
                {"detail": "请求超过33MB限制（文件最多32MB）"}, 413
            )(scope, receive, send)
        # 有界读取，确保块传输同样受限；不记录请求体。
        chunks = []
        size = 0
        while True:
            msg = await receive()
            if msg["type"] == "http.disconnect":
                return
            size += len(msg.get("body", b""))
            if size > self.limit:
                return await JSONResponse(
                    {"detail": "请求超过33MB限制（文件最多32MB）"}, 413
                )(scope, receive, send)
            chunks.append(msg)
            if not msg.get("more_body", False):
                break

        async def replay():
            if chunks:
                return chunks.pop(0)
            return await receive()

        await self.app(scope, replay, send)


def create_app(root: Path | str = "runtime"):
    @asynccontextmanager
    async def lifespan(app):
        with app.state.store.transaction() as db:
            db.execute(
                "UPDATE jobs SET state='interrupted',error='服务重启，请重试' WHERE state IN ('queued','processing')"
            )
        app.state.import_pool = ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="import"
        )
        warmup = (
            asyncio.create_task(preload(app))
            if os.environ.get("ROBOT_PRELOAD_MODELS") == "1"
            else None
        )

        async def expire_history():
            from .companion import purge

            while True:
                for row in app.state.store.read("SELECT robot_id FROM configs"):
                    await asyncio.to_thread(purge, app.state.store, row["robot_id"])
                await asyncio.sleep(60)

        app.state.audio_preparation.recover()
        preparation = (
            asyncio.create_task(app.state.audio_preparation.run())
            if os.environ.get("ROBOT_PREPARE_AUDIO", "1") != "0"
            else None
        )
        history_cleanup = asyncio.create_task(expire_history())
        yield
        history_cleanup.cancel()
        await asyncio.gather(history_cleanup, return_exceptions=True)
        if warmup is not None:
            warmup.cancel()
            await asyncio.gather(warmup, return_exceptions=True)
        if preparation is not None:
            preparation.cancel()
            await asyncio.gather(preparation, return_exceptions=True)
        await app.state.audio_preparation.close()
        await app.state.speech_worker.close()
        await app.state.library_worker.close()
        await app.state.tts_worker.close()
        app.state.import_pool.shutdown(wait=True, cancel_futures=True)

    app = FastAPI(
        lifespan=lifespan,
        title="Family Robot Local API",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
    )
    app.state.store = Store(Path(root))
    app.state.knowledge = Knowledge(app.state.store)
    app.state.speech_worker = SpeechWorker()
    app.state.model_slots = asyncio.Semaphore(1)
    app.state.library_worker = SpeechWorker()
    app.state.library_slots = asyncio.Semaphore(1)
    # 试听、回答、资源朗读共用一个 TTS 实例，避免在 16GB Mac 上重复加载大模型。
    app.state.tts_worker = SpeechWorker("robot_service.tts_worker", worker_python())
    app.state.tts_slots = PrioritySlot()
    app.state.audio_preparation = AudioPreparation(app)
    app.state.dialogue_slots = asyncio.Semaphore(1)
    app.state.sessions = {}
    app.state.debug_sessions = {}
    app.state.games = {}
    app.state.memory_epoch = "initial"
    app.state.memory_referents = {}
    app.state.model_warmup = "disabled"
    app.add_middleware(BoundedBody)
    app.include_router(audio_preparation_router)
    app.include_router(keywords_router)
    app.include_router(knowledge_router)
    app.include_router(intelligence_router)
    app.include_router(auth_router)
    app.include_router(companion_router)
    app.include_router(library_router)
    app.include_router(imports_router)
    app.include_router(management_router)
    app.include_router(diagnostics_router)

    @app.get("/health")
    def health():
        return {
            "ok": True,
            "protocolVersion": 1,
            "serviceId": app.state.store.meta("service_id"),
            "serverTime": time.time(),
            "modelWarmup": app.state.model_warmup,
        }

    @app.exception_handler(Exception)
    async def internal_error(request, exc):
        # 向客户端提供固定错误，不泄漏文件路径、令牌或输入内容。
        return JSONResponse({"detail": "服务处理失败，请检查本地诊断"}, 500)

    @app.exception_handler(StorageCapacityError)
    async def storage_full(request, exc):
        return JSONResponse({"detail": str(exc)}, 507)

    return app

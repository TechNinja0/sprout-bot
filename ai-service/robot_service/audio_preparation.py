"""发布音频的持久任务与按需合成共用缓存；一个服务进程持有一个 TTS 实例。"""

import asyncio
import logging
import time

from fastapi import APIRouter, Depends, HTTPException, Request

from .auth import parent, principal
from .schemas import Voice
from .store import StorageCapacityError
from .tts import LEGACY_PROFILE, render_profile

router = APIRouter(prefix="/v1")
log = logging.getLogger(__name__)


def audio_segments(body):
    from .library import scope_notice

    notice = scope_notice(body)
    return ([{**notice, "pronunciation": {}}] if notice else []) + (
        [] if body.get("audioAsset") else body["segments"]
    )


def enqueue(db, rid, revision, body):
    """与发布使用同一个事务：已发布就一定有任务；重复发布回执不重复排队。"""
    total = len(audio_segments(body))
    db.execute(
        "UPDATE audio_jobs SET state='cancelled',updated=? WHERE resource_id=? "
        "AND revision_id!=? AND state IN ('queued','processing','failed')",
        (time.time(), rid, revision),
    )
    db.execute(
        "INSERT OR IGNORE INTO audio_jobs "
        "(revision_id,resource_id,state,total,completed,attempts,retry_at,error,updated) "
        "VALUES(?,?,?,?,0,0,0,'',?)",
        (revision, rid, "queued" if total else "ready", total, time.time()),
    )


class AudioPreparation:
    def __init__(self, app):
        self.app = app
        self.store = app.state.store
        self.inflight = {}

    def path(self, revision, segment):
        return self.store.root / "audio" / revision / f"{segment}.wav"

    def cached(self, revision, segment):
        path = self.path(revision, segment)
        return path.is_file() and path.stat().st_size > 0

    def reconcile(self, rid, revision, body):
        total = len(audio_segments(body))
        completed = sum(self.cached(revision, s["id"]) for s in audio_segments(body))
        with self.store.transaction() as db:
            db.execute(
                "UPDATE audio_jobs SET completed=?,total=?,"
                "state=CASE WHEN ?=? THEN 'ready' ELSE state END,"
                "error=CASE WHEN ?=? THEN '' ELSE error END,updated=? "
                "WHERE revision_id=? AND resource_id=? AND state!='cancelled'",
                (
                    completed,
                    total,
                    completed,
                    total,
                    completed,
                    total,
                    time.time(),
                    revision,
                    rid,
                ),
            )
        return completed, total

    def recover(self):
        """启动时补旧书、恢复中断任务，并按实际落盘文件修正进度。"""
        import json

        with self.store.transaction() as db:
            db.execute(
                "UPDATE audio_jobs SET state='queued',retry_at=0 WHERE state='processing'"
            )
            db.execute(
                "UPDATE audio_jobs SET state='cancelled' WHERE NOT EXISTS "
                "(SELECT 1 FROM resources r JOIN revisions v ON v.id=r.published_id "
                "WHERE r.status='published' AND v.revoked=0 "
                "AND r.id=audio_jobs.resource_id AND v.id=audio_jobs.revision_id)"
            )
            rows = db.execute(
                "SELECT r.id,v.id AS revision,v.body FROM resources r "
                "JOIN revisions v ON v.id=r.published_id "
                "WHERE r.status='published' AND v.revoked=0"
            ).fetchall()
            for row in rows:
                enqueue(db, row["id"], row["revision"], json.loads(row["body"]))
        for row in rows:
            completed, total = self.reconcile(
                row["id"], row["revision"], json.loads(row["body"])
            )
            if completed < total:
                with self.store.transaction() as db:
                    db.execute(
                        "UPDATE audio_jobs SET state='queued',retry_at=0 "
                        "WHERE revision_id=? AND state='ready'",
                        (row["revision"],),
                    )

    async def ensure(self, request, rid, revision, segment, *, bulk=False):
        from .library import published

        _, rev = published(self.store, rid, revision)
        body = rev["body"]
        seg = next((s for s in audio_segments(body) if s["id"] == segment), None)
        if not seg:
            raise HTTPException(404, "段落不存在")
        if not self.cached(revision, segment):
            key = (revision, segment)
            task = self.inflight.get(key)
            if task is None:
                task = asyncio.create_task(
                    self._render(request, rid, revision, seg, body, bulk)
                )
                self.inflight[key] = task

                def finished(done):
                    self.inflight.pop(key, None)
                    if not done.cancelled():
                        done.exception()  # 请求已断开时也收集异常，避免泄漏后台 task。

                task.add_done_callback(finished)
            # 一个听众取消不会取消其他听众或后台任务共用的合成。
            await asyncio.shield(task)
        with self.store.content_lock:
            published(self.store, rid, revision)
            return self.path(revision, segment).read_bytes()

    async def _render(self, request, rid, revision, seg, body, bulk):
        from .intelligence import speech
        from .library import published

        if body.get("ttsProfile", LEGACY_PROFILE) != render_profile():
            raise HTTPException(
                409, "此版本使用其他语音引擎；请重新发布或恢复原引擎后重试"
            )
        text = seg["text"]
        for src, dst in sorted(
            seg.get("pronunciation", {}).items(), key=lambda x: -len(x[0])
        ):
            if src:
                text = text.replace(src, dst)
        audio = await speech(
            request,
            text,
            Voice.model_validate(body["voice"]),
            True,
            background=True,
            bulk=bulk,
        )
        # 与下架的状态变更互斥；不会在删除后重新创建音频目录。
        with self.store.content_lock:
            published(self.store, rid, revision)
            self.store.write_content(self.path(revision, seg["id"]), audio)
            self.reconcile(rid, revision, body)

    async def run_once(self):
        from .library import published

        row = self.store.one(
            "SELECT * FROM audio_jobs WHERE state='queued' AND retry_at<=? "
            "ORDER BY updated LIMIT 1",
            (time.time(),),
        )
        if not row:
            return False
        rid, revision = row["resource_id"], row["revision_id"]
        try:
            resource, rev = published(self.store, rid, revision)
            if resource["published_id"] != revision:
                raise HTTPException(410, "已有新的发布版本")
            body = rev["body"]
            with self.store.transaction() as db:
                db.execute(
                    "UPDATE audio_jobs SET state='processing' WHERE revision_id=? AND state='queued'",
                    (revision,),
                )
            completed, total = self.reconcile(rid, revision, body)
            if completed != total:
                seg = next(
                    s
                    for s in audio_segments(body)
                    if not self.cached(revision, s["id"])
                )
                await self.ensure(
                    Request({"type": "http", "app": self.app}),
                    rid,
                    revision,
                    seg["id"],
                    bulk=True,
                )
            with self.store.transaction() as db:
                db.execute(
                    "UPDATE audio_jobs SET state='queued',attempts=0,retry_at=0,error='',updated=? "
                    "WHERE revision_id=? AND state='processing'",
                    (time.time(), revision),
                )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            status = exc.status_code if isinstance(exc, HTTPException) else 503
            busy = status == 429
            attempts = row["attempts"] + (0 if busy else 1)
            permanent = status in (400, 404, 409, 410, 422) or isinstance(
                exc, StorageCapacityError
            )
            state = "failed" if permanent or attempts >= 5 else "queued"
            if status in (404, 410):
                state = "cancelled"
            error = (
                str(exc.detail)
                if isinstance(exc, HTTPException)
                else (
                    str(exc)
                    if isinstance(exc, StorageCapacityError)
                    else "音频生成失败，请稍后重试"
                )
            )
            with self.store.transaction() as db:
                db.execute(
                    "UPDATE audio_jobs SET state=?,attempts=?,retry_at=?,error=?,updated=? "
                    "WHERE revision_id=? AND state IN ('queued','processing')",
                    (
                        state,
                        attempts,
                        time.time()
                        + (2 if busy else min(300, 5 * 2 ** min(attempts, 6))),
                        error[:200],
                        time.time(),
                        revision,
                    ),
                )
        return True

    async def run(self):
        while True:
            try:
                await self.run_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("音频后台任务暂时不可用")
            # 一次只做一段并让出执行机会，发布/对话不会被整本书阻塞。
            await asyncio.sleep(1)

    async def close(self):
        tasks = list(self.inflight.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.inflight.clear()


def status_payload(store, rid, revision):
    row = store.one(
        "SELECT * FROM audio_jobs WHERE resource_id=? AND revision_id=?",
        (rid, revision),
    )
    if not row:
        return {
            "revisionId": revision,
            "state": "queued",
            "completed": 0,
            "total": 0,
            "error": "",
        }
    return {
        "revisionId": revision,
        "state": row["state"],
        "completed": row["completed"],
        "total": row["total"],
        "error": row["error"],
    }


@router.get("/resources/{rid}/audio-preparation")
def preparation_status(rid: str, request: Request, user=Depends(principal)):
    from .library import published

    _, revision = published(request.app.state.store, rid)
    return status_payload(request.app.state.store, rid, revision["id"])


@router.post("/resources/{rid}/audio-preparation/retry")
def retry_preparation(
    rid: str, revisionId: str, request: Request, user=Depends(parent)
):
    from .library import published

    store = request.app.state.store
    with store.content_lock, store.transaction() as db:
        resource, revision = published(store, rid, revisionId)
        if resource["published_id"] != revisionId:
            raise HTTPException(409, "发布版本已变化，请刷新后重试")
        enqueue(db, rid, revisionId, revision["body"])
        db.execute(
            "UPDATE audio_jobs SET state='queued',attempts=0,retry_at=0,error='',updated=? "
            "WHERE revision_id=? AND state='failed'",
            (time.time(), revisionId),
        )
    return status_payload(store, rid, revisionId)

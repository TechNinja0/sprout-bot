import io
import json
import time
import zipfile

from fastapi import APIRouter, Depends, File, Header, Request, UploadFile
from fastapi.responses import Response
from pydantic import Field

from .auth import fail, parent, principal, robot
from .library import published
from .schemas import Memory, MemoryDecision, Strict
from .store import bump_catalog, digest, dumps, uid

router = APIRouter(prefix="/v1")


def record_memory_action(db, action, count):
    row = db.execute("SELECT value FROM meta WHERE key='memory_actions'").fetchone()
    items = json.loads(row[0]) if row else []
    items.append(
        {"id": uid(), "action": action, "count": count, "created": time.time()}
    )
    db.execute(
        "INSERT OR REPLACE INTO meta VALUES('memory_actions',?)", (dumps(items[-20:]),)
    )


@router.get("/memory-actions")
def memory_actions(request: Request, user=Depends(parent)):
    row = request.app.state.store.one(
        "SELECT value FROM meta WHERE key='memory_actions'"
    )
    return list(reversed(json.loads(row["value"]))) if row else []


def forget_related(request, target_id=None):
    """只有紧邻的显式记忆候选可确定指代；不确定时停用，不删除无关偏好。"""
    with request.app.state.store.transaction() as db:
        target = (
            db.execute(
                "SELECT id FROM memories WHERE id=? AND state!='deleted'", (target_id,)
            ).fetchone()
            if target_id
            else None
        )
        if target:
            db.execute(
                "UPDATE memories SET body='{}',state='deleted',updated=? WHERE id=?",
                (time.time(), target_id),
            )
            db.execute(
                "INSERT OR REPLACE INTO tombstones VALUES(?,?,?)",
                (target_id, "memory", time.time()),
            )
            action, count = "related_deleted", 1
        else:
            # suspended不参与approved检索；即使旧服务读取该库也不会使用，恢复亦需重新审核。
            count = db.execute(
                "UPDATE memories SET state='suspended',updated=? WHERE state IN ('approved','pending')",
                (time.time(),),
            ).rowcount
            action = "preferences_suspended"
        record_memory_action(db, action, count)
    request.app.state.memory_epoch = uid()
    request.app.state.sessions.clear()
    request.app.state.memory_referents.clear()
    return action


@router.get("/memories")
def memories(request: Request, user=Depends(parent)):
    rows = request.app.state.store.read(
        "SELECT * FROM memories WHERE state!='deleted' ORDER BY updated DESC"
    )
    for row in rows:
        row["body"] = json.loads(row["body"])
    return rows


@router.post("/memories")
def memory_candidate(body: Memory, request: Request, user=Depends(principal)):
    mid = uid()
    value = body.model_dump()
    value["sourceTime"] = time.time()
    # 仅显式候选，普通对话不记录逐句文本；审核权只在家长。
    with request.app.state.store.transaction() as db:
        db.execute(
            "INSERT INTO memories VALUES(?,?,?,?)",
            (mid, dumps(value), "pending", time.time()),
        )
    return {"id": mid, "state": "pending"}


@router.put("/memories/{mid}")
def memory_review(
    mid: str, body: MemoryDecision, request: Request, user=Depends(parent)
):
    store = request.app.state.store
    with store.transaction() as db:
        row = db.execute(
            "SELECT * FROM memories WHERE id=? AND state!='deleted'", (mid,)
        ).fetchone()
        if not row:
            fail(404, "记忆不存在")
        value = json.loads(row["body"])
        if body.content is not None:
            value["content"] = body.content
        if body.expires is not None:
            value["expires"] = body.expires
        value["allowCloud"] = body.allowCloud
        if body.state == "deleted":
            value = {}
            db.execute(
                "INSERT OR REPLACE INTO tombstones VALUES(?,?,?)",
                (mid, "memory", time.time()),
            )
        db.execute(
            "UPDATE memories SET body=?,state=?,updated=? WHERE id=?",
            (dumps(value), body.state, time.time(), mid),
        )
    request.app.state.memory_epoch = uid()
    request.app.state.sessions.clear()
    request.app.state.memory_referents.clear()
    return {"state": body.state}


@router.post("/memories/forget")
def forget(request: Request, user=Depends(robot)):
    # 此接口只用于明确要求遗忘全部；“不要记这个”走单项/停用流程。
    store = request.app.state.store
    with store.transaction() as db:
        rows = db.execute("SELECT id FROM memories WHERE state!='deleted'").fetchall()
        for row in rows:
            db.execute(
                "INSERT OR REPLACE INTO tombstones VALUES(?,?,?)",
                (row["id"], "memory", time.time()),
            )
        db.execute(
            "UPDATE memories SET body='{}',state='deleted',updated=?", (time.time(),)
        )
        record_memory_action(db, "all_deleted", len(rows))
    request.app.state.memory_epoch = uid()
    request.app.state.sessions.clear()
    request.app.state.memory_referents.clear()
    return {"state": "deleted", "scope": "all_preferences"}


class Playlist(Strict):
    name: str = Field(min_length=1, max_length=100)
    resources: list[str] = Field(default_factory=list, max_length=100)


@router.get("/playlists")
def playlists(request: Request, user=Depends(principal)):
    store = request.app.state.store
    result = []
    for row in store.read("SELECT * FROM playlists"):
        ids = json.loads(row["resources"])
        if user["role"] == "robot":
            ids = [
                x
                for x in ids
                if store.one(
                    "SELECT 1 FROM resources WHERE id=? AND status='published'", (x,)
                )
            ]
        result.append({**row, "resources": ids})
    return result


@router.post("/playlists")
def add_playlist(body: Playlist, request: Request, user=Depends(parent)):
    store = request.app.state.store
    pid = uid()
    with store.transaction() as db:
        for rid in body.resources:
            if not db.execute(
                "SELECT 1 FROM resources WHERE id=? AND status!='deleted'", (rid,)
            ).fetchone():
                fail(422, "清单包含无效资源")
        db.execute(
            "INSERT INTO playlists VALUES(?,?,?)",
            (pid, body.name, dumps(body.resources)),
        )
    return {"id": pid}


@router.put("/playlists/{pid}")
def update_playlist(pid: str, body: Playlist, request: Request, user=Depends(parent)):
    """Preserve playlist identity so scheduled plans keep referencing the edited list."""
    with request.app.state.store.transaction() as db:
        if not db.execute("SELECT 1 FROM playlists WHERE id=?", (pid,)).fetchone():
            fail(404, "清单不存在")
        for rid in body.resources:
            if not db.execute(
                "SELECT 1 FROM resources WHERE id=? AND status='published'", (rid,)
            ).fetchone():
                fail(422, "请选择已发布资源")
        db.execute("UPDATE playlists SET name=?,resources=? WHERE id=?",
                   (body.name, dumps(body.resources), pid))
    return {"id": pid}


@router.delete("/playlists/{pid}")
def remove_playlist(pid: str, request: Request, user=Depends(parent)):
    with request.app.state.store.transaction() as db:
        db.execute("DELETE FROM playlists WHERE id=?", (pid,))
    return {"deleted": True}


class Usage(Strict):
    day: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    seconds: int = Field(ge=0, le=86400)
    seq: int = Field(ge=0)


@router.post("/usage")
def usage(body: Usage, request: Request, user=Depends(robot)):
    with request.app.state.store.transaction() as db:
        old = db.execute(
            "SELECT seq,seconds FROM usage WHERE robot_id=? AND day=?",
            (user["id"], body.day),
        ).fetchone()
        if old and (old["seq"] >= body.seq or old["seconds"] > body.seconds):
            return {"accepted": False}
        db.execute(
            "INSERT OR REPLACE INTO usage VALUES(?,?,?,?)",
            (user["id"], body.day, body.seq, body.seconds),
        )
    return {"accepted": True}


@router.get("/usage")
def usage_summary(request: Request, user=Depends(parent)):
    return request.app.state.store.read(
        "SELECT day,seconds FROM usage WHERE robot_id=? ORDER BY day DESC LIMIT 90",
        (user["robot_id"],),
    )


@router.get("/usage/summary")
def family_summary(request: Request, user=Depends(parent)):
    store = request.app.state.store
    device = store.one(
        "SELECT status,last_seen FROM devices WHERE id=?", (user["robot_id"],)
    )
    status = json.loads(device["status"]) if device else {}
    return {
        "days": usage_summary(request, user),
        "readCategories": store.read(
            "SELECT r.kind,COUNT(*) AS resources FROM progress p JOIN resources r ON r.id=p.resource_id "
            "WHERE p.robot_id=? AND p.offset_ms>0 AND r.status!='deleted' GROUP BY r.kind",
            (user["robot_id"],),
        ),
        "pendingMemories": store.one(
            "SELECT COUNT(*) AS n FROM memories WHERE state IN ('pending','suspended')"
        )["n"],
        "serviceFailures": status.get("serviceFailures", {}),
        "lastSeen": device["last_seen"] if device else 0,
    }


class DownloadState(Strict):
    revisionId: str
    state: str = Field(pattern="^(downloaded|downloading|failed|removed)$")


@router.put("/resources/{rid}/download")
def download_state(
    rid: str, body: DownloadState, request: Request, user=Depends(robot)
):
    store = request.app.state.store
    if body.state != "removed":
        published(store, rid, body.revisionId)
    with store.transaction() as db:
        db.execute(
            "INSERT OR REPLACE INTO downloads VALUES(?,?,?,?,?)",
            (user["id"], rid, body.revisionId, body.state, time.time()),
        )
    return {"state": body.state}


@router.get("/downloads")
def downloads(request: Request, user=Depends(principal)):
    rid = user["id"] if user["role"] == "robot" else user["robot_id"]
    return request.app.state.store.read(
        "SELECT * FROM downloads WHERE robot_id=?", (rid,)
    )


def export_library(store):
    tables = ("resources", "revisions", "assets", "memories", "playlists", "tombstones")
    # 读取一致快照，不含安装凭证、配置秘密、会话音视频。
    with store.transaction() as db:
        data = {t: [dict(r) for r in db.execute("SELECT * FROM " + t)] for t in tables}
        estimated = sum(a["size"] for a in data["assets"]) + len(dumps(data).encode())
        if estimated > 512 * 1024 * 1024:
            fail(413, "单份资源备份超过512MB，请使用停服完整数据目录备份")
        buffer = io.BytesIO()
        hashes = {}
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for asset in data["assets"]:
                path = store.root / "assets" / asset["id"]
                if not path.exists():
                    raise ValueError("原稿缺失，不能创建完整备份")
                raw = path.read_bytes()
                if digest(raw) != asset["hash"]:
                    raise ValueError("原稿校验失败")
                name = "assets/" + asset["id"]
                hashes[name] = digest(raw)
                archive.writestr(name, raw)
            raw = dumps(data).encode()
            hashes["data.json"] = digest(raw)
            archive.writestr("data.json", raw)
            archive.writestr(
                "manifest.json",
                dumps(
                    {
                        "version": 1,
                        "created": time.time(),
                        "hashes": hashes,
                        "audioIncluded": False,
                    }
                ),
            )
    return buffer.getvalue()


def restore_library(store, raw):
    from .schemas import ResourceDraft

    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        if len(infos) > 5000 or sum(i.file_size for i in infos) > 512 * 1024 * 1024:
            raise ValueError("备份超限")
        if len({i.filename for i in infos}) != len(infos):
            raise ValueError("重复文件")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest["version"] != 1:
            raise ValueError("备份版本不支持")
        names = set(archive.namelist())
        if names != {"manifest.json", *manifest["hashes"]}:
            raise ValueError("未声明备份文件")
        payloads = {}
        for name, hash_value in manifest["hashes"].items():
            if name != "data.json" and not __import__("re").fullmatch(
                r"assets/[a-f0-9]{32}", name
            ):
                raise ValueError("非法备份路径")
            payloads[name] = archive.read(name)
            if digest(payloads[name]) != hash_value:
                raise ValueError("备份校验失败")
        data = json.loads(payloads["data.json"])
        for res in data["resources"]:
            if res["status"] != "deleted":
                ResourceDraft.model_validate_json(res["draft"])
        for asset in data["assets"]:
            if digest(payloads["assets/" + asset["id"]]) != asset["hash"]:
                raise ValueError("素材哈希不匹配")
        # 文件写入与数据库失败一起回滚，不遗留占用容量的孤立恢复素材。
        # 永不覆盖现有资源，恢复时创建独立草稿；墓碑ID跳过，避免旧备份复活。
        with store.new_content_batch() as created_files, store.transaction() as db:
            blocked = {r[0] for r in db.execute("SELECT id FROM tombstones")}
            blocked.update(r["id"] for r in data["tombstones"])
            restored = []
            for res in data["resources"]:
                if res["id"] in blocked or res["status"] == "deleted":
                    continue
                if db.execute(
                    "SELECT 1 FROM resources WHERE id=?", (res["id"],)
                ).fetchone():
                    continue
                draft = json.loads(res["draft"])
                draft["auditioned"] = False
                for page in draft["pages"]:
                    page["reviewed"] = False
                db.execute(
                    "INSERT INTO resources VALUES(?,?,?,?,?,?,?)",
                    (
                        res["id"],
                        res["kind"],
                        res["draft_version"] + 1,
                        dumps(draft),
                        None,
                        "draft",
                        time.time(),
                    ),
                )
                restored.append(res["id"])
            for asset in data["assets"]:
                if asset["resource_id"] not in restored:
                    continue
                # 确定名称由清单验证，绝不extractall不受信压缩包。
                target = store.root / "assets" / asset["id"]
                if target.exists() and digest(target.read_bytes()) != asset["hash"]:
                    raise ValueError("素材身份冲突")
                if not target.exists():
                    created_files.append(target)
                store.write_content(target, payloads["assets/" + asset["id"]])
                db.execute(
                    "INSERT INTO assets VALUES(?,?,?,?,?,?)",
                    tuple(
                        asset[k]
                        for k in (
                            "id",
                            "resource_id",
                            "hash",
                            "filename",
                            "media_type",
                            "size",
                        )
                    ),
                )
            for t in data["tombstones"]:
                db.execute(
                    "INSERT OR IGNORE INTO tombstones VALUES(?,?,?)",
                    (t["id"], t["kind"], t["created"]),
                )
            for m in data["memories"]:
                if m["id"] in blocked or m["state"] == "deleted":
                    continue
                if not db.execute(
                    "SELECT 1 FROM memories WHERE id=?", (m["id"],)
                ).fetchone():
                    value = Memory.model_validate(
                        {
                            k: v
                            for k, v in json.loads(m["body"]).items()
                            if k != "sourceTime"
                        }
                    ).model_dump()
                    value["allowCloud"] = False
                    db.execute(
                        "INSERT INTO memories VALUES(?,?,?,?)",
                        (m["id"], dumps(value), "pending", time.time()),
                    )
            for playlist in data.get("playlists", []):
                ids = json.loads(playlist["resources"])
                ids = [
                    rid
                    for rid in ids
                    if rid not in blocked
                    and db.execute(
                        "SELECT 1 FROM resources WHERE id=? AND status!='deleted'",
                        (rid,),
                    ).fetchone()
                ]
                if (
                    ids
                    and not db.execute(
                        "SELECT 1 FROM playlists WHERE id=?", (playlist["id"],)
                    ).fetchone()
                ):
                    validated = Playlist(name=playlist["name"], resources=ids)
                    db.execute(
                        "INSERT INTO playlists VALUES(?,?,?)",
                        (playlist["id"], validated.name, dumps(validated.resources)),
                    )
            bump_catalog(db)
    return {
        "restoredDrafts": len(restored),
        "audio": "regenerate",
        "credentials": "not_restored",
    }


@router.get("/backup")
def backup(request: Request, user=Depends(parent)):
    return Response(
        export_library(request.app.state.store),
        media_type="application/zip",
        headers={
            "Content-Disposition": 'attachment; filename="family-library.zip"',
            "Cache-Control": "no-store",
        },
    )


@router.post("/backup/restore")
async def restore(
    request: Request,
    file: UploadFile = File(...),
    x_recovery: str = Header(default=""),
    user=Depends(parent),
):
    store = request.app.state.store
    record = store.one("SELECT value FROM meta WHERE key='recovery_hash'")
    import secrets

    if not record or not secrets.compare_digest(record["value"], digest(x_recovery)):
        fail(403, "恢复需要本地管理员恢复凭据")
    raw = await file.read(32 * 1024 * 1024 + 1)
    if len(raw) > 32 * 1024 * 1024:
        fail(413, "手机恢复限制32MB；更大备份使用本地工具")
    try:
        return restore_library(store, raw)
    except (ValueError, KeyError, zipfile.BadZipFile):
        fail(422, "备份结构或校验无效")

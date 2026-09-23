import json
import re
import time
import unicodedata

from fastapi import APIRouter, Depends, Request

from .auth import fail, parent, principal, robot
from .schemas import (
    Lookup,
    Progress,
    Publish,
    ResourceCreate,
    ResourceDraft,
    ResourceEdit,
)
from .store import bump_catalog, digest, dumps, uid
from .tts import LEGACY_PROFILE, render_profile

router = APIRouter(prefix="/v1")


def normalize(text):
    return "".join(
        c for c in unicodedata.normalize("NFKC", text).casefold() if c.isalnum()
    )


def get_resource(store, rid, allow_deleted=False):
    row = store.one("SELECT * FROM resources WHERE id=?", (rid,))
    if not row or (row["status"] == "deleted" and not allow_deleted):
        fail(404, "资源不存在")
    row["draft"] = json.loads(row["draft"])
    return row


def page_order_warnings(pages):
    """仅对家长给定的明确数字印刷页码做提示，不猜页码或自动补正文。"""
    warnings = []
    previous = None
    for index, page in enumerate(pages):
        match = re.fullmatch(
            r"(?:第\s*)?(\d{1,6})(?:\s*页)?", page.get("label", "").strip()
        )
        current = (int(match[1]), page.get("chapter", ""), index) if match else None
        if current and previous and current[1] == previous[1]:
            start, end = previous[0], current[0]
            if end > start + 1:
                warnings.append(
                    f"录入第{index + 1}项前，印刷页码从{start}跳到{end}；可能缺第{start + 1}—{end - 1}页，请核对原书及节选范围。"
                )
            elif end <= start:
                warnings.append(
                    f"录入第{index + 1}项的印刷页码{end}重复或逆序，请核对页序与章节。"
                )
        previous = current
    return warnings


def published(store, rid, revision_id=None):
    resource = get_resource(store, rid)
    if resource["status"] != "published" or not resource["published_id"]:
        fail(409, "资源未发布或已下架")
    revision = store.one(
        "SELECT * FROM revisions WHERE id=? AND resource_id=? AND revoked=0",
        (revision_id or resource["published_id"], rid),
    )
    if not revision:
        fail(409, "版本不可读")
    revision["body"] = json.loads(revision["body"])
    return resource, revision


def validate_assets(db, rid, draft):
    refs = [draft.coverAsset, draft.audioAsset] + [p.sourceAsset for p in draft.pages]
    for ref in refs:
        if (
            ref
            and not db.execute(
                "SELECT 1 FROM assets WHERE id=? AND resource_id=?", (ref, rid)
            ).fetchone()
        ):
            fail(422, "素材不属于本资源")
    pageids = [p.id for p in draft.pages]
    if len(set(pageids)) != len(pageids):
        fail(422, "页面ID重复")
    if draft.minAge > draft.maxAge:
        fail(422, "年龄范围错误")


def scope_notice(draft):
    if draft.get("complete", False):
        return None
    return draft.get("scopeNotice") or {
        "id": "scope-notice",
        "text": "这次读的是节选，已录入的范围是："
        + draft["excerpt"]
        + "。接下来开始读原文。",
    }


def segments(draft):
    result = []
    for page in draft["pages"]:
        if page["skip"]:
            continue
        # 固定小段，保留全部字符；原文和发音映射各自留存。
        text = page["text"]
        chunks = re.findall(r".{1,200}(?:[。！？.!?\n]|$)|.{1,200}", text, re.S)
        for index, chunk in enumerate(chunks):
            result.append(
                {
                    "id": digest(page["id"] + ":" + str(index) + ":" + chunk)[:24],
                    "pageId": page["id"],
                    "label": page["label"],
                    "chapter": page["chapter"],
                    "text": chunk,
                    "pronunciation": page["pronunciation"],
                    "language": draft["language"],
                }
            )
    return result


@router.post("/resources")
def create(body: ResourceCreate, request: Request, user=Depends(parent)):
    rid = uid()
    store = request.app.state.store
    with store.transaction() as db:
        validate_assets(db, rid, body.draft)
        db.execute(
            "INSERT INTO resources VALUES(?,?,?,?,?,?,?)",
            (
                rid,
                body.kind,
                1,
                dumps(body.draft.model_dump()),
                None,
                "draft",
                time.time(),
            ),
        )
    return get_resource(store, rid)


@router.get("/resources")
def listing(
    request: Request,
    q: str = "",
    kind: str = "",
    language: str = "",
    state: str = "",
    favorite: bool = False,
    user=Depends(principal),
):
    if len(q) > 200:
        fail(422, "查询太长")
    store = request.app.state.store
    result = []
    for row in store.read(
        "SELECT * FROM resources WHERE status!='deleted' ORDER BY updated DESC LIMIT 2000"
    ):
        if user["role"] == "robot":
            if row["status"] != "published":
                continue
            rev = store.one(
                "SELECT body FROM revisions WHERE id=? AND revoked=0",
                (row["published_id"],),
            )
            if not rev:
                continue
            draft = json.loads(rev["body"])
            row.pop("draft")
        else:
            draft = json.loads(row.pop("draft"))
        if kind and row["kind"] != kind:
            continue
        if language and draft["language"] != language:
            continue
        if state and row["status"] != state:
            continue
        if favorite and not draft["favorite"]:
            continue
        hay = normalize(
            " ".join(
                [draft["title"], *draft["aliases"], *draft["tags"], draft["author"]]
            )
        )
        if q and normalize(q) not in hay:
            continue
        result.append(
            {
                **row,
                "metadata": {
                    k: v for k, v in draft.items() if k not in ("pages", "segments")
                },
                "pageCount": len(draft["pages"]),
            }
        )
    return {"items": result, "catalogVersion": int(store.meta("catalog"))}


@router.get("/resources/{rid}")
def detail(rid: str, request: Request, user=Depends(parent)):
    row = get_resource(request.app.state.store, rid)
    row["pageOrderWarnings"] = page_order_warnings(row["draft"].get("pages", []))
    return row


@router.put("/resources/{rid}")
def edit(rid: str, body: ResourceEdit, request: Request, user=Depends(parent)):
    store = request.app.state.store
    with store.transaction() as db:
        row = db.execute(
            "SELECT * FROM resources WHERE id=? AND status!=?", (rid, "deleted")
        ).fetchone()
        if not row:
            fail(404, "资源不存在")
        if row["draft_version"] != body.expectedVersion:
            fail(409, "草稿版本冲突")
        validate_assets(db, rid, body.draft)
        payload = body.draft.model_dump()
        # 任何正文/声音/页序/完整范围改变必须重新试听；不能沿用旧复选框。
        old = json.loads(row["draft"])
        if any(
            old.get(k) != payload.get(k)
            for k in ("pages", "voice", "audioAsset", "complete", "excerpt")
        ):
            payload["auditioned"] = False
        db.execute(
            "UPDATE resources SET draft=?,draft_version=draft_version+1,updated=? WHERE id=?",
            (dumps(payload), time.time(), rid),
        )
    return get_resource(store, rid)


@router.post("/resources/{rid}/publish")
def publish(rid: str, body: Publish, request: Request, user=Depends(parent)):
    store = request.app.state.store
    with store.transaction() as db:
        owner = user["id"] + ":" + rid + ":" + str(body.expectedVersion)
        receipt = db.execute(
            "SELECT * FROM receipts WHERE request_id=?", (body.requestId,)
        ).fetchone()
        if receipt:
            if receipt["owner"] != owner:
                fail(409, "请求ID冲突")
            return json.loads(receipt["result"])
        row = db.execute(
            "SELECT * FROM resources WHERE id=? AND status!='deleted'", (rid,)
        ).fetchone()
        if not row:
            fail(404, "资源不存在")
        if row["draft_version"] != body.expectedVersion:
            fail(409, "草稿版本冲突")
        draft = ResourceDraft.model_validate_json(row["draft"])
        validate_assets(db, rid, draft)
        if not draft.auditioned:
            fail(422, "请先完成试听确认")
        if not draft.complete and not draft.excerpt.strip():
            fail(422, "需确认完整范围或填写节选范围")
        if not draft.audioAsset and not any(
            p.text.strip() and not p.skip for p in draft.pages
        ):
            fail(422, "没有可读正文或音频")
        if row["kind"] == "book" and not draft.pages:
            fail(422, "图书需有页面正文")
        if row["kind"] == "song" and not draft.audioAsset:
            fail(422, "儿歌需上传实际音频；文字朗读请使用故事或英语短句分类")
        if any(not p.reviewed for p in draft.pages):
            fail(422, "仍有未审核页面")
        snapshot = draft.model_dump()
        snapshot["segments"] = segments(snapshot)
        snapshot["scopeNotice"] = scope_notice(snapshot)
        snapshot["ttsProfile"] = render_profile()
        rev = uid()
        db.execute(
            "INSERT INTO revisions VALUES(?,?,?,?,0)",
            (rev, rid, dumps(snapshot), time.time()),
        )
        db.execute(
            "UPDATE resources SET published_id=?,status='published',updated=? WHERE id=?",
            (rev, time.time(), rid),
        )
        bump_catalog(db)
        result = {"resourceId": rid, "revisionId": rev, "state": "published"}
        db.execute(
            "INSERT INTO receipts VALUES(?,?,?)", (body.requestId, owner, dumps(result))
        )
    return result


@router.post("/resources/{rid}/unlist")
def unlist(rid: str, request: Request, user=Depends(parent)):
    store = request.app.state.store
    with store.transaction() as db:
        row = db.execute(
            "SELECT id FROM resources WHERE id=? AND status!='deleted'", (rid,)
        ).fetchone()
        if not row:
            fail(404, "资源不存在")
        db.execute(
            "UPDATE resources SET status='unlisted',updated=? WHERE id=?",
            (time.time(), rid),
        )
        db.execute("UPDATE revisions SET revoked=1 WHERE resource_id=?", (rid,))
        db.execute(
            "UPDATE downloads SET state='pending_removal' WHERE resource_id=?", (rid,)
        )
        db.execute(
            "INSERT OR REPLACE INTO tombstones VALUES(?,?,?)",
            (rid, "resource", time.time()),
        )
        bump_catalog(db)
    return {"state": "unlisted", "offline": "pending_sync"}


@router.delete("/resources/{rid}")
def delete(rid: str, request: Request, user=Depends(parent)):
    unlist(rid, request, user)
    store = request.app.state.store
    with store.transaction() as db:
        assets = db.execute(
            "SELECT id FROM assets WHERE resource_id=?", (rid,)
        ).fetchall()
        revs = db.execute(
            "SELECT id FROM revisions WHERE resource_id=?", (rid,)
        ).fetchall()
        db.execute(
            "UPDATE resources SET status='deleted',draft='{}',published_id=NULL WHERE id=?",
            (rid,),
        )
        db.execute("DELETE FROM revisions WHERE resource_id=?", (rid,))
        db.execute("DELETE FROM assets WHERE resource_id=?", (rid,))
        db.execute(
            "UPDATE jobs SET state='cancelled',result='{}' WHERE resource_id=?", (rid,)
        )
        db.execute("DELETE FROM progress WHERE resource_id=?", (rid,))
    for asset in assets:
        (store.root / "assets" / asset["id"]).unlink(missing_ok=True)
    # 缓存按revision隔离，避免删除共享资源。
    for rev in revs:
        import shutil

        shutil.rmtree(store.root / "audio" / rev["id"], ignore_errors=True)
    return {"state": "deleted", "offline": "pending_sync"}


@router.post("/books/lookup")
def lookup(body: Lookup, request: Request, user=Depends(principal)):
    store = request.app.state.store
    query = normalize(body.text)
    matches = []
    unreadable = []
    for row in store.read(
        "SELECT id,published_id,kind,status,draft FROM resources WHERE status!='deleted'"
    ):
        if row["kind"] != "book":
            continue
        rev = store.one(
            "SELECT body FROM revisions WHERE id=? AND revoked=0",
            (row["published_id"],),
        )
        readable = row["status"] == "published" and rev is not None
        # 草稿仅用于说明“已入库但尚不能读”，绝不返回草稿正文或候选ID。
        book = json.loads(rev["body"] if readable else row["draft"])
        if body.language and book["language"] != body.language:
            continue
        if body.edition and normalize(book["edition"]) != normalize(body.edition):
            continue
        names = [book["title"], *book["aliases"]]
        evidence = [
            x for x in names if len(normalize(x)) >= 2 and normalize(x) in query
        ]
        isbn = normalize(book["isbn"])
        if isbn and isbn in query:
            evidence.append(book["isbn"])
        if evidence:
            # 精确ISBN或版本线索必须压过泛书名，包含未发布/下架版本，避免读错版本。
            rank = (
                3
                if isbn and isbn in query
                else 2
                if book["edition"] and normalize(book["edition"]) in query
                else 1
            )
            if not readable:
                has_text = bool(book.get("audioAsset")) or any(
                    page.get("text", "").strip() and not page.get("skip")
                    for page in book.get("pages", [])
                )
                unreadable.append(
                    (
                        rank,
                        "UNLISTED"
                        if row["status"] == "unlisted"
                        else "UNPUBLISHED"
                        if has_text
                        else "NO_TEXT",
                    )
                )
                continue
            matches.append(
                {
                    "rank": rank,
                    "resourceId": row["id"],
                    "revisionId": row["published_id"],
                    "title": book["title"],
                    "edition": book["edition"],
                    "language": book["language"],
                    "evidence": evidence,
                }
            )
    best = max(
        [m["rank"] for m in matches] + [rank for rank, _ in unreadable], default=0
    )
    matches = [m for m in matches if m.pop("rank") == best]
    unreadable_reasons = {reason for rank, reason in unreadable if rank == best}
    if not matches and unreadable_reasons:
        return {
            "status": "NOT_READABLE",
            "reason": next(iter(unreadable_reasons))
            if len(unreadable_reasons) == 1
            else "NOT_READY",
            "candidates": [],
        }
    # 标题包含关系不直接取最高分，保留歧义避免错误自动播书。
    return {
        "status": "MATCH"
        if len(matches) == 1
        else "AMBIGUOUS"
        if matches
        else "NOT_FOUND",
        "candidateCount": len(matches),
        "candidates": matches[:5],
    }


@router.get("/resources/{rid}/manifest")
def manifest(
    rid: str, request: Request, revisionId: str | None = None, user=Depends(principal)
):
    resource, revision = published(request.app.state.store, rid, revisionId)
    b = revision["body"]
    from .keywords import book_keywords

    return {
        "resourceId": rid,
        "revisionId": revision["id"],
        "title": b["title"],
        "kind": resource["kind"],
        "complete": b["complete"],
        "excerpt": b["excerpt"],
        "scopeNotice": scope_notice(b),
        "voice": b["voice"],
        "ttsProfile": b.get("ttsProfile", LEGACY_PROFILE),
        "audioAsset": b["audioAsset"],
        "segments": b["segments"],
        "offlineKeywords": book_keywords(rid, b),
        "catalogVersion": int(request.app.state.store.meta("catalog")),
    }


@router.put("/resources/{rid}/progress")
def progress(rid: str, body: Progress, request: Request, user=Depends(robot)):
    store = request.app.state.store
    _, rev = published(store, rid, body.revisionId)
    if body.segmentId not in [s["id"] for s in rev["body"]["segments"]] and not (
        body.segmentId == "audio" and rev["body"]["audioAsset"]
    ):
        fail(422, "段落不属于版本")
    with store.transaction() as db:
        old = db.execute(
            "SELECT seq FROM progress WHERE robot_id=? AND resource_id=?",
            (user["id"], rid),
        ).fetchone()
        if old and old["seq"] >= body.seq:
            return {"accepted": False}
        db.execute(
            "INSERT OR REPLACE INTO progress VALUES(?,?,?,?,?,?)",
            (user["id"], rid, body.revisionId, body.segmentId, body.offsetMs, body.seq),
        )
    return {"accepted": True}


@router.get("/resources/{rid}/progress")
def get_progress(rid: str, request: Request, user=Depends(principal)):
    rid_robot = user["id"] if user["role"] == "robot" else user["robot_id"]
    return (
        request.app.state.store.one(
            "SELECT * FROM progress WHERE robot_id=? AND resource_id=?",
            (rid_robot, rid),
        )
        or {}
    )


@router.get("/catalog/revocations")
def revocations(request: Request, user=Depends(principal)):
    store = request.app.state.store
    items = store.read(
        "SELECT t.id,t.created,CASE WHEN r.status='published' THEN r.published_id ELSE '' END AS activeRevision FROM tombstones t LEFT JOIN resources r ON r.id=t.id WHERE t.kind='resource'"
    )
    revoked_by_resource = {}
    for revision in store.read(
        "SELECT resource_id,id FROM revisions WHERE revoked=1 ORDER BY created,id"
    ):
        revoked_by_resource.setdefault(revision["resource_id"], []).append(
            revision["id"]
        )
    for item in items:
        # 普通改版不撤销旧快照；历史下架墓碑仍保留，以便离线旧设备补同步。
        item["revokedRevisionIds"] = revoked_by_resource.get(item["id"], [])
    return {
        "catalogVersion": int(store.meta("catalog")),
        "items": items,
    }

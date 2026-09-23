import io
import json
import subprocess
import sys
import time
import wave
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, Query, Request, UploadFile
from fastapi.responses import Response
from PIL import Image, ImageOps
from pydantic import Field

from .auth import fail, parent, principal
from .library import get_resource, published
from .schemas import Strict
from .store import digest, dumps, uid

router = APIRouter(prefix="/v1")
TYPES = {
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".wav": "audio/wav",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
}


def decode_extraction(data, interrupted=""):
    if not data:
        raise ValueError("没有提取结果")
    text = data.decode("utf-8") if isinstance(data, bytes) else data
    # 兼容旧提取器的一次性结果；新提取器每完成一页就输出一行。
    if text.lstrip().startswith("["):
        if interrupted:
            raise ValueError("旧格式不支持部分恢复")
        return json.loads(text)
    sources = None
    pages = {}
    done = False
    for line in text.splitlines():
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            if interrupted:
                break  # 进程可能在最后一行中间超时，之前完整页仍可用。
            raise
        if "sources" in record:
            sources = record["sources"]
        elif "page" in record:
            page = record["page"]
            pages[page["sourcePage"]] = page
        done = bool(record.get("done")) or done
    if not isinstance(sources, list) or len(sources) > 300:
        raise ValueError("缺少有效源页清单")
    if not interrupted and (not done or len(pages) != len(sources)):
        raise ValueError("提取结果不完整")
    return [
        pages.get(index)
        or {
            "sourcePage": index,
            "text": "",
            "confidence": None,
            "qualityWarnings": ["OCR_FAILED"],
            "extractionError": interrupted or "Incomplete",
        }
        for index in sources
    ]


def process_job(store, job_id):
    job = store.one("SELECT * FROM jobs WHERE id=?", (job_id,))
    if not job or job["state"] not in ("queued", "interrupted", "failed"):
        return
    with store.transaction() as db:
        row = db.execute("SELECT state FROM jobs WHERE id=?", (job_id,)).fetchone()
        if not row or row["state"] not in ("queued", "interrupted", "failed"):
            return
        db.execute(
            "UPDATE jobs SET state='processing',error=NULL WHERE id=?", (job_id,)
        )
    asset = store.one("SELECT * FROM assets WHERE id=?", (job["asset_id"],))
    options = json.loads(job["options"])
    try:
        try:
            process = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "robot_service.extract",
                    str(store.root / "assets" / asset["id"]),
                    Path(asset["filename"]).suffix.lower(),
                    dumps({**options, "stream": True}),
                ],
                capture_output=True,
                timeout=180,
                cwd=Path(__file__).resolve().parents[1],
                check=True,
            )
            pages = decode_extraction(process.stdout)
        except subprocess.TimeoutExpired as exc:
            pages = decode_extraction(exc.stdout, "BatchTimeout")
        except subprocess.CalledProcessError as exc:
            pages = decode_extraction(exc.stdout, "ExtractorFailed")
        if len(pages) > 300:
            raise ValueError("pages limit")
        with store.transaction() as db:
            current = db.execute(
                "SELECT * FROM resources WHERE id=?", (job["resource_id"],)
            ).fetchone()
            state = db.execute(
                "SELECT state FROM jobs WHERE id=?", (job_id,)
            ).fetchone()[0]
            if state != "processing":
                return
            if (
                not current
                or current["status"] == "deleted"
                or current["draft_version"] != job["draft_version"]
            ):
                db.execute(
                    "UPDATE jobs SET state='stale',result=? WHERE id=?",
                    (dumps({"pageCount": len(pages)}), job_id),
                )
                return
            draft = json.loads(current["draft"])
            target_id = options.get("targetPageId")
            target = next((p for p in draft["pages"] if p["id"] == target_id), None)
            if target_id and (target is None or len(pages) != 1):
                raise ValueError("目标页不存在或结果不是单页")
            if not target_id and len(draft["pages"]) + len(pages) > 300:
                raise ValueError("pages limit")
            for page in pages:
                values = {
                    "sourceAsset": asset["id"],
                    "sourcePage": page["sourcePage"],
                    "sourceRotation": options.get("rotation", 0),
                    "text": page["text"],
                    "reviewed": False,
                    "confidence": page.get("confidence"),
                    "qualityWarnings": page.get("qualityWarnings", []),
                    "extractionError": page.get("extractionError", ""),
                }
                if target is not None:
                    # 失败重试保留当前文字和源图；不能用空结果覆盖人工校对内容。
                    if values["extractionError"]:
                        target.update(
                            {
                                k: values[k]
                                for k in (
                                    "extractionError",
                                    "qualityWarnings",
                                    "confidence",
                                    "reviewed",
                                )
                            }
                        )
                    else:
                        target.update(values)
                else:
                    draft["pages"].append(
                        {
                            "id": uid(),
                            "label": "",
                            "chapter": "",
                            "skip": False,
                            "pronunciation": {},
                            **values,
                        }
                    )
            # 重算完全相同正文的提示；提示不自动合并/删除页面。
            texts = [p["text"].strip() for p in draft["pages"]]
            for p in draft["pages"]:
                warnings = [w for w in p.get("qualityWarnings", []) if w != "DUPLICATE"]
                if p["text"].strip() and texts.count(p["text"].strip()) > 1:
                    warnings.append("DUPLICATE")
                p["qualityWarnings"] = warnings
            draft["auditioned"] = False
            db.execute(
                "UPDATE resources SET draft=?,draft_version=draft_version+1,updated=? WHERE id=?",
                (dumps(draft), time.time(), job["resource_id"]),
            )
            failed_count = sum(bool(p.get("extractionError")) for p in pages)
            failed_target = bool(target_id and failed_count)
            db.execute(
                "UPDATE jobs SET state=?,result=?,error=? WHERE id=?",
                (
                    "failed" if failed_target else "needs_review",
                    dumps({"pageCount": len(pages), "failedPages": failed_count}),
                    "本页重新识别失败；原正文保留，可重试此作业"
                    if failed_target
                    else None,
                    job_id,
                ),
            )
    except Exception as exc:
        with store.transaction() as db:
            db.execute(
                "UPDATE jobs SET state='failed',error=? WHERE id=? AND state='processing'",
                (type(exc).__name__ + ": 解析失败，请检查格式/OCR安装后重试", job_id),
            )


def validate_file(data, extension):
    if extension in (".png", ".jpg", ".jpeg", ".webp"):
        image = Image.open(io.BytesIO(data))
        if image.width * image.height > 25_000_000:
            raise ValueError("image pixel limit")
        image.verify()
    elif extension == ".pdf":
        if not data.startswith(b"%PDF-"):
            raise ValueError("invalid pdf")
    elif extension in (".txt", ".md"):
        data.decode("utf-8-sig")
    elif extension == ".wav":
        with wave.open(io.BytesIO(data)) as wav:
            if (
                wav.getnchannels() not in (1, 2)
                or wav.getsampwidth() != 2
                or wav.getnframes() == 0
                or wav.getnframes() / wav.getframerate() > 7200
            ):
                raise ValueError("invalid audio")
    elif extension in (".mp3", ".m4a"):
        import av

        # 可寻址内存输入；使用已锁定的PyAV，部署不依赖额外ffprobe命令。
        with av.open(io.BytesIO(data)) as source:
            duration = (source.duration or 0) / av.time_base
            if (
                duration <= 0
                or duration > 7200
                or len(source.streams.audio) != 1
                or source.streams.video
            ):
                raise ValueError("invalid audio or duration")
            if extension == ".mp3" and source.format.name != "mp3":
                raise ValueError("format mismatch")
            if extension == ".m4a" and "mp4" not in source.format.name:
                raise ValueError("format mismatch")
            if next(source.decode(audio=0), None) is None:
                raise ValueError("empty audio")


@router.post("/resources/{rid}/assets")
async def upload(
    rid: str,
    request: Request,
    file: UploadFile = File(...),
    purpose: str = Query("pages", pattern="^(pages|cover|audio)$"),
    expectedVersion: int = Query(..., ge=1),
    targetPageId: str = Query("", max_length=64),
    user=Depends(parent),
):
    store = request.app.state.store
    data = await file.read(32 * 1024 * 1024 + 1)
    from starlette.concurrency import run_in_threadpool

    result = await run_in_threadpool(
        ingest_asset,
        store,
        rid,
        file.filename or "unknown",
        data,
        purpose,
        expectedVersion,
        targetPageId,
    )
    if result.get("jobId"):
        request.app.state.import_pool.submit(process_job, store, result["jobId"])
    return result


def ingest_asset(
    store, rid, filename, data, purpose, expected_version, target_page_id=""
):
    resource = get_resource(store, rid)
    if resource["draft_version"] != expected_version:
        fail(409, "草稿版本冲突")
    name = Path(filename).name
    extension = Path(name).suffix.lower()
    if extension not in TYPES:
        fail(422, "支持图片、PDF、TXT、Markdown、WAV、MP3、M4A")
    if purpose not in ("pages", "cover", "audio"):
        fail(422, "素材用途错误")
    if (purpose == "audio") != (extension in (".wav", ".mp3", ".m4a")):
        fail(422, "素材用途不匹配")
    if purpose == "cover" and extension not in (".png", ".jpg", ".jpeg", ".webp"):
        fail(422, "封面必须是图片")
    if target_page_id and (
        purpose != "pages" or extension not in (".png", ".jpg", ".jpeg", ".webp")
    ):
        fail(422, "重拍替换只能上传单张书页图片")
    if not data or len(data) > 32 * 1024 * 1024:
        fail(413, "素材为空或超过32MB")
    try:
        validate_file(data, extension)
    except Exception:
        fail(422, "素材不可解析；请检查格式、时长和音频轨道")
    asset_id = uid()
    path = store.root / "assets" / asset_id
    store.write_content(path, data)
    try:
        with store.transaction() as db:
            current = db.execute(
                "SELECT * FROM resources WHERE id=?", (rid,)
            ).fetchone()
            if (
                current["draft_version"] != expected_version
                or current["status"] == "deleted"
            ):
                fail(409, "上传期间草稿已变化")
            options = {}
            if target_page_id:
                if not any(
                    p["id"] == target_page_id
                    for p in json.loads(current["draft"])["pages"]
                ):
                    fail(404, "目标书页不存在")
                options = {
                    "targetPageId": target_page_id,
                    "sourcePage": 0,
                    "rotation": 0,
                }
            db.execute(
                "INSERT INTO assets VALUES(?,?,?,?,?,?)",
                (asset_id, rid, digest(data), name, TYPES[extension], len(data)),
            )
            draft = json.loads(current["draft"])
            if purpose in ("cover", "audio"):
                draft["coverAsset" if purpose == "cover" else "audioAsset"] = asset_id
                draft["auditioned"] = False
                db.execute(
                    "UPDATE resources SET draft=?,draft_version=draft_version+1 WHERE id=?",
                    (dumps(draft), rid),
                )
                return {"assetId": asset_id, "draftVersion": expected_version + 1}
            job_id = uid()
            db.execute(
                "INSERT INTO jobs(id,resource_id,asset_id,draft_version,state,result,error,created,options) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    job_id,
                    rid,
                    asset_id,
                    expected_version,
                    "queued",
                    "{}",
                    None,
                    time.time(),
                    dumps(options),
                ),
            )
        return {"assetId": asset_id, "jobId": job_id, "state": "queued"}
    except BaseException:
        path.unlink(missing_ok=True)
        raise


class PageExtraction(Strict):
    expectedVersion: int = Field(ge=1)
    rotation: Literal[0, 90, 180, 270] = 0


@router.post("/resources/{rid}/pages/{page_id}/extract")
def retry_page(
    rid: str, page_id: str, body: PageExtraction, request: Request, user=Depends(parent)
):
    store = request.app.state.store
    with store.transaction() as db:
        row = db.execute(
            "SELECT * FROM resources WHERE id=? AND status!='deleted'", (rid,)
        ).fetchone()
        if not row:
            fail(404, "资源不存在")
        if row["draft_version"] != body.expectedVersion:
            fail(409, "草稿版本冲突")
        page = next(
            (p for p in json.loads(row["draft"])["pages"] if p["id"] == page_id), None
        )
        if not page or not page.get("sourceAsset"):
            fail(422, "此页没有可重新识别的原稿")
        asset = db.execute(
            "SELECT * FROM assets WHERE id=? AND resource_id=?",
            (page["sourceAsset"], rid),
        ).fetchone()
        if not asset:
            fail(404, "原稿不存在")
        if body.rotation and not (
            asset["media_type"].startswith("image/")
            or asset["media_type"] == "application/pdf"
        ):
            fail(422, "文本不能旋转")
        job_id = uid()
        options = {
            "targetPageId": page_id,
            "sourcePage": page["sourcePage"],
            "rotation": body.rotation,
        }
        db.execute(
            "INSERT INTO jobs(id,resource_id,asset_id,draft_version,state,result,error,created,options) VALUES(?,?,?,?,?,?,?,?,?)",
            (
                job_id,
                rid,
                asset["id"],
                body.expectedVersion,
                "queued",
                "{}",
                None,
                time.time(),
                dumps(options),
            ),
        )
    request.app.state.import_pool.submit(process_job, store, job_id)
    return {"jobId": job_id, "state": "queued"}


@router.get("/jobs")
def jobs(request: Request, user=Depends(parent)):
    return request.app.state.store.read(
        "SELECT * FROM jobs ORDER BY created DESC LIMIT 200"
    )


@router.get("/jobs/{job_id}")
def job_status(job_id: str, request: Request, user=Depends(parent)):
    row = request.app.state.store.one("SELECT * FROM jobs WHERE id=?", (job_id,))
    if not row:
        fail(404, "作业不存在")
    return row


@router.post("/jobs/{job_id}/{action}")
def control_job(job_id: str, action: str, request: Request, user=Depends(parent)):
    store = request.app.state.store
    next_job = None
    with store.transaction() as db:
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if not row:
            fail(404, "作业不存在")
        if action == "cancel":
            db.execute("UPDATE jobs SET state='cancelled' WHERE id=?", (job_id,))
        elif action == "retry":
            if row["state"] not in ("failed", "interrupted", "stale", "cancelled"):
                fail(409, "作业不能重试")
            resource = db.execute(
                "SELECT * FROM resources WHERE id=? AND status!='deleted'",
                (row["resource_id"],),
            ).fetchone()
            if not resource:
                fail(404, "资源已删除")
            db.execute("UPDATE jobs SET state='cancelled' WHERE id=?", (job_id,))
            next_job = uid()
            db.execute(
                "INSERT INTO jobs(id,resource_id,asset_id,draft_version,state,result,error,created,options) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    next_job,
                    row["resource_id"],
                    row["asset_id"],
                    resource["draft_version"],
                    "queued",
                    "{}",
                    None,
                    time.time(),
                    row["options"],
                ),
            )
        else:
            fail(404, "未知操作")
    if action == "retry":
        request.app.state.import_pool.submit(process_job, store, next_job)
    return {
        "state": "cancelled" if action == "cancel" else "queued",
        "jobId": next_job or job_id,
    }


@router.get("/assets/{asset_id}")
def asset(asset_id: str, request: Request, user=Depends(principal)):
    store = request.app.state.store
    row = store.one("SELECT * FROM assets WHERE id=?", (asset_id,))
    if not row:
        fail(404, "素材不存在")
    if user["role"] == "robot":
        _, revision = published(store, row["resource_id"])
        if revision["body"]["audioAsset"] != asset_id:
            fail(403, "机器人不能读取原稿素材")
    from fastapi.responses import FileResponse

    return FileResponse(
        store.root / "assets" / asset_id,
        media_type=row["media_type"],
        headers={"Cache-Control": "no-store"},
    )


@router.get("/assets/{asset_id}/page/{page_index}")
def source_page(
    asset_id: str,
    page_index: int,
    request: Request,
    rotation: int = Query(0, ge=0, le=270),
    user=Depends(parent),
):
    if rotation not in (0, 90, 180, 270):
        fail(422, "旋转角度需为0、90、180或270")
    store = request.app.state.store
    row = store.one("SELECT * FROM assets WHERE id=?", (asset_id,))
    if not row:
        fail(404, "素材不存在")
    path = store.root / "assets" / asset_id
    if row["media_type"] == "application/pdf":
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(path)
        if page_index < 0 or page_index >= min(len(pdf), 300):
            pdf.close()
            fail(404, "页不存在")
        page = pdf[page_index]
        bitmap = page.render(scale=min(2, 1600 / max(page.get_size())))
        image = bitmap.to_pil().rotate(-rotation, expand=True)
        buf = io.BytesIO()
        image.save(buf, format="PNG")
        bitmap.close()
        page.close()
        pdf.close()
        return Response(buf.getvalue(), media_type="image/png")
    if not row["media_type"].startswith("image/"):
        fail(422, "此素材没有图像页面")
    if page_index != 0:
        fail(404, "页不存在")
    image = ImageOps.exif_transpose(Image.open(path)).rotate(-rotation, expand=True)
    image.thumbnail((1600, 1600))
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return Response(buf.getvalue(), media_type="image/png")

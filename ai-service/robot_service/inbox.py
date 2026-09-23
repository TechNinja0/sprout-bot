"""管理员显式导入 inbox 中的一个目录；只生成待审核草稿。"""

import re
import time
from pathlib import Path

from .imports import TYPES, ingest_asset, process_job
from .schemas import ResourceDraft
from .store import dumps, uid


def natural_key(path):
    return [
        int(part) if part.isdigit() else part.casefold()
        for part in re.split(r"(\d+)", path.name)
    ]


def import_directory(store, folder: str, title: str | None = None):
    inbox = store.root / "inbox"
    inbox.mkdir(exist_ok=True, mode=0o700)
    relative = Path(folder)
    if (
        inbox.is_symlink()
        or relative.is_absolute()
        or not relative.parts
        or any(p in ("..", ".") for p in relative.parts)
    ):
        raise ValueError("只允许导入数据目录 inbox 下的相对目录")
    current = inbox
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("导入路径不能包含符号链接")
    source = current.resolve()
    if (
        not source.is_relative_to(inbox.resolve())
        or not source.is_dir()
        or source == inbox.resolve()
    ):
        raise ValueError("导入目录不存在或超出 inbox")
    entries = list(source.iterdir())
    if any(p.is_symlink() for p in entries):
        raise ValueError("导入目录不能包含符号链接")
    files = sorted(
        (
            p
            for p in entries
            if p.name.casefold() not in (".ds_store", "thumbs.db", "desktop.ini")
        ),
        key=natural_key,
    )
    if any(not p.is_file() for p in files):
        raise ValueError("每个目录对应一本书，请移走子目录")
    if not files or len(files) > 301:
        raise ValueError("目录需要1—301个文件（封面及最多300页素材）")
    if any(
        p.suffix.lower() not in TYPES or p.suffix.lower() in (".wav", ".mp3", ".m4a")
        for p in files
    ):
        raise ValueError("目录只接受图片、PDF、TXT、Markdown；音频请在家长端录入")
    covers = [p for p in files if p.stem.casefold() in ("cover", "封面")]
    if len(covers) > 1 or any(
        p.suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp") for p in covers
    ):
        raise ValueError("只允许一张名为 cover 或封面的图片")
    if any(p.stat().st_size > 32 * 1024 * 1024 for p in files):
        raise ValueError("单个素材不能超过32MB")
    rid = uid()
    draft = ResourceDraft(title=title or source.name, source="家长电脑目录导入")
    with store.transaction() as db:
        db.execute(
            "INSERT INTO resources VALUES(?,?,?,?,?,?,?)",
            (rid, "book", 1, dumps(draft.model_dump()), None, "draft", time.time()),
        )
    results = []
    for path in files:
        try:
            # 读取时再检查边界，目录导入不跟随用户放入的外部链接。
            if path.is_symlink() or path.resolve().parent != source:
                raise ValueError("文件路径变化")
            with path.open("rb") as handle:
                data = handle.read(32 * 1024 * 1024 + 1)
            version = store.one(
                "SELECT draft_version FROM resources WHERE id=?", (rid,)
            )["draft_version"]
            result = ingest_asset(
                store,
                rid,
                path.name,
                data,
                "cover" if path in covers else "pages",
                version,
            )
            if result.get("jobId"):
                process_job(store, result["jobId"])
                job = store.one(
                    "SELECT state,result,error FROM jobs WHERE id=?", (result["jobId"],)
                )
                result.update(job)
            results.append({"filename": path.name, **result})
        except Exception as exc:
            results.append(
                {
                    "filename": path.name,
                    "state": "failed",
                    "error": getattr(exc, "detail", type(exc).__name__),
                }
            )
    return {
        "resourceId": rid,
        "status": "draft",
        "files": results,
        "message": "原文件保留；请在家长端校对页序、文字、完整范围并试听后发布",
    }

"""应用升级包只读代理：发布目录由 scripts/release_site.py archive 写入，仅家长可读。"""

import json

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse

from .auth import fail, parent

router = APIRouter(prefix="/v1/app")
FIELDS = ("versionName", "versionCode", "channel", "sha256", "sizeBytes", "notes")


def release(store):
    """返回 (manifest latest, APK 路径)；未发布或文件缺失时返回 None。"""
    try:
        manifest = json.loads((store.root / "releases/manifest.json").read_text())
        entry = manifest.get("latest")
    except (OSError, ValueError, AttributeError):
        return None
    if not isinstance(entry, dict) or ".." in str(entry.get("file", "")):
        return None
    apk = store.root / "releases" / str(entry.get("file", ""))
    if not apk.is_file():
        return None
    return entry, apk


@router.get("/latest")
def latest(request: Request, user=Depends(parent)):
    found = release(request.app.state.store)
    if not found:
        fail(404, "尚未发布应用安装包")
    entry, _ = found
    return {key: entry[key] for key in FIELDS if key in entry}


@router.get("/download")
def download(request: Request, user=Depends(parent)):
    found = release(request.app.state.store)
    if not found:
        fail(404, "尚未发布应用安装包")
    entry, apk = found
    return FileResponse(
        apk,
        media_type="application/vnd.android.package-archive",
        headers={
            "Cache-Control": "no-store",
            # 与 App 端共用现有校验约定：响应头提供整体 SHA256，客户端流式校验。
            "X-Content-SHA256": entry["sha256"],
        },
    )

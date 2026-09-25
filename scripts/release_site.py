#!/usr/bin/env python3
"""局域网发布中心：archive 把新构建的 APK 自动发布到 runtime/releases，
serve 在局域网提供下载官网（含二维码），供手机浏览器首次安装或手动升级。

与家庭 HTTPS 服务(8766)解耦：官网是纯 HTTP 的只读静态分发，不含任何设备凭据；
App 内升级走 8766 的鉴权接口 /v1/app/latest 与 /v1/app/download。
"""

import argparse
import hashlib
import html
import json
import re
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAX_APK_BYTES = 1024 * 1024 * 1024
MANIFEST_NAME = "manifest.json"
DEFAULT_PORT = 8767
DEFAULT_KEEP = 3


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def safe_name(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]", "-", value)[:80]


def app_display_name() -> str:
    """应用显示名（与手机桌面一致）：用于安装包文件名与官网标题。"""
    text = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text()
    match = re.search(r'android:label="([^"]+)"', text)
    name = re.sub(r'[\\/:*?"<>|\s]+', "-", match.group(1).strip()) if match else ""
    return name or "app"


def gradle_version():
    text = (ROOT / "android-app/app/build.gradle.kts").read_text()
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        return None
    return name.group(1), int(code.group(1))


def load_manifest(releases: Path) -> dict:
    try:
        manifest = json.loads((releases / MANIFEST_NAME).read_text())
        if isinstance(manifest, dict) and isinstance(manifest.get("history"), list):
            return manifest
    except (OSError, ValueError):
        pass
    return {"latest": None, "history": []}


def write_manifest(releases: Path, manifest: dict) -> None:
    tmp = releases / (MANIFEST_NAME + ".partial")
    tmp.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    tmp.replace(releases / MANIFEST_NAME)


def cmd_archive(args) -> int:
    apk = Path(args.apk).expanduser()
    if not apk.is_file():
        print(f"APK 不存在：{apk}", file=sys.stderr)
        return 2
    if apk.suffix.lower() != ".apk":
        print("仅支持 .apk 文件", file=sys.stderr)
        return 2
    size = apk.stat().st_size
    if size <= 0 or size > MAX_APK_BYTES:
        print("APK 大小异常（0 或超过 1GB）", file=sys.stderr)
        return 2

    parsed = gradle_version()
    version_name = args.version_name or (parsed[0] if parsed else None)
    version_code = args.version_code or (parsed[1] if parsed else None)
    if not version_name or not version_code:
        print(
            "无法从 build.gradle.kts 解析版本号，请提供 --version-name 与 --version-code",
            file=sys.stderr,
        )
        return 2

    releases = Path(args.releases_dir)
    releases.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest(releases)
    latest = manifest["latest"]
    if latest:
        if latest["versionCode"] > version_code:
            print(
                f"版本号必须递增：已发布 {latest['versionCode']}，本次 {version_code}",
                file=sys.stderr,
            )
            return 2
        if latest["versionCode"] == version_code and not args.force:
            print("该版本已发布；重复发布需 --force", file=sys.stderr)
            return 2

    file_name = f"{app_display_name()}-v{safe_name(version_name)}.apk"
    dest = releases / file_name
    temp = releases / (file_name + ".partial")
    shutil.copy2(apk, temp)
    checksum = sha256_file(temp)
    if dest.is_file():
        dest.unlink()
    temp.replace(dest)

    entry = {
        "versionName": version_name,
        "versionCode": version_code,
        "channel": args.channel,
        "sha256": checksum,
        "sizeBytes": size,
        "file": file_name,
        "notes": (args.notes or "").strip()[:500],
        "publishedAt": int(time.time()),
    }
    history = [e for e in manifest["history"] if e.get("file") != file_name]
    history.insert(0, entry)
    for old in history[args.keep :]:
        (releases / str(old.get("file", ""))).unlink(missing_ok=True)
    write_manifest(releases, {"latest": entry, "history": history[: args.keep]})

    print(f"已发布 v{version_name}（versionCode {version_code}，{size} 字节）")
    print(f"文件：{dest}")
    print(f"SHA256：{checksum}")
    print("官网刷新后即可下载；家长端 App 启动会自动提示升级。")
    return 0


def cmd_bump(args) -> int:
    """把 build.gradle.kts 的版本号向前推进一步：versionCode +1，versionName 默认 patch +1。"""
    path = Path(args.gradle_file)
    if not path.is_file():
        print(f"找不到构建配置：{path}", file=sys.stderr)
        return 2
    text = path.read_text()
    code_match = re.search(r"versionCode\s*=\s*(\d+)", text)
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code_match or not name_match:
        print("无法解析当前 versionCode / versionName", file=sys.stderr)
        return 2
    new_code = int(code_match.group(1)) + 1
    current = name_match.group(1)
    if args.set:
        new_name = args.set.strip()
        if not re.fullmatch(r"[0-9A-Za-z._-]{1,40}", new_name):
            print("版本名只允许字母、数字、点、下划线与连字符", file=sys.stderr)
            return 2
    else:
        parsed = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(.*)", current)
        if not parsed:
            print(f"无法自动递增版本名 {current}，请用 --set 指定", file=sys.stderr)
            return 2
        major, minor, patch, suffix = parsed.groups()
        if args.part == "major":
            new_name = f"{int(major) + 1}.0.0{suffix}"
        elif args.part == "minor":
            new_name = f"{major}.{int(minor) + 1}.0{suffix}"
        else:
            new_name = f"{major}.{minor}.{int(patch) + 1}{suffix}"
    text = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {new_code}", text, count=1)
    text = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{new_name}"', text, count=1)
    tmp = path.with_name(path.name + ".partial")
    tmp.write_text(text)
    tmp.replace(path)
    print(f"版本已更新：{new_name}（versionCode {new_code}）")
    return 0


def create_site_app(releases: Path):
    from fastapi import FastAPI, Request
    from fastapi.responses import FileResponse, JSONResponse, Response

    app = FastAPI(title="Family Robot Release Site", docs_url=None, redoc_url=None)
    app.state.releases = Path(releases)

    def find_entry(file_name: str):
        for entry in load_manifest(app.state.releases)["history"]:
            if entry.get("file") == file_name:
                return entry
        return None

    @app.get("/healthz")
    def healthz():
        return {"ok": True}

    @app.get("/api/latest")
    def api_latest():
        entry = load_manifest(app.state.releases)["latest"]
        if not entry:
            return JSONResponse({"detail": "尚未发布应用安装包"}, 404)
        return entry

    @app.get("/download/{file_name}")
    def download(file_name: str):
        entry = find_entry(file_name)
        if not entry:
            return JSONResponse({"detail": "安装包不存在"}, 404)
        path = app.state.releases / file_name
        if not path.is_file():
            return JSONResponse({"detail": "安装包文件缺失"}, 404)
        return FileResponse(
            path,
            media_type="application/vnd.android.package-archive",
            headers={"X-Content-SHA256": entry["sha256"]},
        )

    @app.get("/qr")
    def qr(request: Request):
        import io

        import qrcode

        url = str(request.base_url).rstrip("/")
        image = qrcode.make(url, box_size=8, border=1)
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        return Response(buffer.getvalue(), media_type="image/png")

    @app.get("/")
    def index(request: Request):
        manifest = load_manifest(app.state.releases)
        latest, history = manifest["latest"], manifest["history"]
        app_name = html.escape(app_display_name())
        rows = ""
        for entry in history:
            rows += (
                "<tr><td>{}{}</td><td>{}</td><td>{} MB</td><td><a href='/download/{}'>下载</a></td></tr>"
            ).format(
                html.escape(str(entry["versionName"])),
                "（最新）" if latest and entry["file"] == latest["file"] else "",
                html.escape(entry.get("channel", "")),
                entry["sizeBytes"] // 1048576,
                html.escape(entry["file"]),
            )
        if latest:
            notes = html.escape(latest.get("notes") or "无更新说明")
            card = f"""
<section class="card">
  <h2>最新版本 {html.escape(str(latest['versionName']))}</h2>
  <p>{notes}</p>
  <p class="meta">大小约 {latest['sizeBytes'] // 1048576} MB · 发布时间 {time.strftime('%Y-%m-%d %H:%M', time.localtime(latest['publishedAt']))}</p>
  <a class="button" href="/download/{html.escape(latest['file'])}">下载安装包（APK）</a>
</section>"""
        else:
            card = """
<section class="card">
  <h2>还没有发布安装包</h2>
  <p>在家庭电脑上执行 <code>bash scripts/release_app.sh "更新说明"</code> 构建并发布。</p>
</section>"""
        page = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{app_name} · 应用下载</title>
<style>
body{{font-family:-apple-system,"PingFang SC",sans-serif;margin:0;background:#f5f7f5;color:#182e2d}}
main{{max-width:560px;margin:0 auto;padding:28px 18px 60px}}
h1{{font-size:24px}} h2{{font-size:19px;margin:0 0 10px}}
.card{{background:#fff;border-radius:20px;padding:22px;margin:16px 0;box-shadow:0 1px 4px rgba(24,46,45,.08)}}
.button{{display:block;text-align:center;background:#196956;color:#fff;text-decoration:none;border-radius:14px;padding:15px;font-size:16px;margin-top:14px}}
.meta{{color:#586c67;font-size:13px}}
ol{{line-height:1.9}}
img{{display:block;margin:14px auto 0;width:180px;height:180px}}
table{{width:100%;border-collapse:collapse;font-size:14px}}
td{{padding:9px 4px;border-bottom:1px solid #e1e9e4}}
a{{color:#196956}}
code{{background:#e9f0eb;border-radius:6px;padding:2px 6px}}
</style></head><body><main>
<h1>{app_name} · 应用下载</h1>
<p class="meta">家庭局域网发布中心 · {html.escape(str(request.base_url).rstrip('/'))}</p>
{card}
<section class="card">
  <h2>手机安装步骤</h2>
  <ol>
    <li>点击上方按钮下载 APK（无需连接公网）</li>
    <li>打开系统通知或"下载"中的文件，允许安装未知来源应用</li>
    <li>安装完成后打开 App；已有家长身份的手机之后会在启动时收到升级提示</li>
  </ol>
  <img src="/qr" alt="本页二维码" width="180" height="180">
  <p class="meta" style="text-align:center">用手机相机扫码直接打开本页</p>
</section>
<section class="card">
  <h2>历史版本</h2>
  <table><tr><th>版本</th><th>渠道</th><th>大小</th><th></th></tr>{rows}</table>
</section>
</main></body></html>"""
        return Response(page, media_type="text/html; charset=utf-8")

    return app


def cmd_serve(args) -> int:
    import uvicorn

    app = create_site_app(Path(args.releases_dir))
    uvicorn.run(app, host=args.bind, port=args.port, access_log=False)
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    archive = sub.add_parser("archive", help="发布一个新构建的 APK")
    archive.add_argument("--apk", required=True)
    archive.add_argument("--releases-dir", default=str(ROOT / "runtime/releases"))
    archive.add_argument("--version-name")
    archive.add_argument("--version-code", type=int)
    archive.add_argument("--channel", default="debug")
    archive.add_argument("--notes", default="")
    archive.add_argument("--keep", type=int, default=DEFAULT_KEEP)
    archive.add_argument("--force", action="store_true")
    archive.set_defaults(func=cmd_archive)

    bump = sub.add_parser("bump", help="递增 Android 版本号（versionCode +1，versionName 默认 patch +1）")
    bump.add_argument("--gradle-file", default=str(ROOT / "android-app/app/build.gradle.kts"))
    bump.add_argument("--part", choices=["patch", "minor", "major"], default="patch")
    bump.add_argument("--set", metavar="X.Y.Z", help="直接指定新的 versionName（如升 0.2.0）")
    bump.set_defaults(func=cmd_bump)

    serve = sub.add_parser("serve", help="启动局域网下载官网")
    serve.add_argument("--bind", default="0.0.0.0")
    serve.add_argument("--port", type=int, default=DEFAULT_PORT)
    serve.add_argument("--releases-dir", default=str(ROOT / "runtime/releases"))
    serve.set_defaults(func=cmd_serve)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

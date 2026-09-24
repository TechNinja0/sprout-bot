#!/usr/bin/env python3
"""下载锁定的 Qwen3-TTS 1.7B；断点续传、内容校验，推理时不联网。"""

import argparse
import hashlib
import json
import shutil
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "scripts/tts-model.lock.json").read_text())


def verified(path, entry):
    if not path.is_file() or path.stat().st_size != entry["size"]:
        return False
    h = hashlib.sha256() if entry["sha256"] else hashlib.sha1()
    if not entry["sha256"]:
        h.update(f"blob {entry['size']}\0".encode())
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest() == (entry["sha256"] or entry["gitBlob"])


def check(models):
    directory = models / LOCK["directory"]
    missing = [
        f["path"] for f in LOCK["files"] if not verified(directory / f["path"], f)
    ]
    try:
        installed = json.loads((directory / "installed.json").read_text())
    except (OSError, ValueError):
        installed = {}
    if installed != {"repo": LOCK["repo"], "revision": LOCK["revision"]}:
        missing.append("installed.json")
    return missing


def install(models, endpoint):
    directory = models / LOCK["directory"]
    directory.mkdir(parents=True, exist_ok=True)
    missing = [f for f in LOCK["files"] if not verified(directory / f["path"], f)]
    if (
        shutil.disk_usage(directory).free
        < sum(f["size"] for f in missing) + 512 * 1024**2
    ):
        raise RuntimeError("模型安装空间不足，请至少为模型预留约 4GB")

    def download(entry):
        dest = directory / entry["path"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        partial = dest.with_suffix(dest.suffix + ".partial")
        url = f"{endpoint.rstrip('/')}/{LOCK['repo']}/resolve/{LOCK['revision']}/{entry['path']}"
        for attempt in range(5):
            try:
                start = partial.stat().st_size if partial.is_file() else 0
                if start >= entry["size"]:
                    if verified(partial, entry):
                        partial.replace(dest)
                        return
                    partial.unlink()
                    start = 0
                request = urllib.request.Request(
                    url, headers={"Range": f"bytes={start}-"} if start else {}
                )
                if entry["size"] > 64 * 1024**2:
                    # 有界并发，最多缓存约 96MB；仅连续完成的区间写入断点文件。
                    chunk_size = 4 * 1024**2

                    def fetch_range(bounds):
                        lo, hi = bounds
                        req = urllib.request.Request(
                            url + f"?range={lo}-{hi}",
                            headers={"Range": f"bytes={lo}-{hi}"},
                        )
                        with urllib.request.urlopen(req, timeout=120) as response:
                            expected = f"bytes {lo}-{hi}/{entry['size']}"
                            if (
                                response.status != 206
                                or response.headers.get("Content-Range") != expected
                            ):
                                raise ValueError("下载服务器未返回请求区间")
                            data = response.read(hi - lo + 2)
                            if len(data) != hi - lo + 1:
                                raise ValueError("下载区间不完整")
                            return data

                    with (
                        ThreadPoolExecutor(max_workers=24) as ranges,
                        partial.open("ab") as out,
                    ):
                        while start < entry["size"]:
                            bounds = [
                                (lo, min(lo + chunk_size, entry["size"]) - 1)
                                for lo in range(
                                    start,
                                    min(start + chunk_size * 24, entry["size"]),
                                    chunk_size,
                                )
                            ]
                            for block in ranges.map(fetch_range, bounds):
                                out.write(block)
                                out.flush()
                                start += len(block)
                            print(
                                f"下载：{entry['path']} {start / entry['size']:.0%}",
                                flush=True,
                            )
                else:
                    with urllib.request.urlopen(request, timeout=60) as response:
                        append = response.status == 206 and response.headers.get(
                            "Content-Range", ""
                        ).startswith(f"bytes {start}-")
                        with partial.open("ab" if append else "wb") as out:
                            while block := response.read(1024 * 1024):
                                out.write(block)
                if not verified(partial, entry):
                    raise ValueError("下载不完整或内容校验失败")
                partial.replace(dest)
                print("已校验：" + entry["path"], flush=True)
                return
            except (OSError, ValueError) as exc:
                print(
                    f"下载重试：{entry['path']} ({type(exc).__name__}, {attempt + 1}/5)",
                    flush=True,
                )
                if attempt == 4:
                    raise
                time.sleep(attempt + 1)

    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(download, missing))
    (directory / "installed.json").write_text(
        json.dumps({"repo": LOCK["repo"], "revision": LOCK["revision"]})
    )
    print("本地 TTS 模型就绪：" + str(directory), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", type=Path, default=ROOT / "runtime/models")
    parser.add_argument("--endpoint", default="https://huggingface.co")
    parser.add_argument("--check", action="store_true", help="只校验，不下载或修改文件")
    args = parser.parse_args()
    if args.check:
        missing = check(args.models)
        for name in missing:
            print("缺失或校验失败：" + name)
        raise SystemExit(1 if missing else 0)
    install(args.models, args.endpoint)

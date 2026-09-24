#!/usr/bin/env python3
"""下载固定依赖到忽略目录。没有自动接受变化中的同名模型。Python>=3.12。"""

import argparse
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "scripts/models.lock.json").read_text())


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def cache_path(asset, cache):
    # 多个 Hugging Face 仓库都有 config.json/model.bin，不能共用缓存文件名。
    prefix = hashlib.sha256(asset["url"].encode()).hexdigest()[:16]
    return cache / (prefix + "-" + asset["url"].rsplit("/", 1)[-1])


def destination(asset, models):
    if "destination" in asset:
        return models / asset["destination"]
    if asset["id"] == "aar":
        return ROOT / "android-app/app/libs" / asset["url"].rsplit("/", 1)[-1]
    return ROOT / ".tools/ollama" if asset["id"].startswith("ollama") else models


def archive_matches(archive_path, dest):
    with tarfile.open(archive_path) as archive:
        for member in archive.getmembers():
            path = dest / member.name
            if not path.resolve().is_relative_to(dest.resolve()):
                return False
            if member.isdir():
                if not path.is_dir():
                    return False
            elif member.issym():
                if not path.is_symlink() or str(path.readlink()) != member.linkname:
                    return False
            elif member.isfile() or member.islnk():
                if not path.is_file():
                    return False
                if (
                    member.isfile()
                    and path.stat().st_mode & 0o111 != member.mode & 0o111
                ):
                    return False
                with archive.extractfile(member) as stream:
                    size = stream.seek(0, 2)
                    if path.stat().st_size != size:
                        return False
                    stream.seek(0)
                    h = hashlib.file_digest(stream, "sha256").hexdigest()
                if sha(path) != h:
                    return False
            else:
                return False
    return True


def asset_matches(asset, cache, models):
    dest = destination(asset, models)
    if "destination" in asset or asset["id"] == "aar":
        return dest.is_file() and sha(dest) == asset["sha256"]
    target = cache_path(asset, cache)
    # 兼容原安装器留下的下载缓存；归档是解包文件的校验依据。
    if not target.is_file():
        target = cache / asset["url"].rsplit("/", 1)[-1]
    return (
        target.is_file()
        and sha(target) == asset["sha256"]
        and archive_matches(target, dest)
    )


def mobile_copies(ids, models):
    pairs = []
    if "kws" in ids:
        source = models / "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
        dest = ROOT / "android-app/app/src/main/assets/models/kws"
        for name in [
            "tokens.txt",
            *[
                f"{part}-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                for part in ("encoder", "decoder", "joiner")
            ],
        ]:
            pairs.append((source / name, dest / name))
        pairs.append((ROOT / "protocol/kws-default.txt", dest / "keywords.txt"))
    if "vad" in ids:
        pairs.append(
            (
                models / "silero-vad/silero_vad.onnx",
                ROOT / "android-app/app/src/main/assets/models/vad/silero_vad.onnx",
            )
        )
    return pairs


def check(ids, cache, models):
    failed = []
    for asset in LOCK["assets"]:
        if asset["id"] in ids and not asset_matches(asset, cache, models):
            failed.append(asset.get("destination", asset["id"]))
    for source, dest in mobile_copies(ids, models):
        if not source.is_file() or not dest.is_file() or sha(source) != sha(dest):
            failed.append(str(dest.relative_to(ROOT)))
    return failed


def install(ids, cache, models):
    cache.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    for asset in LOCK["assets"]:
        if asset["id"] not in ids:
            continue
        if asset_matches(asset, cache, models):
            print("已校验，跳过：" + asset.get("destination", asset["id"]), flush=True)
            continue
        target = cache_path(asset, cache)
        legacy = cache / asset["url"].rsplit("/", 1)[-1]
        if not target.is_file() and legacy.is_file() and sha(legacy) == asset["sha256"]:
            target = legacy
        if not target.is_file() or sha(target) != asset["sha256"]:
            temp = target.with_suffix(target.suffix + ".partial")
            print("下载：" + asset["id"], flush=True)
            with (
                urllib.request.urlopen(asset["url"], timeout=30) as response,
                temp.open("wb") as out,
            ):
                while block := response.read(1024 * 1024):
                    if shutil.disk_usage(cache).free < len(block) + 256 * 1024 * 1024:
                        raise RuntimeError("模型下载空间不足，保留已安装文件")
                    out.write(block)
            if sha(temp) != asset["sha256"]:
                temp.unlink(missing_ok=True)
                raise RuntimeError("完整性校验失败：" + asset["id"])
            temp.replace(target)
        if "destination" in asset:
            dest = models / asset["destination"]
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, dest)
        elif asset["id"] == "aar":
            dest = ROOT / "android-app/app/libs" / target.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, dest)
        else:
            dest = (
                ROOT / ".tools/ollama" if asset["id"].startswith("ollama") else models
            )
            dest.mkdir(parents=True, exist_ok=True)
            with tarfile.open(target) as archive:
                if (
                    sum(member.size for member in archive.getmembers())
                    + 256 * 1024 * 1024
                    > shutil.disk_usage(dest).free
                ):
                    raise RuntimeError("模型解包空间不足")
                archive.extractall(dest, filter="data")
        print("已校验：" + asset["id"], flush=True)
    for source, dest in mobile_copies(ids, models):
        if dest.is_file() and sha(source) == sha(dest):
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--only", default="kws,vad,aar,asr,tts", help="逗号分隔的models.lock.json条目ID"
    )
    parser.add_argument(
        "--cache", type=Path, default=ROOT / ".artifacts/model-downloads"
    )
    parser.add_argument("--models", type=Path, default=ROOT / "runtime/models")
    parser.add_argument("--check", action="store_true", help="只校验，不下载或修改文件")
    args = parser.parse_args()
    ids = args.only.split(",")
    known = {a["id"] for a in LOCK["assets"]}
    if set(ids) - known:
        parser.error("未知依赖：" + ",".join(set(ids) - known))
    if args.check:
        missing = check(ids, args.cache, args.models)
        for name in missing:
            print("缺失或校验失败：" + name)
        raise SystemExit(1 if missing else 0)
    install(ids, args.cache, args.models)

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


def install(ids, cache, models):
    cache.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    for asset in LOCK["assets"]:
        if asset["id"] not in ids:
            continue
        target = cache / asset["url"].rsplit("/", 1)[-1]
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
    if "kws" in ids:
        source = models / "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
        dest = ROOT / "android-app/app/src/main/assets/models/kws"
        dest.mkdir(parents=True, exist_ok=True)
        for name in [
            "tokens.txt",
            *[
                f"{part}-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                for part in ("encoder", "decoder", "joiner")
            ],
        ]:
            shutil.copy2(source / name, dest / name)
        shutil.copy2(ROOT / "protocol/kws-default.txt", dest / "keywords.txt")

    if "vad" in ids:
        dest = ROOT / "android-app/app/src/main/assets/models/vad"
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(models / "silero-vad/silero_vad.onnx", dest / "silero_vad.onnx")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--only", default="kws,vad,aar,asr,tts", help="逗号分隔的models.lock.json条目ID"
    )
    parser.add_argument(
        "--cache", type=Path, default=ROOT / ".artifacts/model-downloads"
    )
    parser.add_argument("--models", type=Path, default=ROOT / "runtime/models")
    args = parser.parse_args()
    ids = args.only.split(",")
    known = {a["id"] for a in LOCK["assets"]}
    if set(ids) - known:
        parser.error("未知依赖：" + ",".join(set(ids) - known))
    install(ids, args.cache, args.models)

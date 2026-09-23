#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
locks = json.loads((root / "scripts/models.lock.json").read_text())
for role in ("llm", "vlm"):
    lock = locks[role]
    name, tag = lock["name"].split(":")
    path = (
        root / "runtime/models/ollama/manifests/registry.ollama.ai/library" / name / tag
    )
    if not path.is_file():
        raise SystemExit("先向项目 Ollama 服务拉取 " + lock["name"])
    if hashlib.sha256(path.read_bytes()).hexdigest() != lock["manifestSha256"]:
        raise SystemExit("模型标签内容变化，停止使用并审核新许可/能力/哈希")
    manifest = json.loads(path.read_text())
    for layer in [manifest["config"], *manifest["layers"]]:
        digest = layer["digest"].split(":")[1]
        file = root / "runtime/models/ollama/blobs" / ("sha256-" + digest)
        h = hashlib.sha256()
        with file.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(block)
        if h.hexdigest() != digest or file.stat().st_size != layer["size"]:
            raise SystemExit("模型文件校验失败")
    print(lock["name"] + " 固定清单、配置和全部模型层校验通过")

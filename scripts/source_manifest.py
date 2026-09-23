#!/usr/bin/env python3
"""列出 Git 可见源码及 SHA256；只生成私有清单，不提交或发布文件。"""

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def build_manifest(root: Path) -> dict:
    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", str(root), *args], capture_output=True, check=False
        )

    listing = git("ls-files", "-z", "--cached", "--others", "--exclude-standard")
    listing.check_returncode()
    files = []
    for name in sorted(set(listing.stdout.decode().split("\0")) - {""}):
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"源码路径不是普通文件，需先核对：{name}")
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"源码路径越出仓库：{name}")
        data = path.read_bytes()
        files.append(
            {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        )
    head = git("rev-parse", "--verify", "HEAD")
    status = git("status", "--porcelain", "--untracked-files=all")
    status.check_returncode()
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "commit": head.stdout.decode().strip() if head.returncode == 0 else None,
        "dirty": bool(status.stdout),
        "scope": "Git可见文件（含未提交文件）；不证明隐私、许可证或产品验收通过",
        "fileCount": len(files),
        "totalBytes": sum(item["bytes"] for item in files),
        "files": files,
    }


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output", type=Path, default=root / ".artifacts/development/source-manifest.json"
    )
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(root):
        ignored = subprocess.run(
            ["git", "-C", str(root), "check-ignore", "-q", str(output)], check=False
        )
        if ignored.returncode != 0:
            parser.error("仓库内清单必须保存到已忽略目录，避免把上次清单纳入新清单")
    manifest = build_manifest(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"已记录 {manifest['fileCount']} 个文件；dirty={manifest['dirty']}；{output}")


if __name__ == "__main__":
    main()

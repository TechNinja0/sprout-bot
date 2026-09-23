#!/usr/bin/env python3
"""清点当前Python环境和本地AAR的依赖、许可证文件与哈希；不下载或发布。"""

import argparse
import hashlib
import importlib.metadata
import json
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def license_path(path):
    return bool(
        re.match(r"(?i)^(license|licence|copying|notice)([._-]|$)", path.name)
        or any(part.lower() in {"licenses", "licences"} for part in path.parts)
    )


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output", type=Path, default=root / ".artifacts/dependency-audit"
    )
    parser.add_argument(
        "--aar", type=Path, default=root / "android-app/app/libs/sherpa-onnx-1.13.8.aar"
    )
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(root):
        ignored = subprocess.run(
            [
                "git",
                "-C",
                str(root),
                "check-ignore",
                "-q",
                str(output / "inventory.json"),
            ],
            check=False,
        )
        if ignored.returncode:
            parser.error("仓库内依赖证据必须写入已忽略目录，不能自动混入源码发行")
    output.mkdir(parents=True, exist_ok=True)
    packages = []
    for distribution in sorted(
        importlib.metadata.distributions(),
        key=lambda d: d.metadata.get("Name", "").lower(),
    ):
        name = distribution.metadata.get("Name", "unknown")
        version = distribution.version
        folder = re.sub(r"[^A-Za-z0-9_.-]", "_", f"{name}-{version}")
        documents = []
        unavailable = []
        for entry in distribution.files or []:
            if not license_path(Path(str(entry))):
                continue
            source = Path(distribution.locate_file(entry)).resolve()
            if (
                not source.is_relative_to(Path(sys.prefix).resolve())
                or not source.is_file()
            ):
                unavailable.append(str(entry))
                continue
            data = source.read_bytes()
            # 保留包内层次；不让RECORD中的相对跳转写到输出目录外。
            relative = Path(
                *[
                    part
                    for part in Path(str(entry)).parts
                    if part not in {"..", ".", "/"}
                ]
            )
            target = output / "python" / folder / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            documents.append(
                {
                    "source": str(entry),
                    "copy": str(target.relative_to(output)),
                    "sha256": digest(data),
                }
            )
        packages.append(
            {
                "name": name,
                "version": version,
                "licenseExpression": distribution.metadata.get("License-Expression"),
                "licenseMetadata": (distribution.metadata.get("License") or "")[:2000],
                "licenseFiles": documents,
                "unavailableFiles": unavailable,
                "requiresManualReview": not documents or bool(unavailable),
            }
        )
    aar = None
    if args.aar.is_file():
        with zipfile.ZipFile(args.aar) as archive:
            entries = []
            for item in archive.infolist():
                if item.is_dir() or not (
                    item.filename.endswith(".so") or license_path(Path(item.filename))
                ):
                    continue
                data = archive.read(item)
                markers = [
                    marker.decode()
                    for marker in (b"espeak_", b"piper", b"phonemize", b"onnxruntime")
                    if marker in data
                ]
                entries.append(
                    {
                        "path": item.filename,
                        "bytes": len(data),
                        "sha256": digest(data),
                        "binaryStringMarkers": markers,
                    }
                )
            aar = {
                "name": args.aar.name,
                "sha256": digest(args.aar.read_bytes()),
                "entries": entries,
            }
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "pythonVersion": sys.version.split()[0],
        "environment": sys.prefix,
        "scope": "当前环境的文件清点；不等同完整SBOM、许可证法律结论或二进制发行放行。原生字符串仅为线索，未出现也不能证明未链接。",
        "packages": packages,
        "aar": aar,
    }
    (output / "inventory.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    review = sum(item["requiresManualReview"] for item in packages)
    print(
        f"已清点{len(packages)}个Python发行包，其中{review}个缺少完整随包许可文件，需人工核对。"
    )
    print(output / "inventory.json")


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROBOT_PYTHON="${ROBOT_PYTHON:-python3.12}"
"$ROBOT_PYTHON" -m venv "$ROBOT_ROOT/ai-service/.venv"
"$ROBOT_ROOT/ai-service/.venv/bin/python" -m pip install -r "$ROBOT_ROOT/ai-service/requirements-lock.txt"
"$ROBOT_ROOT/ai-service/.venv/bin/python" -m pip install --no-deps -e "$ROBOT_ROOT/ai-service"
if [[ "$(uname -s)" == Darwin ]]; then
  xcrun swiftc "$ROBOT_ROOT/ai-service/native/ocr.swift" -o "$ROBOT_ROOT/ai-service/native/ocr"
else
  printf '%s\n' '图片OCR需要安装 tesseract-ocr 与 chi_sim/eng；压缩音频由已安装的PyAV解析，无需额外ffprobe命令。'
fi

#!/usr/bin/env bash
# 独立环境避免 MLX/Transformers 与 ASR 依赖相互升级。
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROBOT_PYTHON="${ROBOT_PYTHON:-python3.12}"
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  printf '%s\n' '当前 Qwen MLX 适配器需要 Apple Silicon Mac；其他平台可设置 ROBOT_TTS_BACKEND=kokoro。' >&2
  exit 1
fi
"$ROBOT_PYTHON" -m venv "$ROBOT_ROOT/ai-service/.venv-tts"
"$ROBOT_ROOT/ai-service/.venv-tts/bin/python" -m pip install -r "$ROBOT_ROOT/ai-service/requirements-tts-lock.txt"
"$ROBOT_ROOT/ai-service/.venv-tts/bin/python" -m pip check
printf '%s\n' 'TTS 环境就绪，继续运行 python3 scripts/install_tts.py 安装固定模型。'

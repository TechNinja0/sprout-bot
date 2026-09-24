#!/usr/bin/env bash
# 前台运行家庭局域网 HTTPS 服务；后台常驻可由 launchd 托管此脚本。
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROBOT_ROOT"
export ROBOT_ASR_BACKEND="${ROBOT_ASR_BACKEND:-mlx}"
export ROBOT_TTS_BACKEND="${ROBOT_TTS_BACKEND:-qwen3-mlx}"
export ROBOT_PRELOAD_MODELS=1
exec "$ROBOT_ROOT/ai-service/.venv/bin/robot-service" --data "$ROBOT_ROOT/runtime" \
  serve --bind "${ROBOT_BIND:-0.0.0.0}" --port "${ROBOT_PORT:-8766}"

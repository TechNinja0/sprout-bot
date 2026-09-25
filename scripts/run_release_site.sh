#!/usr/bin/env bash
# 前台运行局域网下载官网（默认 0.0.0.0:8767，纯 HTTP 只读分发，无任何凭据）。
# 发布新版本不需要重启本服务；archive 后网页即时生效。
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

exec "$ROBOT_ROOT/ai-service/.venv/bin/python" "$ROBOT_ROOT/scripts/release_site.py" serve \
  --bind "${RELEASE_BIND:-0.0.0.0}" --port "${RELEASE_PORT:-8767}" \
  --releases-dir "${ROBOT_ROOT}/runtime/releases"

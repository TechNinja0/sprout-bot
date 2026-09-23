#!/usr/bin/env bash
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROBOT_OLLAMA="${ROBOT_OLLAMA:-$ROBOT_ROOT/.tools/ollama/ollama}"
export OLLAMA_MODELS="$ROBOT_ROOT/runtime/models/ollama"
export OLLAMA_HOST=127.0.0.1:11435
export OLLAMA_NO_CLOUD=1
exec "$ROBOT_OLLAMA" serve

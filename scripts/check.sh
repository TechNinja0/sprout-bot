#!/usr/bin/env bash
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROBOT_ROOT"
ai-service/.venv/bin/python -m ruff check ai-service/robot_service ai-service/tests scripts
ai-service/.venv/bin/python -m pytest ai-service/tests -q
c++ -std=c++17 -Wall -Wextra -Werror -Imotion-firmware/include motion-firmware/tests/safety_test.cpp -o /tmp/family-robot-safety-test
/tmp/family-robot-safety-test
android-app/gradlew -p android-app :core:test :app:assembleDebug :app:lintDebug

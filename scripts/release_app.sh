#!/usr/bin/env bash
# 构建最新 Android APK 并自动发布到 runtime/releases（官网与家长端升级共用）。
# 用法：bash scripts/release_app.sh [-r|--release] "本次更新说明"
#   --release 使用 runtime/android-signing 中的正式签名构建发行版；默认构建 debug 版。
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 本机构建环境兜底（已设置则不覆盖），避免 ANDROID_SDK_ROOT/ANDROID_HOME 双变量冲突
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  unset ANDROID_SDK_ROOT
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi

MODE="debug"
if [[ "${1:-}" == "-r" || "${1:-}" == "--release" ]]; then
  MODE="release"
  shift
fi
NOTES="${1:-}"

cd "$ROBOT_ROOT/android-app"
if [[ "$MODE" == "release" ]]; then
  ./gradlew -q :app:assembleRelease
  APK="$ROBOT_ROOT/android-app/app/build/outputs/apk/release/app-release.apk"
  if [[ ! -f "$APK" ]]; then
    echo "未生成已签名的 release APK：请确认 runtime/android-signing/ 下 release.jks 与 keystore.properties 存在。" >&2
    exit 2
  fi
else
  ./gradlew -q :app:assembleDebug
  APK="$ROBOT_ROOT/android-app/app/build/outputs/apk/debug/app-debug.apk"
fi

exec "$ROBOT_ROOT/ai-service/.venv/bin/python" "$ROBOT_ROOT/scripts/release_site.py" archive \
  --apk "$APK" --channel "$MODE" ${NOTES:+--notes "$NOTES"}

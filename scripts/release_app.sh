#!/usr/bin/env bash
# 构建最新 Android APK 并自动发布到 runtime/releases（官网与家长端升级共用）。
# 用法：bash scripts/release_app.sh [-r|--release] [--force] [--version X.Y.Z] [--no-bump] "本次更新说明"
#   --release 使用 runtime/android-signing 中的正式签名构建发行版；默认构建 debug 版。
#   --force   已在官网发布过相同 versionCode 时重新发布（如应用改名换包名后重新归档）。
#   --version 指定本次 versionName（如升 minor：0.2.0）；versionCode 始终自动 +1。
#   --no-bump 不自动递增版本号，适合重发当前版本（配合 --force）。
# 默认行为：每次发布自动把 versionCode +1、versionName 的 patch +1（0.1.0 → 0.1.1），无需手动改 build.gradle.kts。
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

MODE="debug"; FORCE=""; NOBUMP=""; VERSION=""; NOTES=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -r|--release) MODE="release" ;;
    --force) FORCE=1 ;;
    --no-bump) NOBUMP=1 ;;
    --version)
      VERSION="${2:-}"
      if [[ -z "$VERSION" ]]; then
        echo "--version 需要版本名参数（如 0.2.0）" >&2
        exit 2
      fi
      shift ;;
    *) NOTES="$1" ;;
  esac
  shift
done

PY="$ROBOT_ROOT/ai-service/.venv/bin/python"
if [[ -z "$NOBUMP" ]]; then
  if [[ -n "$VERSION" ]]; then
    "$PY" "$ROBOT_ROOT/scripts/release_site.py" bump --set "$VERSION"
  else
    "$PY" "$ROBOT_ROOT/scripts/release_site.py" bump --part patch
  fi
fi

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

exec "$PY" "$ROBOT_ROOT/scripts/release_site.py" archive \
  --apk "$APK" --channel "$MODE" ${NOTES:+--notes "$NOTES"} ${FORCE:+--force}

#!/usr/bin/env bash
# 服务端一键部署入口；只检查模式绝不安装系统工具。
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROBOT_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --check) ROBOT_CHECK=1 ;;
    -h|--help)
      cat <<'HELP'
用法：bash scripts/setup_mac.sh [--check] [--no-start] [--host 局域网IP] [--port 8766]
                           [--timeout 420] [--tts-endpoint https://huggingface.co]

默认检测并安装 Apple Silicon Mac 服务端环境、全部当前服务端模型、初始化身份，
配置当前用户的登录自启动，启动服务并等待模型预热成功。可以重复执行。
--check       只检查；不安装、不下载、不初始化、不启动或重启服务。
--no-start    安装/检查静态环境，不配置常驻服务，不检查在线健康。
--host        新身份使用的局域网 IPv4 地址；省略时自动检测。
--port        家庭服务端口；省略时沿用本脚本上次配置，首次为 8766。
--timeout     等待预热的秒数，默认 420。
--tts-endpoint 只覆盖 Qwen TTS 的下载站点，仍校验固定版本和哈希。

不安装 Android SDK、JDK 或 Android Studio，不构建 APK。
首次系统工具安装可能要求 macOS 确认或管理员密码。请使用普通用户运行。
退出码：0 全部所选检查通过；1 检查或安装失败；2 参数/平台不支持。
HELP
      exit 0 ;;
  esac
done
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo '仅支持 Apple Silicon Mac 的原生 arm64 终端；Intel/Rosetta 不适用。' >&2
  exit 2
fi
if [[ "$(id -u)" == 0 ]]; then
  echo '请使用普通用户执行，不要 sudo 运行整个脚本。系统安装器会按需请求权限。' >&2
  exit 2
fi

# 先验证参数，避免拼错参数时意外安装系统工具。
ROBOT_ARGS=("$@")
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check|--no-start) shift ;;
    --host|--port|--timeout|--tts-endpoint)
      if [[ $# -lt 2 || "$2" == --* ]]; then
        echo "参数 $1 缺少值" >&2; exit 2
      fi
      if [[ "$1" == --port || "$1" == --timeout ]]; then
        if [[ ! "$2" =~ ^[0-9]+$ ]]; then
          echo "$1 需要正整数" >&2; exit 2
        fi
        if [[ ${#2} -gt 9 ]] || (( 10#$2 < 1 )); then
          echo "$1 数值超出范围" >&2; exit 2
        fi
        if [[ "$1" == --port ]] && (( 10#$2 < 1024 || 10#$2 > 65535 || 10#$2 == 11435 )); then
          echo '--port 需要为 1024—65535，排除 Ollama 的 11435' >&2; exit 2
        fi
      fi
      if [[ "$1" == --tts-endpoint && "$2" != https://* ]]; then
        echo '--tts-endpoint 必须使用 HTTPS' >&2; exit 2
      fi
      shift 2 ;;
    *) echo "未知参数：$1；使用 --help 查看帮助。" >&2; exit 2 ;;
  esac
done

if ! /usr/bin/xcrun --find swiftc >/dev/null 2>&1; then
  if [[ "$ROBOT_CHECK" == 1 ]]; then
    echo '[缺失] Apple Command Line Tools（OCR 编译需要）' >&2
  else
    echo '正在请求安装 Apple Command Line Tools，请完成 macOS 弹窗。'
    /usr/bin/xcode-select --install || true
    echo '完成系统安装后，请重新执行相同命令继续；已有内容会保留。' >&2
    exit 1
  fi
fi

ROBOT_PYTHON_FOUND=''
for candidate in "${ROBOT_PYTHON:-}" "$ROBOT_ROOT/ai-service/.venv/bin/python" \
  /opt/homebrew/bin/python3.12 /opt/homebrew/opt/python@3.12/bin/python3.12 python3.12; do
  if [[ -n "$candidate" ]] && "$candidate" -c \
    'import platform,sys; sys.exit(not (sys.version_info[:2] == (3,12) and platform.machine() == "arm64"))' \
    >/dev/null 2>&1; then
    ROBOT_PYTHON_FOUND="$candidate"
    break
  fi
done
if [[ -z "$ROBOT_PYTHON_FOUND" ]]; then
  if [[ "$ROBOT_CHECK" == 1 ]]; then
    echo '[缺失] 原生 arm64 Python 3.12，无法继续检查 Python 依赖；运行不带 --check 的命令安装。' >&2
    exit 1
  fi
  if [[ ! -x /opt/homebrew/bin/brew ]]; then
    echo '安装官方 Homebrew（安装器可能要求管理员密码）……'
    ROBOT_BREW_INSTALLER="$(mktemp -t robot-homebrew)"
    trap 'rm -f "$ROBOT_BREW_INSTALLER"' EXIT
    /usr/bin/curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
      https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh \
      --output "$ROBOT_BREW_INSTALLER"
    /bin/bash "$ROBOT_BREW_INSTALLER"
    rm -f "$ROBOT_BREW_INSTALLER"
    trap - EXIT
  fi
  /opt/homebrew/bin/brew install python@3.12
  ROBOT_PYTHON_FOUND=/opt/homebrew/opt/python@3.12/bin/python3.12
fi
export PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
exec "$ROBOT_PYTHON_FOUND" -B "$ROBOT_ROOT/scripts/setup_mac.py" ${ROBOT_ARGS[@]+"${ROBOT_ARGS[@]}"}

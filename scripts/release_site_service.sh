#!/usr/bin/env bash
# 把局域网下载官网（scripts/run_release_site.sh，默认 0.0.0.0:8767）交给 launchd 托管：
# 关闭终端仍运行、异常退出后自动重启、登录 macOS 后自动启动（仅官网，不影响家庭服务）。
# 用法：bash scripts/release_site_service.sh {install|start|stop|restart|status|uninstall}
set -euo pipefail
ROBOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="org.familyrobot.release-site"
DOMAIN="gui/$(id -u)"
AGENT="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$ROBOT_ROOT/runtime/logs/$LABEL.log"
PORT="${RELEASE_PORT:-8767}"
BIND="${RELEASE_BIND:-0.0.0.0}"

python_bin() {
  if [[ -x "$ROBOT_ROOT/ai-service/.venv/bin/python" ]]; then
    printf '%s' "$ROBOT_ROOT/ai-service/.venv/bin/python"
  else
    command -v python3
  fi
}

loaded() { launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; }
port_busy() { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; }
python_real() {
  "$(python_bin)" -c 'import os,sys;print(os.path.realpath(sys.executable))' 2>/dev/null || echo "（无法解析）"
}

lan_ip() {
  local iface ip
  for iface in en0 en1 en2; do
    if ip=$(/usr/sbin/ipconfig getifaddr "$iface" 2>/dev/null) && [[ -n "$ip" ]]; then
      printf '%s' "$ip"
      return 0
    fi
  done
  printf '127.0.0.1'
}

write_plist() {
  mkdir -p "$HOME/Library/LaunchAgents" "$ROBOT_ROOT/runtime/logs"
  # 不经 bash 启动：macOS 隐私保护会拒绝 launchd 上下文里 /bin/bash 读取 ~/Documents 下的项目文件，
  # 而 Python 可直接读取；run_release_site.sh 继续用于终端前台运行。
  "$(python_bin)" - "$AGENT" "$ROBOT_ROOT" "$LABEL" "$PORT" "$BIND" <<'PY'
import os, plistlib, sys

agent, root, label, port, bind = sys.argv[1:6]
spec = {
    "Label": label,
    "ProgramArguments": [
        "/usr/bin/caffeinate", "-i", "-s",
        os.path.join(root, "ai-service/.venv/bin/python"),
        os.path.join(root, "scripts/release_site.py"), "serve",
        "--bind", bind,
        "--port", port,
        "--releases-dir", os.path.join(root, "runtime/releases"),
    ],
    "EnvironmentVariables": {"PYTHONDONTWRITEBYTECODE": "1"},
    "RunAtLoad": True,
    "KeepAlive": True,
    "ThrottleInterval": 10,
    "StandardOutPath": os.path.join(root, "runtime/logs", label + ".log"),
    "StandardErrorPath": os.path.join(root, "runtime/logs", label + ".log"),
}
temp = agent + ".tmp"
with open(temp, "wb") as file:
    plistlib.dump(spec, file)
os.chmod(temp, 0o600)
os.replace(temp, agent)
PY
  echo "已写入 $AGENT"
}

wait_health() {
  for _ in {1..20}; do
    if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
      echo "官网已就绪：http://$(lan_ip):$PORT"
      return 0
    fi
    sleep 1
  done
  if grep -q "Operation not permitted" "$LOG" 2>/dev/null; then
    cat >&2 <<MSG
等待官网就绪超时：launchd 进程可能被 macOS 隐私保护拦截（日志含 Operation not permitted）。
处理：在“系统设置 → 隐私与安全性 → 完全磁盘访问权限”中添加 Python 后重试：
  $(python_real)
然后执行 bash scripts/release_site_service.sh restart
MSG
  else
    echo "等待官网就绪超时；请查看日志 $LOG" >&2
  fi
  return 1
}

install_agent() {
  if [[ ! -x "$ROBOT_ROOT/ai-service/.venv/bin/python" ]]; then
    echo "缺少 ai-service/.venv/bin/python；请先按《Mac服务端一键部署》准备 Python 环境。" >&2
    exit 1
  fi
  if loaded; then
    echo "已安装托管（${LABEL}），重新加载配置…"
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    for _ in {1..10}; do port_busy || break; sleep 1; done
  elif port_busy; then
    echo "端口 $PORT 已被其他进程占用；不会终止该进程。请先停止它（例如在原终端 Ctrl-C 或结束旧后台进程）再执行 install。" >&2
    exit 1
  fi
  write_plist
  launchctl bootstrap "$DOMAIN" "$AGENT"
  wait_health
}

case "${1:-status}" in
  install) install_agent ;;
  start)
    [[ -f "$AGENT" ]] || { echo "尚未安装：先执行 bash scripts/release_site_service.sh install" >&2; exit 1; }
    if loaded; then
      echo "已在托管中。"
    else
      launchctl bootstrap "$DOMAIN" "$AGENT"
    fi
    wait_health
    ;;
  stop)
    if loaded; then
      launchctl bootout "$DOMAIN/$LABEL"
      echo "已停止本次登录会话的官网托管；执行 start 可再次启动，下次登录仍会自动启动。"
    else
      echo "未在托管中。"
    fi
    ;;
  restart)
    if ! loaded; then
      echo "未在托管中：先执行 bash scripts/release_site_service.sh install" >&2
      exit 1
    fi
    launchctl kickstart -k "$DOMAIN/$LABEL"
    wait_health
    ;;
  status)
    if output=$(launchctl print "$DOMAIN/$LABEL" 2>/dev/null); then
      echo "$LABEL"
      echo "$output" | sed -n -E '/^[[:space:]]+(state|pid|last exit code) =/p' | head -3
      if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
        echo "健康检查通过：http://$(lan_ip):$PORT"
      else
        echo "健康检查失败（端口 $PORT 无响应）；日志：$LOG"
      fi
    else
      echo "未加载（执行 install 安装托管）"
    fi
    ;;
  uninstall)
    if loaded; then
      launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    fi
    if [[ -f "$AGENT" ]]; then
      rm -f "$AGENT"
    fi
    echo "已卸载官网托管并删除 plist；已发布的 APK 不受影响，可用 run_release_site.sh 前台运行。"
    ;;
  *) echo "用法：bash scripts/release_site_service.sh {install|start|stop|restart|status|uninstall}" >&2; exit 2 ;;
esac

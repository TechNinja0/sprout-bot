#!/usr/bin/env bash
# 管理本机已安装的 macOS LaunchAgents，不依赖 ADB。
set -euo pipefail
ROBOT_DOMAIN="gui/$(id -u)"
ROBOT_AGENTS=(org.familyrobot.ollama org.familyrobot.service)

start_services() {
  for label in "${ROBOT_AGENTS[@]}"; do
    if ! launchctl print "$ROBOT_DOMAIN/$label" >/dev/null 2>&1; then
      plist="$HOME/Library/LaunchAgents/$label.plist"
      if [[ ! -f "$plist" ]]; then
        echo "缺少 ${plist}；本机尚未配置后台托管，可用 run_home_service.sh 前台启动。" >&2
        exit 1
      fi
      launchctl bootstrap "$ROBOT_DOMAIN" "$plist"
    fi
    launchctl kickstart "$ROBOT_DOMAIN/$label"
  done
  echo "启动请求已提交，模型预热需要一些时间；可运行 status 查看进程。"
}

case "${1:-start}" in
  start) start_services ;;
  restart)
    start_services
    launchctl kill SIGTERM "$ROBOT_DOMAIN/org.familyrobot.service"
    echo "家庭 HTTPS 服务已请求重启。"
    ;;
  stop)
    for label in org.familyrobot.service org.familyrobot.ollama; do
      if launchctl print "$ROBOT_DOMAIN/$label" >/dev/null 2>&1; then
        launchctl bootout "$ROBOT_DOMAIN/$label"
      fi
    done
    echo "本次登录会话的家庭服务已停止；下次登录仍会自动启动。"
    ;;
  status)
    for label in "${ROBOT_AGENTS[@]}"; do
      echo "$label"
      if output=$(launchctl print "$ROBOT_DOMAIN/$label" 2>/dev/null); then
        echo "$output" | sed -n -E '/^	(state|pid|last exit code) =/p'
      else
        echo "  未加载"
      fi
    done
    ;;
  *) echo "用法：bash scripts/home_service.sh {start|restart|stop|status}" >&2; exit 2 ;;
esac

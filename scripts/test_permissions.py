#!/usr/bin/env python3
"""测试安装的OS权限撤销/冷启；finally恢复原授权，不读取用户其他应用。"""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
args = p.parse_args()
base = [args.adb, "-s", args.serial]
package = "org.familyrobot.app"
output = Path(__file__).resolve().parents[1] / ".artifacts/development"
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.run([*base, *parts], capture_output=True, check=True).stdout


permissions = ["CAMERA", "RECORD_AUDIO"]
info = adb("shell", "dumpsys", "package", package).decode()
baseline = {
    permission: bool(
        re.search(r"android.permission." + permission + r": granted=true", info)
    )
    for permission in permissions
}
report = {
    "scope": "测试App的OS权限撤销后冷启动，非家庭声学测试",
    "passed": False,
    "checks": [],
}
(output / "permission-safety.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2)
)


def launch():
    adb("shell", "am", "force-stop", package)
    adb("shell", "am", "start", "-W", "-n", package + "/.MainActivity")
    time.sleep(5)


def running(permission):
    ops = adb("shell", "cmd", "appops", "get", package, permission).decode()
    if "No operations." in ops:
        return False
    assert permission + ":" in ops and "Unknown command" not in ops, (
        "AppOps输出格式不可识别"
    )
    return "(running)" in ops or "running=true" in ops


try:
    adb("shell", "pm", "grant", package, "android.permission.RECORD_AUDIO")
    adb("shell", "pm", "revoke", package, "android.permission.CAMERA")
    launch()
    assert not running("CAMERA") and running("RECORD_AUDIO"), (
        "撤销相机后保留本地语音入口"
    )
    report["checks"].append("camera_denied_microphone_available")
    adb("shell", "pm", "revoke", package, "android.permission.RECORD_AUDIO")
    launch()
    assert not running("CAMERA") and not running("RECORD_AUDIO"), "全部撤销后不可采集"
    (output / "permission-denied-face.png").write_bytes(
        adb("exec-out", "screencap", "-p")
    )
    report["checks"].append("both_denied_no_capture")
    for permission in permissions:
        adb("shell", "pm", "grant", package, "android.permission." + permission)
    launch()
    assert running("RECORD_AUDIO") and not running("CAMERA"), (
        "恢复权限后仅待机唤醒，不擅自拍摄"
    )
    report["checks"].append("restored_standby_microphone_only")
    report["passed"] = True
finally:
    for permission, granted in baseline.items():
        adb(
            "shell",
            "pm",
            "grant" if granted else "revoke",
            package,
            "android.permission." + permission,
        )
    launch()
    (output / "permission-safety.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2)
    )
print("OS权限撤销/冷启/恢复通过")

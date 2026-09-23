#!/usr/bin/env python3
"""真实5组30秒等待、5组10分钟会话和20次相机；不压缩墙钟。"""

import argparse
import fcntl
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
p.add_argument("--focus", choices=["full", "idle"], default="full")
a = p.parse_args()
if a.focus == "idle":
    lock = (ROOT / ".artifacts/development/endurance-suite.lock").open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit("其他手机测试正在运行，拒绝启动诊断") from None
base = [a.adb, "-s", a.serial]
output = (
    ROOT
    / ".artifacts/development"
    / (
        ("session-idle-diagnostic-" if a.focus == "idle" else "session-timing-")
        + time.strftime("%Y%m%d-%H%M%S")
    )
)
output.mkdir(parents=True)
if a.focus == "idle":
    subprocess.run(
        [
            *base,
            "install",
            "-r",
            str(
                ROOT
                / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
            ),
        ],
        check=True,
    )
subprocess.run([*base, "reverse", "tcp:8765", "tcp:8765"], check=True)
with (output / "instrumentation.txt").open("w") as log:
    process = subprocess.Popen(
        [
            *base,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "timingFocus",
            a.focus,
            "-e",
            "class",
            "org.familyrobot.app.SessionTimingTest",
            "org.familyrobot.app.test/androidx.test.runner.AndroidJUnitRunner",
        ],
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    caffeine = (
        subprocess.Popen(["caffeinate", "-i", "-w", str(process.pid)])
        if sys.platform == "darwin"
        else None
    )
    began = time.monotonic()
    try:
        while process.poll() is None:
            if time.monotonic() - began > (300 if a.focus == "idle" else 4800):
                raise TimeoutError("计时验收超过诊断5分钟或完整80分钟上限")
            time.sleep(10)
            result = subprocess.run(
                [
                    *base,
                    "exec-out",
                    "run-as",
                    "org.familyrobot.app",
                    "cat",
                    "files/session-timing.jsonl",
                ],
                capture_output=True,
            )
            if result.returncode == 0:
                (output / "samples.jsonl").write_bytes(result.stdout)
    except BaseException:
        process.terminate()
        subprocess.run(
            [*base, "shell", "am", "force-stop", "org.familyrobot.app"], check=True
        )
        raise
    finally:
        if caffeine:
            caffeine.terminate()
passed = "OK (1 test)" in (output / "instrumentation.txt").read_text()
(output / "run.json").write_text(
    json.dumps(
        {
            "elapsedSeconds": time.monotonic() - began,
            "passed": passed,
            "diagnosticOnly": a.focus == "idle",
            "scope": "真实墙钟/相机/提示音；有效输入事件注入，不是声学验收",
        },
        ensure_ascii=False,
        indent=2,
    )
)
print(output)
if not passed:
    raise SystemExit("会话计时真机验收失败，保留记录")

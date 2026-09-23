#!/usr/bin/env python3
"""长测结束后验证实际时段/额度与离线进程重启；不修改手机系统时钟。"""

import argparse
import fcntl
import hashlib
import io
import json
import subprocess
import time
from pathlib import Path

import av

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
p.add_argument("--restore-only", action="store_true")
a = p.parse_args()
base = [a.adb, "-s", a.serial]
artifacts = ROOT / ".artifacts/development"
artifacts.mkdir(parents=True, exist_ok=True)
lock = (artifacts / "endurance-suite.lock").open("a")
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit("正式长测正在占用手机，本测试拒绝安装或执行") from None
output = artifacts / ("policy-boundary-" + time.strftime("%Y%m%d-%H%M%S"))
output.mkdir()
apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
fingerprint = hashlib.sha256(apk.read_bytes()).hexdigest()


def adb(*args, **kwargs):
    return subprocess.run([*base, *args], check=True, **kwargs)


def set_marker(value):
    adb(
        "shell",
        "run-as",
        "org.familyrobot.app",
        "sh",
        "-c",
        "'cat > files/policy-boundary-state'",
        input=value.encode(),
    )


def run_stage(method, limit):
    with (output / f"{method}.txt").open("w") as log:
        process = subprocess.Popen(
            [
                *base,
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                "org.familyrobot.app.PolicyBoundaryTest#" + method,
                "org.familyrobot.app.test/androidx.test.runner.AndroidJUnitRunner",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        started = time.monotonic()
        try:
            while process.poll() is None:
                if time.monotonic() - started > limit:
                    raise TimeoutError(f"{method} 超时")
                time.sleep(1)
                if method != "realQuietBoundaryAndQuota":
                    continue
                state = subprocess.run(
                    [
                        *base,
                        "exec-out",
                        "run-as",
                        "org.familyrobot.app",
                        "cat",
                        "files/policy-boundary-state",
                    ],
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                if state == "disconnect":
                    adb("reverse", "--remove", "tcp:8765")
                    set_marker("offline")
                elif state == "reconnect":
                    adb("reverse", "tcp:8765", "tcp:8765")
                    set_marker("online")
        except BaseException:
            process.terminate()
            adb("shell", "am", "force-stop", "org.familyrobot.app")
            raise
    if "OK (1 test)" not in (output / f"{method}.txt").read_text():
        raise RuntimeError(f"{method} 未通过，见保留日志")


installed = (
    adb("shell", "pm", "path", "org.familyrobot.app", capture_output=True, text=True)
    .stdout.strip()
    .splitlines()
)
if len(installed) != 1 or not installed[0].startswith("package:/data/app/"):
    raise SystemExit("无法核对单APK安装来源，停止测试")
installed_bytes = adb(
    "exec-out", "cat", installed[0].removeprefix("package:"), capture_output=True
).stdout
if hashlib.sha256(installed_bytes).hexdigest() != fingerprint:
    raise SystemExit("手机主APK与本地构建不一致，先核对版本；本脚本不会覆盖主APK")
del installed_bytes
adb(
    "install",
    "-r",
    str(
        ROOT
        / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    ),
)
adb("reverse", "tcp:8765", "tcp:8765")
adb(
    "shell",
    "run-as",
    "org.familyrobot.app",
    "rm",
    "-f",
    "files/policy-boundary-network.jsonl",
)
report = {
    "scope": "真实分钟边界/额度及离线进程重启；不含系统重启或修改系统时间",
    "appSha256": fingerprint,
}
try:
    if not a.restore_only:
        audio = io.BytesIO()
        with av.open(audio, "w", format="mp3") as encoded:
            stream = encoded.add_stream("libmp3lame", rate=24000)
            stream.layout = "mono"
            for _ in range(8):
                with av.open(
                    str(ROOT / "android-app/app/src/main/assets/prompts/unclear.wav")
                ) as original:
                    for frame in original.decode(audio=0):
                        frame.pts = None
                        for packet in stream.encode(frame):
                            encoded.mux(packet)
            for packet in stream.encode():
                encoded.mux(packet)
        adb(
            "shell",
            "run-as",
            "org.familyrobot.app",
            "sh",
            "-c",
            "'cat > files/test-audio.mp3'",
            input=audio.getvalue(),
        )
        run_stage("realQuietBoundaryAndQuota", 420)
        adb("shell", "am", "force-stop", "org.familyrobot.app")
        pid = subprocess.run(
            [*base, "shell", "pidof", "org.familyrobot.app"], capture_output=True
        )
        if pid.stdout.strip():
            raise RuntimeError("旧进程未终止，不能计为冷启动证据")
        adb("reverse", "--remove", "tcp:8765")
        run_stage("quotaAfterProcessRestart", 60)
        report["scenariosPassed"] = True
except BaseException as error:
    report["error"] = f"{type(error).__name__}: {error}"
finally:
    try:
        adb("reverse", "tcp:8765", "tcp:8765")
        run_stage("restoreConfiguration", 60)
        report["configurationRestored"] = True
    except BaseException as error:
        report["restoreError"] = f"{type(error).__name__}: {error}"
    result = subprocess.run(
        [
            *base,
            "exec-out",
            "run-as",
            "org.familyrobot.app",
            "cat",
            "files/policy-boundary-results.jsonl",
        ],
        capture_output=True,
    )
    if result.returncode == 0:
        (output / "samples.jsonl").write_bytes(result.stdout)
    network = subprocess.run(
        [
            *base,
            "exec-out",
            "run-as",
            "org.familyrobot.app",
            "cat",
            "files/policy-boundary-network.jsonl",
        ],
        capture_output=True,
    )
    if network.returncode == 0:
        (output / "network.jsonl").write_bytes(network.stdout)
    report["appUnchanged"] = hashlib.sha256(apk.read_bytes()).hexdigest() == fingerprint
    report["passed"] = (
        not report.get("error")
        and not report.get("restoreError")
        and report["appUnchanged"]
        and (a.restore_only or report.get("scenariosPassed", False))
    )
    report["restoreOnly"] = a.restore_only
    (output / "run.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(output)
if not report["passed"]:
    raise SystemExit("时段/额度测试未通过；配置恢复失败时先执行 --restore-only")

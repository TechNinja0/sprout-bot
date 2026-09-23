#!/usr/bin/env python3
"""依次完成真实会话计时、4小时混合与24小时待机；任一步失败即停止，不伪造时长。"""

import argparse
import fcntl
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
p.add_argument(
    "--stages",
    nargs="+",
    choices=["session-timing", "mixed", "standby"],
    default=["session-timing", "mixed", "standby"],
    help="选择独立验收阶段；省略的阶段明确记为未运行，不能由其他阶段代替",
)
a = p.parse_args()
canonical_stages = ["session-timing", "mixed", "standby"]
if a.stages != [stage for stage in canonical_stages if stage in a.stages]:
    p.error("阶段不能重复，且须按session-timing、mixed、standby顺序")
output = ROOT / ".artifacts/development"
output.mkdir(parents=True, exist_ok=True)
lock = (output / "endurance-suite.lock").open("w")
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit("已有长测套件运行，拒绝重复占用手机") from None


def source_hashes():
    paths = [
        *sorted((ROOT / "ai-service/robot_service").glob("*.py")),
        ROOT / "scripts/models.lock.json",
        ROOT / "scripts/tts-model.lock.json",
        ROOT / "ai-service/requirements-lock.txt",
        ROOT / "ai-service/requirements-apple-lock.txt",
        ROOT / "ai-service/requirements-tts-lock.txt",
        ROOT / "scripts/test_stability.py",
        ROOT / "scripts/endurance_suite.py",
        ROOT / "scripts/test_session_timing.py",
        ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk",
        ROOT
        / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]
    return {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in paths
    }


state = {
    "pid": os.getpid(),
    "startedAt": time.time(),
    "status": "running",
    "stages": [],
    "requestedStages": a.stages,
    "omittedStages": [stage for stage in canonical_stages if stage not in a.stages],
    "fullSuiteRequested": a.stages == canonical_stages,
    "sourceSha256": source_hashes(),
}


def save():
    temporary = output / "endurance-suite.json.partial"
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    temporary.replace(output / "endurance-suite.json")


save()
try:
    for mode in a.stages:
        if state["sourceSha256"] != source_hashes():
            raise RuntimeError("长测期间构建或服务源码改变，请重新建立完整证据")
        stage = {"mode": mode, "startedAt": time.time(), "status": "running"}
        state["stages"].append(stage)
        save()
        with (output / f"endurance-{mode}.log").open("w") as log:
            command = [
                sys.executable,
                str(
                    ROOT
                    / (
                        "scripts/test_session_timing.py"
                        if mode == "session-timing"
                        else "scripts/test_stability.py"
                    )
                ),
                "--serial",
                a.serial,
                "--adb",
                a.adb,
            ]
            if mode != "session-timing":
                command.extend(["--mode", mode])
            result = subprocess.run(
                command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT
            )

        stage.update(
            endedAt=time.time(), status="passed" if result.returncode == 0 else "failed"
        )
        save()
        if state["sourceSha256"] != source_hashes():
            raise RuntimeError("长测过程中源码或构建已改变，结果不能作为当前版本验收")
        if result.returncode:
            raise RuntimeError(f"{mode} 长测失败，保留现场记录，未启动后续阶段")
    state["status"] = "completed-awaiting-analysis"
except BaseException as error:
    state.update(status="failed", error=f"{type(error).__name__}: {error}")
    raise
finally:
    state["updatedAt"] = time.time()
    save()

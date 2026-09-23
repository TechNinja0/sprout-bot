#!/usr/bin/env python3
"""已配对测试身份各10次真实进程冷启动；不清除用户数据。"""

import argparse
import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
a = p.parse_args()
base = [a.adb, "-s", a.serial]
report = {"passed": False, "checks": []}
output = ROOT / ".artifacts/development/cold-start.json"


def adb(*args):
    return subprocess.run(
        [*base, *args], check=True, capture_output=True, text=True
    ).stdout


try:
    for role in ("parent", "robot"):
        setup = adb(
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "role",
            role,
            "-e",
            "class",
            "org.familyrobot.app.IdentitySeedTest",
            "org.familyrobot.app.test/androidx.test.runner.AndroidJUnitRunner",
        )
        assert "OK (1 test)" in setup, setup[-500:]
        for index in range(10):
            adb("shell", "am", "force-stop", "org.familyrobot.app")
            assert not subprocess.run(
                [*base, "shell", "pidof", "org.familyrobot.app"],
                capture_output=True,
                text=True,
            ).stdout.strip()
            adb("shell", "am", "start", "-W", "-n", "org.familyrobot.app/.MainActivity")
            time.sleep(3)
            adb("shell", "uiautomator", "dump", "/data/local/tmp/family-robot-cold.xml")
            xml = ET.fromstring(
                adb("shell", "cat", "/data/local/tmp/family-robot-cold.xml")
            )
            texts = [
                n.get("text")
                for n in xml.iter("node")
                if n.get("package") == "org.familyrobot.app" and n.get("text")
            ]
            assert "连接家庭机器人" not in texts
            if role == "parent":
                assert "家庭小伙伴" in texts, texts[:3]
            else:
                assert not texts, texts[:3]
            ops = adb(
                "shell", "cmd", "appops", "get", "org.familyrobot.app", "RECORD_AUDIO"
            )
            assert "RECORD_AUDIO:" in ops and "Unknown command" not in ops
            running = "(running)" in ops or "running=true" in ops
            assert running == (role == "robot"), (role, ops)
            report["checks"].append(
                {
                    "role": role,
                    "round": index + 1,
                    "identityRestored": True,
                    "microphoneRunning": running,
                }
            )
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    report["passed"] = True
finally:
    report["completedAt"] = time.time()
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print("家长/机器人各10次进程冷启动通过")

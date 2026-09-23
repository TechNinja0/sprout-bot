#!/usr/bin/env python3
"""Run prepared V8 instrumented tests; outage injection is limited to an isolated service."""

import argparse
import hashlib
import json
import os
import signal
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CLASSES = [
    "V8AcceptanceTest",
    "PairingQrTest",
    "BookImportUiTest",
    "ParentNavigationTest",
    "SettingsApplicationTest",
    "DiagnosticHardwareTest",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--server-pid", type=int, required=True)
    parser.add_argument("--classes", default=",".join(DEFAULT_CLASSES))
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    command = subprocess.check_output(
        ["ps", "-p", str(args.server_pid), "-o", "command="], text=True
    )
    if not all(
        value in command
        for value in (
            "robot_service.cli",
            "ui-audit/artifacts/",
            "serve",
            "--port 8876",
        )
    ):
        parser.error("只允许暂停 ui-audit/artifacts 下的 8876 端口隔离验收服务")
    classes = args.classes.split(",")
    if any(name.split("#")[0] not in DEFAULT_CLASSES for name in classes):
        parser.error("未登记的验收类")
    if not 30 <= args.timeout <= 1800:
        parser.error("超时范围为30—1800秒")
    args.out.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-P", str(args.adb_port), "-s", args.serial]
    subprocess.run(
        adb
        + [
            "shell",
            "run-as",
            "org.familyrobot.app",
            "rm",
            "-f",
            "files/v8-network-state",
        ],
        check=True,
    )
    apks = [
        ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk",
        ROOT
        / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]
    metadata = {
        "classes": classes,
        "started": time.time(),
        "apkSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in apks},
        "events": [],
    }
    paused = False
    with (args.out / "instrumentation.log").open("w") as log:
        process = subprocess.Popen(
            adb
            + [
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                ",".join("org.familyrobot.app." + name for name in classes),
                "org.familyrobot.app.test/androidx.test.runner.AndroidJUnitRunner",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + args.timeout
            while process.poll() is None:
                if time.monotonic() > deadline:
                    subprocess.run(
                        adb + ["shell", "am", "force-stop", "org.familyrobot.app"],
                        check=True,
                    )
                    process.wait(timeout=10)
                    raise TimeoutError("真机验收超时，不能计作通过")
                result = subprocess.run(
                    adb
                    + [
                        "exec-out",
                        "run-as",
                        "org.familyrobot.app",
                        "cat",
                        "files/v8-network-state",
                    ],
                    capture_output=True,
                    timeout=5,
                )
                state = result.stdout.decode(errors="replace").strip()
                if state == "disconnect" and not paused:
                    os.kill(args.server_pid, signal.SIGSTOP)
                    paused = True
                    metadata["events"].append(
                        {"event": "isolated-service-paused", "time": time.time()}
                    )
                elif state in ("reconnect", "done") and paused:
                    os.kill(args.server_pid, signal.SIGCONT)
                    paused = False
                    metadata["events"].append(
                        {"event": "isolated-service-resumed", "time": time.time()}
                    )
                time.sleep(1)
        finally:
            if paused:
                os.kill(args.server_pid, signal.SIGCONT)
            if process.poll() is None:
                subprocess.run(
                    adb + ["shell", "am", "force-stop", "org.familyrobot.app"],
                    check=False,
                )
                process.wait(timeout=10)
            metadata["finished"] = time.time()
            (args.out / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    result = (args.out / "instrumentation.log").read_text()
    print(result[-3500:])
    return 0 if "OK (" in result and "FAILURES!!!" not in result else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""真实墙钟手机长测。默认4小时；24小时需显式 --mode standby。只用于测试身份。"""

import argparse
import base64
import hashlib
import io
import json
import shutil
import subprocess
import sys
import time
import wave
from pathlib import Path

import numpy as np
from robot_service.model_worker import execute

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
p.add_argument("--mode", choices=["mixed", "standby"], default="mixed")
p.add_argument("--seconds", type=int)
a = p.parse_args()
seconds = a.seconds or (14400 if a.mode == "mixed" else 86400)
if not 60 <= seconds <= 86400:
    p.error("真实时长需60—86400秒")
base = [a.adb, "-s", a.serial]
output = (
    ROOT
    / ".artifacts/development"
    / ("stability-" + a.mode + "-" + time.strftime("%Y%m%d-%H%M%S"))
)
output.mkdir(parents=True)
fingerprints = {}
for path in [
    *sorted((ROOT / "ai-service/robot_service").glob("*.py")),
    ROOT / "scripts/models.lock.json",
    ROOT / "scripts/tts-model.lock.json",
    ROOT / "ai-service/requirements-lock.txt",
    ROOT / "ai-service/requirements-apple-lock.txt",
    ROOT / "ai-service/requirements-tts-lock.txt",
    ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk",
    ROOT
    / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
]:
    fingerprints[str(path.relative_to(ROOT))] = hashlib.sha256(
        path.read_bytes()
    ).hexdigest()
(output / "source-sha256.json").write_text(json.dumps(fingerprints, indent=2))
(output / "run.json").write_text(
    json.dumps(
        {
            "mode": a.mode,
            "seconds": seconds,
            "status": "starting",
            "formalDuration": seconds == (14400 if a.mode == "mixed" else 86400),
        },
        indent=2,
    )
)


def adb(*args, **kwargs):
    return subprocess.run([*base, *args], check=True, **kwargs)


adb(
    "install", "-r", str(ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk")
)
adb(
    "install",
    "-r",
    str(
        ROOT
        / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    ),
)
adb("reverse", "tcp:8765", "tcp:8765")
audio = execute(
    {
        "root": str(ROOT / "runtime/models"),
        "kind": "tts",
        "text": "你好，我是小伙伴，我们一起读书吧。",
        "sid": 45,
        "speed": 1.0,
    }
)
with wave.open(io.BytesIO(base64.b64decode(audio["audio"]))) as source:
    rate = source.getframerate()
    pcm = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
values = np.interp(
    np.arange(int(len(pcm) * 16000 / rate)) * rate / 16000, np.arange(len(pcm)), pcm
).astype("<i2")
speech = io.BytesIO()
with wave.open(speech, "wb") as dest:
    dest.setnchannels(1)
    dest.setsampwidth(2)
    dest.setframerate(16000)
    dest.writeframes(values.tobytes())
adb(
    "shell",
    "run-as",
    "org.familyrobot.app",
    "sh",
    "-c",
    "'cat > files/test-speech.wav'",
    input=speech.getvalue(),
)
log = (output / "instrumentation.txt").open("w")
process = subprocess.Popen(
    [
        *base,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "mode",
        a.mode,
        "-e",
        "seconds",
        str(seconds),
        "-e",
        "class",
        "org.familyrobot.app.StabilityTest",
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
started = time.monotonic()
interrupted = False
last_host_sample = -60.0
try:
    while process.poll() is None:
        if time.monotonic() - started > seconds + 180:
            raise TimeoutError("长测超时")
        time.sleep(10)
        if time.monotonic() - started - last_host_sample >= 60:
            last_host_sample = time.monotonic() - started
            rows = subprocess.run(
                ["ps", "-axo", "pid,ppid,rss,command"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout.splitlines()[1:]
            processes = []
            roots = set()
            ollama_roots = set()
            tts_roots = set()
            for row in rows:
                parts = row.strip().split(None, 3)
                if len(parts) != 4:
                    continue
                pid, parent_pid, rss, command = parts
                processes.append((int(pid), int(parent_pid), int(rss), command))
                if "-m robot_service.cli" in command and " serve" in command:
                    roots.add(int(pid))
                if "ollama serve" in command:
                    ollama_roots.add(int(pid))
                if "-m robot_service.tts_worker" in command:
                    tts_roots.add(int(pid))

            def descendants(root_pids):
                found = set(root_pids)
                for _ in range(4):
                    found.update(
                        pid
                        for pid, parent_pid, _, _ in processes
                        if parent_pid in found
                    )
                return found

            children = descendants(roots)
            ollama_children = descendants(ollama_roots)
            tts_children = descendants(tts_roots)
            service_rss = sum(rss for pid, _, rss, _ in processes if pid in children)
            ollama_rss = sum(
                rss for pid, _, rss, _ in processes if pid in ollama_children
            )
            with (output / "host-samples.jsonl").open("a") as host_log:
                host_log.write(
                    json.dumps(
                        {
                            "elapsedSeconds": round(last_host_sample, 1),
                            "serviceRssKb": service_rss,
                            "ollamaRssKb": ollama_rss,
                            "serviceProcesses": len(children),
                            "servicePids": sorted(roots),
                            "ttsPids": sorted(tts_roots),
                            "ttsRssKb": sum(
                                rss
                                for pid, _, rss, _ in processes
                                if pid in tts_children
                            ),
                            "diskFreeBytes": shutil.disk_usage(ROOT / "runtime").free,
                        }
                    )
                    + "\n"
                )
        result = subprocess.run(
            [
                *base,
                "exec-out",
                "run-as",
                "org.familyrobot.app",
                "cat",
                f"files/stability-{a.mode}.jsonl",
            ],
            capture_output=True,
        )
        if result.returncode == 0:
            (output / "samples.jsonl").write_bytes(result.stdout)
except BaseException:
    interrupted = True
    process.terminate()
    adb("shell", "am", "force-stop", "org.familyrobot.app")
    raise
finally:
    log.close()
    if caffeine:
        caffeine.terminate()
    result = subprocess.run(
        [
            *base,
            "exec-out",
            "run-as",
            "org.familyrobot.app",
            "cat",
            f"files/stability-{a.mode}.jsonl",
        ],
        capture_output=True,
    )
    if result.returncode == 0:
        (output / "samples.jsonl").write_bytes(result.stdout)
    passed = (
        not interrupted
        and "OK (1 test)" in (output / "instrumentation.txt").read_text()
    )
    (output / "run.json").write_text(
        json.dumps(
            {
                "mode": a.mode,
                "seconds": seconds,
                "elapsedSeconds": round(time.monotonic() - started, 1),
                "status": "passed" if passed else "failed",
                "formalDuration": seconds == (14400 if a.mode == "mixed" else 86400),
            },
            indent=2,
        )
    )
print(str(output), flush=True)
if not passed:
    raise SystemExit("长测未通过，见记录")

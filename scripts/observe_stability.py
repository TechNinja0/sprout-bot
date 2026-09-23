#!/usr/bin/env python3
"""接续观察仍存活的长测；不启动、安装或终止手机测试，不改写原失败结果。"""

import argparse
import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--phone-pid", type=int, required=True)
    args = parser.parse_args()
    run = args.run_dir.resolve()
    original = [
        json.loads(line) for line in (run / "samples.jsonl").read_text().splitlines()
    ]
    start = next(row for row in original if row["event"] == "start")
    assert (
        start["mode"] == "standby" and start["seconds"] == 86400 and start["qualifies"]
    )
    expected = json.loads((run / "source-sha256.json").read_text())
    lock = (ROOT / ".artifacts/development/endurance-suite.lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    output = run / ("observer-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir()
    state_file = ROOT / ".artifacts/development/endurance-observer.json"
    state = {
        "pid": os.getpid(),
        "phonePid": args.phone_pid,
        "runDirectory": str(run),
        "outputDirectory": str(output),
        "originalStart": start,
        "status": "running",
        "startedAt": time.time(),
        "scope": "接续观察；原宿主中断结果保留，完成后仍须人工分析",
    }
    base = [args.adb, "-s", args.serial]

    def adb(*parts):
        return subprocess.run([*base, *parts], capture_output=True, timeout=20)

    def event(kind, **fields):
        with (output / "events.jsonl").open("a") as stream:
            stream.write(
                json.dumps(
                    {"event": kind, "time": time.time(), **fields}, ensure_ascii=False
                )
                + "\n"
            )

    def save():
        state["updatedAt"] = time.time()
        temporary = state_file.with_suffix(".partial")
        temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(state_file)
        (output / "state.json").write_text(
            json.dumps(state, ensure_ascii=False, indent=2) + "\n"
        )

    def host_sample():
        listing = subprocess.run(
            ["ps", "-axo", "pid,ppid,rss,command"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.splitlines()[1:]
        processes = []
        for row in listing:
            fields = row.strip().split(None, 3)
            if len(fields) == 4:
                processes.append(
                    (int(fields[0]), int(fields[1]), int(fields[2]), fields[3])
                )

        def rss(token):
            children = {pid for pid, _, _, command in processes if token in command}
            for _ in range(4):
                children.update(
                    pid for pid, parent, _, _ in processes if parent in children
                )
            return sum(value for pid, _, value, _ in processes if pid in children)

        with (output / "host-samples.jsonl").open("a") as stream:
            stream.write(
                json.dumps(
                    {
                        "wallTimeMs": int(time.time() * 1000),
                        "serviceRssKb": rss("-m robot_service.cli"),
                        "ollamaRssKb": rss("ollama serve"),
                        "diskFreeBytes": shutil.disk_usage(ROOT / "runtime").free,
                    }
                )
                + "\n"
            )

    caffeine = subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
    last_progress = time.monotonic()
    last_elapsed = -1
    last_host = -60.0
    save()
    event("attached", phonePid=args.phone_pid)
    try:
        while True:
            mismatches = [
                path
                for path, value in expected.items()
                if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != value
            ]
            if mismatches:
                state.update(status="fingerprints-changed", mismatches=mismatches)
                break
            try:
                result = adb(
                    "exec-out",
                    "run-as",
                    "org.familyrobot.app",
                    "cat",
                    "files/stability-standby.jsonl",
                )
                if result.returncode != 0:
                    raise RuntimeError("无法读取手机采样")
                # 只解析完整行，手机可能正在追加下一行。
                lines = result.stdout.splitlines(keepends=True)
                data = b"".join(line for line in lines if line.endswith(b"\n"))
                rows = [json.loads(line) for line in data.splitlines()]
                if next(row for row in rows if row["event"] == "start") != start:
                    state["status"] = "different-test-detected"
                    break
                (output / "samples.jsonl").write_bytes(data)
                elapsed = max(row.get("elapsedMs", 0) for row in rows)
                if elapsed > last_elapsed:
                    last_progress = time.monotonic()
                    last_elapsed = elapsed
                state.update(elapsedMs=elapsed, latestEvent=rows[-1])
                terminal = [
                    row for row in rows if row["event"] in ("complete", "failed")
                ]
                if terminal:
                    state.update(
                        status="completed-awaiting-analysis"
                        if terminal[-1]["event"] == "complete"
                        and terminal[-1].get("passed")
                        and elapsed >= 86400000
                        else "device-failed",
                        terminalEvent=terminal[-1],
                    )
                    log = adb(
                        "logcat", "-d", "-v", "threadtime", "--pid", str(args.phone_pid)
                    )
                    (output / "terminal-logcat.txt").write_bytes(
                        log.stdout + log.stderr
                    )
                    break
                # shell 正常返回空值才代表 PID 不在；ADB 传输失败只记观察错误。
                pid = adb("shell", "pidof org.familyrobot.app || true")
                if pid.returncode != 0:
                    raise RuntimeError("PID查询连接失败，不能判定手机测试终止")
                if pid.stdout.strip() != str(args.phone_pid).encode():
                    # 再读下一轮，避免恰好在写入 complete 后结束进程的竞争。
                    time.sleep(2)
                    fresh = adb(
                        "exec-out",
                        "run-as",
                        "org.familyrobot.app",
                        "cat",
                        "files/stability-standby.jsonl",
                    )
                    if fresh.returncode != 0:
                        raise RuntimeError("复核采样连接失败，不能判定手机测试终止")
                    if fresh.stdout != result.stdout:
                        continue
                    state.update(
                        status="phone-process-ended",
                        observedPid=pid.stdout.decode().strip(),
                    )
                    break
                reverse = adb("reverse", "--list")
                if (
                    reverse.returncode == 0
                    and b"tcp:8765 tcp:8765" not in reverse.stdout
                ):
                    restored = adb("reverse", "tcp:8765", "tcp:8765")
                    event("usb-forward-restored", returnCode=restored.returncode)
                if time.monotonic() - last_host >= 60:
                    host_sample()
                    last_host = time.monotonic()
                if time.monotonic() - last_progress > 180:
                    state["status"] = "observation-stalled-phone-not-stopped"
                    break
            except (
                subprocess.TimeoutExpired,
                RuntimeError,
                json.JSONDecodeError,
            ) as error:
                event("observation-error", detail=str(error))
                if time.monotonic() - last_progress > 180:
                    state["status"] = "observation-unavailable-phone-not-stopped"
                    break
            save()
            time.sleep(10)
    except BaseException as error:
        state.update(
            status="observer-error-phone-not-stopped",
            error=f"{type(error).__name__}: {error}",
        )
        raise
    finally:
        save()
        caffeine.terminate()
    print(json.dumps(state, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

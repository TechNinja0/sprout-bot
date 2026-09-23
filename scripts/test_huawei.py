#!/usr/bin/env python3
"""已有ADB授权的手机联调；使用测试身份与原创图书，不上传私人资料。"""

import argparse
import fcntl
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="adb")
parser.add_argument(
    "--class-name",
    default="DeviceSmokeTest",
    choices=[
        "DeviceSmokeTest",
        "AudioPolicyTest",
        "MediaCompletionTest",
        "WakeFeedbackTest",
        "TouchInteractionTest",
        "ProgressOutboxTest",
        "ResourceVoiceRevisionTest",
        "BookWorkflowTest",
        "BookImportUiTest",
        "MicrophoneEnvironmentDiagnosticTest",
        "DiagnosticHardwareTest",
        "CameraFeedbackTest",
        "OriginalVoiceRequestsTest",
        "CameraUnavailableTest",
        "SettingsApplicationTest",
        "VisualRequestPrivacyTest",
        "VisualModelPipelineTest",
    ],
)
args = parser.parse_args()
base = [args.adb, "-s", args.serial]


def adb(*arguments, **kwargs):
    return subprocess.run([*base, *arguments], check=True, **kwargs)


output = ROOT / ".artifacts/development"
output.mkdir(parents=True, exist_ok=True)
lock = (output / "endurance-suite.lock").open("a")
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit("已有手机测试占用，本次拒绝安装或执行") from None
artifacts = {
    "DeviceSmokeTest": [
        "smoke-face.png",
        "smoke-management.png",
        "smoke-parent.png",
        "stop-latency.json",
    ],
    "AudioPolicyTest": [
        "audio-policy-result.json",
        "policy-blocked.png",
        "policy-muted.png",
    ],
    "MediaCompletionTest": [
        "media-completion-delayed.jsonl",
        "media-completion-normal.jsonl",
    ],
    "WakeFeedbackTest": [],
    "ProgressOutboxTest": ["progress-outbox-result.json"],
    "ResourceVoiceRevisionTest": ["resource-voice-revision.jsonl"],
    "BookWorkflowTest": ["book-workflow.jsonl"],
    "BookImportUiTest": ["book-import-ui.jsonl", "book-import-editor.png", "book-import-failure.xml", "book-import-failure.png"],
    "MicrophoneEnvironmentDiagnosticTest": ["microphone-environment.jsonl"],
    "DiagnosticHardwareTest": ["diagnostic-hardware.json"],
    "CameraFeedbackTest": ["camera-feedback.json"],
    "OriginalVoiceRequestsTest": ["original-voice-requests.json"],
    "CameraUnavailableTest": ["camera-unavailable.json"],
    "SettingsApplicationTest": ["settings-application.json"],
    "VisualRequestPrivacyTest": ["visual-request-privacy.json"],
    "VisualModelPipelineTest": ["visual-model-pipeline.json"],
    "TouchInteractionTest": [
        f"touch-gallery/{face}.png"
        for face in [
            "standby",
            "waking",
            "listening",
            "hearing",
            "thinking",
            "pet",
            "tickle",
            "speaking",
            "muted",
        ]
    ],
}[args.class_name]
run_output = output / (
    "huawei-" + args.class_name + "-" + time.strftime("%Y%m%d-%H%M%S")
)
run_output.mkdir()
apks = [
    ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk",
    ROOT
    / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
]
fingerprints = {
    str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks
}
for name in artifacts:
    prior = output / name
    if prior.is_file():
        (run_output / "previous" / name).parent.mkdir(parents=True, exist_ok=True)
        (run_output / "previous" / name).write_bytes(prior.read_bytes())
    (output / name).unlink(missing_ok=True)
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
for permission in ["RECORD_AUDIO", "CAMERA"]:
    adb(
        "shell",
        "pm",
        "grant",
        "org.familyrobot.app",
        "android.permission." + permission,
    )
subprocess.run(
    [
        sys.executable,
        "-m",
        "robot_service.cli",
        "--data",
        str(ROOT / "runtime"),
        "invite",
        "--host",
        "127.0.0.1",
    ],
    check=True,
)
adb("shell", "run-as", "org.familyrobot.app", "mkdir", "-p", "files")
adb(
    "shell",
    "run-as",
    "org.familyrobot.app",
    "rm",
    "-f",
    *["files/" + name for name in artifacts],
)
adb(
    "shell",
    "run-as",
    "org.familyrobot.app",
    "sh",
    "-c",
    "'cat > files/test-connection.json'",
    input=(ROOT / "runtime/connection.json").read_bytes(),
)
adb("shell", "run-as", "org.familyrobot.app", "rm", "-f", "files/offline-test-state")
if args.class_name in {"AudioPolicyTest", "MediaCompletionTest"}:
    import io

    import av

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
log_name = {
    "DeviceSmokeTest": "device-smoke.txt",
    "AudioPolicyTest": "audio-policy.txt",
    "MediaCompletionTest": "media-completion.txt",
    "WakeFeedbackTest": "wake-feedback.txt",
    "TouchInteractionTest": "touch-interaction.txt",
    "ProgressOutboxTest": "progress-outbox.txt",
    "ResourceVoiceRevisionTest": "resource-voice-revision.txt",
    "BookWorkflowTest": "book-workflow.txt",
    "BookImportUiTest": "book-import-ui.txt",
    "MicrophoneEnvironmentDiagnosticTest": "microphone-environment.txt",
    "DiagnosticHardwareTest": "diagnostic-hardware.txt",
    "CameraFeedbackTest": "camera-feedback.txt",
    "OriginalVoiceRequestsTest": "original-voice-requests.txt",
    "CameraUnavailableTest": "camera-unavailable.txt",
    "SettingsApplicationTest": "settings-application.txt",
    "VisualRequestPrivacyTest": "visual-request-privacy.txt",
    "VisualModelPipelineTest": "visual-model-pipeline.txt",
}[args.class_name]
log_path = run_output / log_name
log = log_path.open("w")
if args.class_name == "CameraUnavailableTest":
    adb("shell", "pm", "revoke", "org.familyrobot.app", "android.permission.CAMERA")
process = subprocess.Popen(
    [
        *base,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "offline",
        "true",
        "-e",
        "class",
        "org.familyrobot.app." + args.class_name,
        "org.familyrobot.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    stdout=log,
    stderr=subprocess.STDOUT,
)
started = time.monotonic()
disconnected = False
failure = None
try:
    while process.poll() is None:
        timeout = 480 if args.class_name == "BookWorkflowTest" else 360 if args.class_name == "ResourceVoiceRevisionTest" else 240
        if time.monotonic() - started > timeout:
            process.terminate()
            raise RuntimeError(f"真机测试超过{timeout}秒")
        state = subprocess.run(
            [
                *base,
                "shell",
                "run-as",
                "org.familyrobot.app",
                "cat",
                "files/offline-test-state",
            ],
            capture_output=True,
            text=True,
        ).stdout.strip()
        if state == "ready" and not disconnected:
            adb("reverse", "--remove", "tcp:8765")
            disconnected = True
            adb(
                "shell",
                "run-as",
                "org.familyrobot.app",
                "sh",
                "-c",
                "'echo disconnected > files/offline-test-state'",
            )
        elif state == "reconnect":
            adb("reverse", "tcp:8765", "tcp:8765")
            disconnected = False
            adb(
                "shell",
                "run-as",
                "org.familyrobot.app",
                "sh",
                "-c",
                "'echo connected > files/offline-test-state'",
            )
        time.sleep(0.4)
except (Exception, KeyboardInterrupt) as error:
    failure = f"{type(error).__name__}: {error}"
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    subprocess.run(
        [*base, "shell", "am", "force-stop", "org.familyrobot.app"], check=False
    )
finally:
    if args.class_name == "CameraUnavailableTest":
        subprocess.run([*base, "shell", "pm", "grant", "org.familyrobot.app", "android.permission.CAMERA"], check=False)
    subprocess.run([*base, "reverse", "tcp:8765", "tcp:8765"], check=False)
    log.close()
    (output / log_name).write_bytes(log_path.read_bytes())
    for name in artifacts:
        evidence = subprocess.run(
            [
                *base,
                "exec-out",
                "run-as",
                "org.familyrobot.app",
                "cat",
                "files/" + name,
            ],
            capture_output=True,
        )
        if evidence.returncode == 0:
            (run_output / name).parent.mkdir(parents=True, exist_ok=True)
            (output / name).parent.mkdir(parents=True, exist_ok=True)
            (run_output / name).write_bytes(evidence.stdout)
            (output / name).write_bytes(evidence.stdout)
result = log_path.read_text()
expected = 2 if args.class_name == "MediaCompletionTest" else 1
unchanged = fingerprints == {
    str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks
}
passed = (
    failure is None
    and process.returncode == 0
    and f"OK ({expected} test{'s' if expected > 1 else ''})" in result
    and unchanged
)
(run_output / "run.json").write_text(
    json.dumps(
        {
            "class": args.class_name,
            "elapsedSeconds": time.monotonic() - started,
            "apkSha256": fingerprints,
            "apksUnchanged": unchanged,
            "passed": passed,
            "error": failure,
        },
        ensure_ascii=False,
        indent=2,
    )
)
if not passed:
    raise SystemExit("真机未通过或构建被改变，检查" + str(run_output))
print(args.class_name + " 真机自测通过")

#!/usr/bin/env python3
"""macOS 服务端部署编排。由 setup_mac.sh 引导 Python；检查模式只读。"""

import argparse
import contextlib
import fcntl
import ipaddress
import json
import os
import platform
import plistlib
import shutil
import socket
import ssl
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import install_models
import install_tts

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "runtime"
STATE = ROOT / ".artifacts/mac-setup"
MODELS = DATA / "models"
CACHE = ROOT / ".artifacts/model-downloads"
# CPU ASR/Kokoro 是现有服务支持的备用后端；不包含历史对比模型或手机资源。
ASSETS = ["asr", "asr-mlx", "tts", "ollama-macos-arm64"]
LABELS = ("org.familyrobot.ollama", "org.familyrobot.service")
PYTHON = ROOT / "ai-service/.venv/bin/python"
TTS_PYTHON = ROOT / "ai-service/.venv-tts/bin/python"
OLLAMA = ROOT / ".tools/ollama/ollama"
DOMAIN = f"gui/{os.getuid()}"
AGENTS = Path.home() / "Library/LaunchAgents"


def run(argv, *, capture=False, env=None, timeout=None):
    return subprocess.run(
        [str(x) for x in argv],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=capture,
        check=False,
        timeout=timeout,
    )


def require(argv, **kwargs):
    result = run(argv, **kwargs)
    if result.returncode:
        detail = (result.stderr or result.stdout or "").strip()[-1500:]
        raise RuntimeError(f"命令失败：{' '.join(map(str, argv[:4]))}\n{detail}")
    return result


def atomic_write(path, data, mode=0o600):
    if (
        path.is_file()
        and path.read_bytes() == data
        and path.stat().st_mode & 0o777 == mode
    ):
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".setup-tmp")
    temp.write_bytes(data)
    temp.chmod(mode)
    temp.replace(path)


def loaded(label):
    return (
        run(["launchctl", "print", f"{DOMAIN}/{label}"], capture=True).returncode == 0
    )


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def dependency_errors(python, locks, service=False):
    if not python.is_file():
        return [f"缺少 {python.relative_to(ROOT)}"]
    # 标记仅有 Darwin/arm64 条件；入口已经限制平台，全部锁定包均适用。
    expected = {}
    for lock in locks:
        for line in lock.read_text().splitlines():
            requirement = line.split(";", 1)[0].strip()
            if requirement and not requirement.startswith("#"):
                name, version = requirement.split("==", 1)
                expected[name] = version
    code = """
import importlib.metadata as m, json, pathlib, platform, sys
errors = []
if sys.version_info[:2] != (3,12) or platform.machine() != 'arm64':
    errors.append('需要 arm64 Python 3.12')
if pathlib.Path(sys.prefix).resolve() != pathlib.Path(sys.argv[3]).resolve():
    errors.append('虚拟环境路径不匹配，请在当前机器重建')
for name, version in json.loads(sys.argv[1]).items():
    try:
        actual = m.version(name)
        if actual != version: errors.append(f'{name}: {actual} != {version}')
    except m.PackageNotFoundError: errors.append(f'{name}: 未安装')
if not errors:
    try:
        import mlx.core as mx
        mx.eval(mx.array([1]) + 1)
        if sys.argv[2] == 'service':
            import robot_service, mlx_whisper, faster_whisper, sherpa_onnx, av
            if pathlib.Path(robot_service.__file__).resolve().parent != pathlib.Path(sys.argv[4]):
                errors.append('robot_service 指向其他源码目录')
        else:
            from mlx_audio.tts.utils import load_model
    except Exception as exc: errors.append(f'原生依赖加载失败: {exc}')
print(json.dumps(errors, ensure_ascii=False))
sys.exit(bool(errors))
"""
    result = run(
        [
            python,
            "-B",
            "-c",
            code,
            json.dumps(expected),
            "service" if service else "tts",
            python.parent.parent,
            ROOT / "ai-service/robot_service",
        ],
        capture=True,
        timeout=90,
    )
    if result.returncode:
        return [
            (result.stdout + result.stderr).strip()[-3000:] or "Python 环境无法启动"
        ]
    if service:
        entrypoint = python.parent / "robot-service"
        if not entrypoint.is_file() or not os.access(entrypoint, os.X_OK):
            return ["缺少可执行的 robot-service 命令入口"]
        try:
            result = run([entrypoint, "--help"], capture=True, timeout=30)
        except OSError as exc:
            return [f"robot-service 命令入口失效：{exc}"]
        if result.returncode:
            return ["robot-service 命令入口失效：" + result.stderr[-1000:]]
    result = run([python, "-B", "-m", "pip", "check"], capture=True, timeout=60)
    return [] if result.returncode == 0 else [(result.stdout + result.stderr).strip()]


def install_environment(python, locks, service=False):
    environment = python.parent.parent
    valid = False
    if python.is_file():
        probe = run(
            [
                python,
                "-c",
                "import pathlib,sys; sys.exit(not (sys.version_info[:2] == (3,12) and pathlib.Path(sys.prefix).resolve() == pathlib.Path(sys.argv[1]).resolve()))",
                environment,
            ],
            capture=True,
        )
        valid = probe.returncode == 0
    if not valid:
        if environment.exists() or environment.is_symlink():
            backup = environment.with_name(
                f".venv-backup-{environment.name.lstrip('.')}-{time.time_ns()}"
            )
            environment.rename(backup)
            print(f"旧虚拟环境已保留到 {backup}", flush=True)
        require([sys._base_executable, "-m", "venv", environment])
    command = [python, "-m", "pip", "install"]
    for lock in locks:
        command.extend(["-r", lock])
    require(command)
    if service:
        require(
            [python, "-m", "pip", "install", "--no-deps", "-e", ROOT / "ai-service"]
        )


def ocr_errors():
    binary = ROOT / "ai-service/native/ocr"
    if not binary.is_file() or not os.access(binary, os.X_OK):
        return ["缺少可执行的 Apple Vision OCR"]
    if binary.stat().st_mtime < (ROOT / "ai-service/native/ocr.swift").stat().st_mtime:
        return ["OCR 源码更新，需要重新编译"]
    # 使用内存中的空白图片验证真正可执行，不读取家庭图片。
    code = """
import io,json,subprocess,sys
from PIL import Image
b=io.BytesIO(); Image.new('RGB',(64,64),'white').save(b,format='PNG')
r=subprocess.run([sys.argv[1]],input=b.getvalue(),capture_output=True,check=True,timeout=30)
assert isinstance(json.loads(r.stdout)['blocks'],list)
"""
    result = run([PYTHON, "-B", "-c", code, binary], capture=True, timeout=40)
    return (
        [] if result.returncode == 0 else ["OCR 实际运行失败：" + result.stderr[-1000:]]
    )


def llm_errors():
    errors = []
    for role in ("llm", "vlm"):
        lock = install_models.LOCK[role]
        name, tag = lock["name"].split(":")
        path = MODELS / "ollama/manifests/registry.ollama.ai/library" / name / tag
        try:
            if install_models.sha(path) != lock["manifestSha256"]:
                errors.append(lock["name"] + "：清单与固定版本不一致")
                continue
            manifest = json.loads(path.read_text())
            for layer in [manifest["config"], *manifest["layers"]]:
                digest = layer["digest"].removeprefix("sha256:")
                file = MODELS / "ollama/blobs" / ("sha256-" + digest)
                if (
                    file.stat().st_size != layer["size"]
                    or install_models.sha(file) != digest
                ):
                    errors.append(lock["name"] + "：模型层损坏")
                    break
        except (OSError, ValueError, KeyError):
            errors.append(lock["name"] + "：模型缺失或不完整")
    return errors


def request_json(url, context=None):
    # 本机调用不经过系统代理；HTTPS 使用本项目证书验证。
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context)
    )
    with opener.open(url, timeout=5) as response:
        return json.load(response)


def pull_llms():
    if port_open(11435):
        raise RuntimeError(
            "模型需要补装，但 11435 上已有 Ollama。请先停止项目服务后重试，避免同时写模型库。"
        )
    # Ollama 可能直接复用已有 blob；删除已确认损坏的固定版本层后才能补下。
    for role in ("llm", "vlm"):
        lock = install_models.LOCK[role]
        name, tag = lock["name"].split(":")
        path = MODELS / "ollama/manifests/registry.ollama.ai/library" / name / tag
        if not path.is_file() or install_models.sha(path) != lock["manifestSha256"]:
            continue
        manifest = json.loads(path.read_text())
        for layer in [manifest["config"], *manifest["layers"]]:
            digest = layer["digest"].removeprefix("sha256:")
            file = MODELS / "ollama/blobs" / ("sha256-" + digest)
            if file.is_file() and (
                file.stat().st_size != layer["size"]
                or install_models.sha(file) != digest
            ):
                print("重新下载损坏的模型层：" + digest, flush=True)
                file.unlink()
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env = dict(
        os.environ,
        OLLAMA_MODELS=str(MODELS / "ollama"),
        OLLAMA_HOST=f"127.0.0.1:{port}",
        OLLAMA_NO_CLOUD="1",
    )
    log_path = STATE / "ollama-install.log"
    with log_path.open("a") as log:
        process = subprocess.Popen(
            [str(OLLAMA), "serve"], env=env, stdout=log, stderr=log
        )
        try:
            for _ in range(60):
                if process.poll() is not None:
                    raise RuntimeError(f"Ollama 启动失败，请查看 {log_path}")
                try:
                    request_json(f"http://127.0.0.1:{port}/api/version")
                    break
                except (OSError, ValueError):
                    time.sleep(1)
            else:
                raise RuntimeError(f"Ollama 启动超时，请查看 {log_path}")
            for role in ("llm", "vlm"):
                require([OLLAMA, "pull", install_models.LOCK[role]["name"]], env=env)
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def detect_host():
    route = run(["/sbin/route", "-n", "get", "default"], capture=True)
    for line in route.stdout.splitlines():
        if line.strip().startswith("interface:"):
            interface = line.split(":", 1)[1].strip()
            result = run(["/usr/sbin/ipconfig", "getifaddr", interface], capture=True)
            if result.returncode == 0:
                return result.stdout.strip()
    raise RuntimeError(
        "无法检测局域网 IPv4 地址，请用 --host 指定手机能访问的电脑 IP。"
    )


def identity_errors():
    files = [
        DATA / name
        for name in ("server.pem", "server.key", "recovery.secret", "library.sqlite3")
    ]
    # 数据库名称来自 Store，不能借检查过程创建一个新 Store。
    if not all(path.is_file() for path in files):
        return ["家庭身份文件不完整（证书、私钥、恢复秘密、数据库）"]
    try:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_cert_chain(DATA / "server.pem", DATA / "server.key")
    except (OSError, ssl.SSLError) as exc:
        return [f"证书与私钥无法加载：{exc}"]
    return []


def initialize(host, port):
    # 只初始化全新身份。部分文件丢失时停止，不能重新生成身份掩盖损坏。
    names = ("server.pem", "server.key", "recovery.secret", "library.sqlite3")
    if any((DATA / name).exists() for name in names):
        raise RuntimeError(
            "已有家庭身份不完整，请从完整 runtime 备份恢复；不会自动重置证书或设备绑定。"
        )
    require(
        [
            PYTHON.parent / "robot-service",
            "--data",
            DATA,
            "init",
            "--host",
            host,
            "--port",
            str(port),
        ]
    )


def agent_spec(label, port):
    script = "run_ollama.sh" if label == LABELS[0] else "run_home_service.sh"
    env = {
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin",
        "PYTHONDONTWRITEBYTECODE": "1",
    }
    if label == LABELS[0]:
        env["ROBOT_OLLAMA"] = str(OLLAMA)
    if label == LABELS[1]:
        env.update(
            ROBOT_PORT=str(port),
            ROBOT_BIND="0.0.0.0",
            ROBOT_ASR_BACKEND="mlx",
            ROBOT_TTS_BACKEND="qwen3-mlx",
            ROBOT_MODELS=str(MODELS),
            ROBOT_TTS_PYTHON=str(TTS_PYTHON),
            ROBOT_OCR_BINARY=str(ROOT / "ai-service/native/ocr"),
        )
    return {
        "Label": label,
        "WorkingDirectory": str(ROOT),
        "ProgramArguments": [
            "/usr/bin/caffeinate",
            "-i",
            "-s",
            "/bin/bash",
            str(ROOT / "scripts" / script),
        ],
        "EnvironmentVariables": env,
        "RunAtLoad": True,
        "KeepAlive": True,
        "ThrottleInterval": 10,
        "StandardOutPath": str(DATA / "logs" / (label + ".log")),
        "StandardErrorPath": str(DATA / "logs" / (label + ".log")),
    }


def configure_agents(port):
    # 先检查两份配置与端口，再写入，避免只部署了一半才发现冲突。
    pending = []
    for label, service_port in zip(LABELS, (11435, port)):
        path = AGENTS / (label + ".plist")
        spec = agent_spec(label, port)
        prior = plistlib.loads(path.read_bytes()) if path.exists() else None
        if prior is not None and prior != spec:
            raise RuntimeError(
                f"已有不同的自启动配置：{path}；请先备份并移走旧配置，或使用 --no-start。"
            )
        if loaded(label):
            if prior != spec:
                raise RuntimeError(f"{label} 已由其他配置加载；不会接管或重启。")
            continue
        if port_open(service_port):
            raise RuntimeError(
                f"端口 {service_port} 已被其他进程占用；不会终止该进程。"
            )
        pending.append((path, spec, prior))
    for path, spec, prior in pending:
        if prior is None:
            atomic_write(path, plistlib.dumps(spec))
        require(["launchctl", "bootstrap", DOMAIN, path])


def health_error(port):
    try:
        context = ssl.create_default_context(cafile=str(DATA / "server.pem"))
        result = request_json(f"https://127.0.0.1:{port}/health", context)
        if result.get("ok") is not True:
            return "健康接口未返回成功"
        warmup = result.get("modelWarmup")
        if warmup != "ready":
            return f"模型预热状态：{warmup}"
        tags = request_json("http://127.0.0.1:11435/api/tags")
        names = {model["name"] for model in tags["models"]}
        if any(
            install_models.LOCK[role]["name"] not in names for role in ("llm", "vlm")
        ):
            return "当前 Ollama 未提供项目的两个对话模型"
    except (OSError, ValueError, KeyError) as exc:
        return f"服务未就绪：{exc}"
    return None


class Setup:
    def __init__(self, args):
        self.args = args
        self.failures = []
        self.active = port_open(args.port) or port_open(11435)

    def step(self, title, check, fix=None, *, affects_running=True):
        print(f"\n检查：{title}", flush=True)
        try:
            errors = check()
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
            errors = [str(exc)]
        if errors and not self.args.check and fix:
            if self.active and affects_running:
                raise RuntimeError(
                    f"{title} 需要修复，但服务仍在运行。请先执行 bash scripts/home_service.sh stop；前台进程请在原终端停止。"
                )
            print("需要处理：" + "；".join(errors), flush=True)
            fix()
            errors = check()
        if errors:
            self.failures.append(title)
            print("[未通过] " + "；".join(errors), flush=True)
            if not self.args.check:
                raise RuntimeError(f"{title} 验证未通过，已停止后续部署。")
        else:
            print("[通过] " + title, flush=True)

    def execute(self):
        args = self.args
        self.step(
            "Apple Command Line Tools",
            lambda: (
                []
                if run(["xcrun", "--find", "swiftc"], capture=True).returncode == 0
                else ["缺少 swiftc"]
            ),
        )
        main_locks = [
            ROOT / "ai-service" / name
            for name in ("requirements-lock.txt", "requirements-apple-lock.txt")
        ]
        tts_locks = [ROOT / "ai-service/requirements-tts-lock.txt"]
        self.step(
            "Python 服务环境及 MLX/CPU ASR",
            lambda: dependency_errors(PYTHON, main_locks, True),
            lambda: install_environment(PYTHON, main_locks, True),
        )
        self.step(
            "独立 Qwen TTS 环境",
            lambda: dependency_errors(TTS_PYTHON, tts_locks),
            lambda: install_environment(TTS_PYTHON, tts_locks),
        )
        self.step(
            "Apple Vision OCR 编译及实际执行",
            ocr_errors,
            lambda: require(
                [
                    "xcrun",
                    "swiftc",
                    ROOT / "ai-service/native/ocr.swift",
                    "-o",
                    ROOT / "ai-service/native/ocr",
                ]
            ),
        )
        self.step(
            "ASR、备用 Kokoro 和 Ollama 文件完整性",
            lambda: install_models.check(ASSETS, CACHE, MODELS),
            lambda: install_models.install(ASSETS, CACHE, MODELS),
        )
        self.step(
            "Qwen3-TTS 固定版本与全部文件哈希",
            lambda: install_tts.check(MODELS),
            lambda: install_tts.install(MODELS, args.tts_endpoint),
        )
        self.step("两个 Qwen 对话模型的固定清单和全部模型层", llm_errors, pull_llms)
        host = args.host
        if not host and not args.check:
            host = detect_host()
        self.step("家庭服务身份", identity_errors, lambda: initialize(host, args.port))
        if not args.check:
            # 即使首次预热失败，下次也沿用用户指定的端口。
            atomic_write(
                STATE / "config.json",
                json.dumps({"host": host, "port": args.port}).encode(),
            )
        if not args.no_start:
            if not args.check:
                (DATA / "logs").mkdir(parents=True, exist_ok=True)
                configure_agents(args.port)
            self.step(
                "当前用户的登录自启动配置",
                lambda: [
                    label + " 未配置或与当前项目不匹配"
                    for label in LABELS
                    if not (AGENTS / (label + ".plist")).is_file()
                    or plistlib.loads((AGENTS / (label + ".plist")).read_bytes())
                    != agent_spec(label, args.port)
                ],
            )
            if not args.check:
                deadline = time.monotonic() + args.timeout
                while time.monotonic() < deadline:
                    error = health_error(args.port)
                    if error is None or "预热状态：failed" in error:
                        break
                    print(f"等待就绪：{error}（日志 runtime/logs/）", flush=True)
                    time.sleep(5)
            self.step(
                "HTTPS 连通、Ollama 和模型预热",
                lambda: list(filter(None, [health_error(args.port)])),
            )
        if self.failures:
            print("\n检查未通过：" + "、".join(self.failures))
            return 1
        print(
            "\n全部所选检查通过。"
            + (
                "未启动服务，尚未验证模型实际推理。"
                if args.no_start
                else "模型预热已成功。"
            )
        )
        if host:
            print(f"手机连接地址：https://{host}:{args.port}")
        return 0


@contextlib.contextmanager
def install_lock():
    STATE.mkdir(parents=True, exist_ok=True)
    with (STATE / "install.lock").open("a") as file:
        try:
            fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError("另一份部署脚本正在运行，请等待其结束。") from exc
        yield


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--no-start", action="store_true")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--timeout", type=int, default=420)
    parser.add_argument("--tts-endpoint", default="https://huggingface.co")
    args = parser.parse_args(argv)
    if (
        platform.system() != "Darwin"
        or platform.machine() != "arm64"
        or sys.version_info[:2] != (3, 12)
    ):
        parser.error("需要 Apple Silicon macOS 与原生 Python 3.12，请使用 setup_mac.sh")
    if os.getuid() == 0:
        parser.error("请使用普通用户运行")
    config_path = STATE / "config.json"
    try:
        config = json.loads(config_path.read_text()) if config_path.is_file() else {}
        if not isinstance(config, dict):
            raise ValueError("需要 JSON 对象")
    except (OSError, ValueError) as exc:
        parser.error(f"部署配置无法读取：{config_path}：{exc}")
    args.port = args.port if args.port is not None else config.get("port", 8766)
    if (
        not isinstance(args.port, int)
        or not 1024 <= args.port <= 65535
        or args.port == 11435
        or args.timeout < 1
    ):
        parser.error(
            "端口需要为 1024—65535（排除 Ollama 的 11435），timeout 必须大于 0"
        )
    if args.host:
        try:
            address = ipaddress.IPv4Address(args.host)
        except ipaddress.AddressValueError:
            parser.error("--host 需要手机可达的局域网 IPv4 地址")
        if address.is_loopback or address.is_unspecified or address.is_multicast:
            parser.error("--host 不能使用回环、通配或组播地址")
    if not args.tts_endpoint.startswith("https://"):
        parser.error("TTS 下载站点必须使用 HTTPS")
    print(
        f"项目：{ROOT}\n模式：{'只检查' if args.check else '检测并安装'}；服务端口：{args.port}"
    )
    print(
        f"磁盘可用：{shutil.disk_usage(ROOT).free / 1024**3:.1f} GiB；首次安装需为模型、双环境及缓存预留空间。"
    )
    try:
        if args.check:
            return Setup(args).execute()
        with install_lock():
            return Setup(args).execute()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print(
            f"\n部署未完成：{exc}\n修复原因后可重复执行同一命令；已安装文件和家庭数据会保留。",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

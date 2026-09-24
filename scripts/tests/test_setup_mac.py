"""部署检查只使用临时目录和小型假模型，不修改真实服务或下载大模型。"""

import argparse
import hashlib
import importlib
import io
import json
import plistlib
import subprocess
import tarfile
from pathlib import Path

import pytest


@pytest.fixture
def setup(monkeypatch, tmp_path):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[1]))
    module = importlib.import_module("setup_mac")
    for name, value in {
        "ROOT": tmp_path,
        "DATA": tmp_path / "runtime",
        "STATE": tmp_path / ".artifacts/mac-setup",
        "MODELS": tmp_path / "runtime/models",
        "CACHE": tmp_path / ".artifacts/model-downloads",
        "PYTHON": tmp_path / "ai-service/.venv/bin/python",
        "TTS_PYTHON": tmp_path / "ai-service/.venv-tts/bin/python",
        "OLLAMA": tmp_path / ".tools/ollama/ollama",
        "AGENTS": tmp_path / "LaunchAgents",
    }.items():
        monkeypatch.setattr(module, name, value)
    monkeypatch.setattr(module.install_models, "ROOT", tmp_path)
    monkeypatch.setattr(module, "port_open", lambda port: False)
    return module


def raw_asset(setup, content=b"correct-model", destination="test/model.bin"):
    asset = {
        "id": "test",
        "url": "https://invalid.example/model.bin",
        "sha256": hashlib.sha256(content).hexdigest(),
        "destination": destination,
    }
    setup.CACHE.mkdir(parents=True, exist_ok=True)
    setup.install_models.cache_path(asset, setup.CACHE).write_bytes(content)
    return asset


def snapshot(root):
    return {
        str(p.relative_to(root)): (p.read_bytes(), p.stat().st_mtime_ns)
        for p in root.rglob("*")
        if p.is_file()
    }


def test_missing_checks_are_read_only(setup, monkeypatch):
    monkeypatch.setattr(
        setup.install_models,
        "LOCK",
        {
            "assets": [
                {
                    "id": "asr",
                    "url": "https://invalid.example/model.bin",
                    "sha256": "0" * 64,
                    "destination": "asr/model.bin",
                }
            ]
        },
    )
    assert setup.install_models.check(["asr"], setup.CACHE, setup.MODELS)
    assert setup.install_tts.check(setup.MODELS)
    assert setup.identity_errors()
    assert not list(setup.ROOT.iterdir())


def test_model_installer_repairs_corruption_then_skips_without_network(
    setup, monkeypatch
):
    asset = raw_asset(setup)
    monkeypatch.setattr(setup.install_models, "LOCK", {"assets": [asset]})
    monkeypatch.setattr(
        setup.install_models.urllib.request,
        "urlopen",
        lambda *a, **kw: pytest.fail("不应重新联网"),
    )
    target = setup.MODELS / asset["destination"]
    target.parent.mkdir(parents=True)
    target.write_bytes(b"wrong")
    setup.install_models.install(["test"], setup.CACHE, setup.MODELS)
    assert target.read_bytes() == b"correct-model"
    before = snapshot(setup.ROOT)
    setup.install_models.install(["test"], setup.CACHE, setup.MODELS)
    assert not setup.install_models.check(["test"], setup.CACHE, setup.MODELS)
    assert snapshot(setup.ROOT) == before


def test_same_filename_has_separate_cache(setup):
    a = {"url": "https://example.test/asr/config.json"}
    b = {"url": "https://example.test/tts/config.json"}
    assert setup.install_models.cache_path(
        a, setup.CACHE
    ) != setup.install_models.cache_path(b, setup.CACHE)


def test_failed_download_is_not_promoted_to_installed_model(setup, monkeypatch):
    asset = {
        "id": "asr",
        "url": "https://example.test/model.bin",
        "sha256": hashlib.sha256(b"correct").hexdigest(),
        "destination": "asr/model.bin",
    }
    monkeypatch.setattr(setup.install_models, "LOCK", {"assets": [asset]})
    monkeypatch.setattr(
        setup.install_models.urllib.request,
        "urlopen",
        lambda *a, **kw: io.BytesIO(b"wrong download"),
    )
    with pytest.raises(RuntimeError, match="完整性校验失败"):
        setup.install_models.install(["asr"], setup.CACHE, setup.MODELS)
    assert not (setup.MODELS / asset["destination"]).exists()
    assert not setup.install_models.cache_path(asset, setup.CACHE).exists()


def test_wrong_python_environment_is_backed_up_before_rebuild(setup, monkeypatch):
    setup.PYTHON.parent.mkdir(parents=True)
    setup.PYTHON.write_text("old executable")
    monkeypatch.setattr(
        setup, "run", lambda *a, **kw: subprocess.CompletedProcess(a, 1, "", "")
    )
    calls = []
    monkeypatch.setattr(setup, "require", lambda argv: calls.append(argv))
    setup.install_environment(setup.PYTHON, [], True)
    backups = list((setup.ROOT / "ai-service").glob(".venv-backup-*"))
    assert len(backups) == 1
    assert (backups[0] / "bin/python").read_text() == "old executable"
    assert calls[0][1:3] == ["-m", "venv"]
    assert calls[-1][-2:] == ["-e", setup.ROOT / "ai-service"]


def test_archive_repairs_files_and_executable_mode(setup, monkeypatch):
    archive = setup.ROOT / "source.tgz"
    with tarfile.open(archive, "w:gz") as tar:
        info = tarfile.TarInfo("ollama")
        info.size = 6
        info.mode = 0o755
        tar.addfile(info, io.BytesIO(b"binary"))
    asset = {
        "id": "ollama-macos-arm64",
        "url": "https://example.test/ollama.tgz",
        "sha256": setup.install_models.sha(archive),
    }
    setup.CACHE.mkdir(parents=True)
    archive.rename(setup.install_models.cache_path(asset, setup.CACHE))
    monkeypatch.setattr(setup.install_models, "LOCK", {"assets": [asset]})
    setup.install_models.install([asset["id"]], setup.CACHE, setup.MODELS)
    assert not setup.install_models.check([asset["id"]], setup.CACHE, setup.MODELS)
    setup.OLLAMA.chmod(0o644)
    assert setup.install_models.check([asset["id"]], setup.CACHE, setup.MODELS)
    setup.install_models.install([asset["id"]], setup.CACHE, setup.MODELS)
    assert setup.OLLAMA.stat().st_mode & 0o111


def test_server_assets_do_not_create_android_files(setup):
    assert not set(setup.ASSETS) & {"kws", "vad", "aar"}
    assert setup.install_models.mobile_copies(setup.ASSETS, setup.MODELS) == []


def test_tts_check_requires_hash_and_revision_without_writes(setup, monkeypatch):
    content = b"tts-model"
    lock = {
        "repo": "example/tts",
        "revision": "fixed",
        "directory": "tts",
        "files": [
            {
                "path": "model",
                "size": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }
        ],
    }
    monkeypatch.setattr(setup.install_tts, "LOCK", lock)
    directory = setup.MODELS / "tts"
    directory.mkdir(parents=True)
    (directory / "model").write_bytes(content)
    marker = directory / "installed.json"
    marker.write_text(json.dumps({"repo": lock["repo"], "revision": "wrong"}))
    before = snapshot(setup.ROOT)
    assert setup.install_tts.check(setup.MODELS) == ["installed.json"]
    assert snapshot(setup.ROOT) == before
    marker.write_text(json.dumps({"repo": lock["repo"], "revision": "fixed"}))
    assert not setup.install_tts.check(setup.MODELS)
    (directory / "model").write_bytes(b"corrupted")
    assert setup.install_tts.check(setup.MODELS) == ["model"]


def test_llm_requires_locked_manifest_and_every_blob(setup, monkeypatch):
    content = b"layer"
    digest = hashlib.sha256(content).hexdigest()
    manifest = json.dumps(
        {"config": {"digest": "sha256:" + digest, "size": len(content)}, "layers": []}
    ).encode()
    lock = {"name": "qwen:2b", "manifestSha256": hashlib.sha256(manifest).hexdigest()}
    monkeypatch.setattr(setup.install_models, "LOCK", {"llm": lock, "vlm": lock})
    assert setup.llm_errors()
    path = setup.MODELS / "ollama/manifests/registry.ollama.ai/library/qwen/2b"
    path.parent.mkdir(parents=True)
    path.write_bytes(manifest)
    blob = setup.MODELS / "ollama/blobs" / ("sha256-" + digest)
    blob.parent.mkdir(parents=True)
    blob.write_bytes(content)
    assert not setup.llm_errors()
    blob.write_bytes(b"wrong")
    assert all("损坏" in error for error in setup.llm_errors())
    path.write_bytes(b"changed upstream")
    assert all("固定版本" in error for error in setup.llm_errors())


@pytest.mark.parametrize(
    "name", ["server.pem", "server.key", "recovery.secret", "library.sqlite3"]
)
def test_partial_identity_never_gets_reinitialized(setup, monkeypatch, name):
    setup.DATA.mkdir()
    (setup.DATA / name).write_text("existing family identity")
    monkeypatch.setattr(setup, "require", lambda *a, **kw: pytest.fail("不应初始化"))
    before = snapshot(setup.ROOT)
    with pytest.raises(RuntimeError, match="不会自动重置"):
        setup.initialize("192.168.1.20", 8766)
    assert snapshot(setup.ROOT) == before


def test_fresh_identity_initializes_only_once(setup, monkeypatch):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2] / "ai-service"))
    from robot_service.cli import initialize

    calls = []

    def fake_require(argv):
        calls.append(argv)
        initialize(setup.DATA, "192.168.1.20")

    monkeypatch.setattr(setup, "require", fake_require)
    setup.initialize("192.168.1.20", 8766)
    assert not setup.identity_errors()
    before = snapshot(setup.ROOT)
    args = argparse.Namespace(check=False, port=8766)
    setup.Setup(args).step(
        "identity",
        setup.identity_errors,
        lambda: setup.initialize("192.168.1.20", 8766),
    )
    assert len(calls) == 1
    assert snapshot(setup.ROOT) == before


def test_check_never_runs_fixes_and_reports_all_failures(setup, monkeypatch):
    monkeypatch.setattr(
        setup, "run", lambda *a, **kw: subprocess.CompletedProcess(a, 1, "", "")
    )
    monkeypatch.setattr(setup, "dependency_errors", lambda *a: ["missing environment"])
    monkeypatch.setattr(setup, "ocr_errors", lambda: ["missing OCR"])
    monkeypatch.setattr(setup.install_models, "check", lambda *a: ["missing ASR"])
    monkeypatch.setattr(setup.install_tts, "check", lambda *a: ["missing TTS"])
    monkeypatch.setattr(setup, "llm_errors", lambda: ["missing LLM"])
    monkeypatch.setattr(
        setup, "require", lambda *a, **kw: pytest.fail("只检查不得执行安装")
    )
    args = argparse.Namespace(check=True, no_start=True, port=8766, host=None)
    checker = setup.Setup(args)
    assert checker.execute() == 1
    assert len(checker.failures) == 8
    assert not list(setup.ROOT.iterdir())


def test_running_service_blocks_environment_mutation(setup, monkeypatch):
    monkeypatch.setattr(setup, "port_open", lambda port: True)
    checker = setup.Setup(argparse.Namespace(check=False, port=8766))
    with pytest.raises(RuntimeError, match="服务仍在运行"):
        checker.step(
            "dependencies",
            lambda: ["wrong version"],
            lambda: pytest.fail("不应覆盖运行中的环境"),
        )


def test_launch_agents_are_idempotent_and_paths_support_spaces(setup, monkeypatch):
    root = setup.ROOT / "project with spaces"
    monkeypatch.setattr(setup, "ROOT", root)
    monkeypatch.setattr(setup, "loaded", lambda label: False)
    calls = []
    monkeypatch.setattr(setup, "require", lambda argv: calls.append(argv))
    setup.configure_agents(9876)
    assert len(calls) == 2
    spec = plistlib.loads((setup.AGENTS / (setup.LABELS[1] + ".plist")).read_bytes())
    assert spec["ProgramArguments"][-1] == str(root / "scripts/run_home_service.sh")
    assert spec["EnvironmentVariables"]["ROBOT_PORT"] == "9876"
    monkeypatch.setattr(setup, "loaded", lambda label: True)
    before = snapshot(setup.ROOT.parent)
    setup.configure_agents(9876)
    assert len(calls) == 2
    assert snapshot(setup.ROOT.parent) == before


def test_foreign_launch_agent_conflict_is_found_before_any_write(setup, monkeypatch):
    setup.AGENTS.mkdir()
    (setup.AGENTS / (setup.LABELS[1] + ".plist")).write_bytes(
        plistlib.dumps({"Label": "other-project"})
    )
    monkeypatch.setattr(setup, "loaded", lambda label: False)
    monkeypatch.setattr(setup, "require", lambda *a: pytest.fail("不应加载服务"))
    before = snapshot(setup.ROOT)
    with pytest.raises(RuntimeError, match="不同的自启动配置"):
        setup.configure_agents(8766)
    assert snapshot(setup.ROOT) == before


def test_occupied_port_is_not_taken_over(setup, monkeypatch):
    monkeypatch.setattr(setup, "loaded", lambda label: False)
    monkeypatch.setattr(setup, "port_open", lambda port: True)
    with pytest.raises(RuntimeError, match="占用"):
        setup.configure_agents(8766)
    assert not setup.AGENTS.exists()


@pytest.mark.parametrize("state", ["disabled", "warming", "failed"])
def test_health_requires_successful_warmup(setup, monkeypatch, state):
    monkeypatch.setattr(setup.ssl, "create_default_context", lambda **kw: object())
    monkeypatch.setattr(
        setup, "request_json", lambda *a: {"ok": True, "modelWarmup": state}
    )
    assert state in setup.health_error(8766)


def test_install_lock_prevents_concurrent_mutation(setup):
    with setup.install_lock():
        with pytest.raises(RuntimeError, match="另一份部署脚本"):
            with setup.install_lock():
                pytest.fail("不应并发安装")

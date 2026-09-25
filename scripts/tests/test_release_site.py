"""发布中心：archive 版本递增与原子发布、官网页面/二维码/下载端点。"""

import hashlib
import importlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def site(monkeypatch, tmp_path):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[1]))
    module = importlib.import_module("release_site")
    return module, tmp_path / "releases"


def make_apk(path, size=2048):
    data = (bytes(range(256)) * 8)[:size]
    path.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def archive(module, releases, code, name="0.1.0", notes="", force=False):
    apk = releases.parent / f"app-{code}.apk"
    make_apk(apk)
    argv = [
        "archive",
        "--apk", str(apk),
        "--releases-dir", str(releases),
        "--version-name", name,
        "--version-code", str(code),
        "--notes", notes,
    ]
    if force:
        argv.append("--force")
    return module.main(argv)


def test_archive_publishes_manifest_and_apk(site):
    module, releases = site
    apk = releases.parent / "build.apk"
    checksum = make_apk(apk)
    assert (
        module.main(
            [
                "archive",
                "--apk", str(apk),
                "--releases-dir", str(releases),
                "--version-name", "0.2.0",
                "--version-code", "2",
                "--notes", "测试更新",
            ]
        )
        == 0
    )
    manifest = json.loads((releases / "manifest.json").read_text())
    assert manifest["latest"]["versionCode"] == 2
    assert manifest["latest"]["versionName"] == "0.2.0"
    assert manifest["latest"]["sha256"] == checksum
    assert manifest["latest"]["notes"] == "测试更新"
    assert (releases / manifest["latest"]["file"]).is_file()
    assert not list(releases.glob("*.partial"))


def test_archive_requires_increasing_version(site):
    module, releases = site
    assert archive(module, releases, 2) == 0
    assert archive(module, releases, 1) == 2  # 不允许降级
    assert archive(module, releases, 2) == 2  # 重复发布需 --force
    assert archive(module, releases, 2, name="0.2.1", force=True) == 0


def test_archive_keeps_limited_history(site):
    module, releases = site
    for code in (1, 2, 3, 4):
        assert archive(module, releases, code, name=f"0.0.{code}") == 0
    assert len(list(releases.glob("*.apk"))) == 3
    manifest = json.loads((releases / "manifest.json").read_text())
    assert [entry["versionCode"] for entry in manifest["history"]] == [4, 3, 2]
    assert manifest["latest"]["versionCode"] == 4


def test_archive_rejects_invalid_input(site, tmp_path):
    module, releases = site
    missing = tmp_path / "no-such.apk"
    assert (
        module.main(
            [
                "archive",
                "--apk", str(missing),
                "--releases-dir", str(releases),
                "--version-code", "1",
                "--version-name", "0.1.0",
            ]
        )
        == 2
    )
    archive_file = tmp_path / "build.zip"
    archive_file.write_bytes(b"x" * 16)
    assert (
        module.main(
            [
                "archive",
                "--apk", str(archive_file),
                "--releases-dir", str(releases),
                "--version-code", "1",
                "--version-name", "0.1.0",
            ]
        )
        == 2
    )
    assert not (releases / "manifest.json").exists()


def test_site_endpoints(site):
    module, releases = site
    client = TestClient(module.create_site_app(releases))
    assert client.get("/healthz").json() == {"ok": True}
    assert client.get("/api/latest").status_code == 404
    assert "还没有发布安装包" in client.get("/").text
    assert archive(module, releases, 3, notes="新功能上线") == 0
    page = client.get("/")
    assert "0.1.0" in page.text and "新功能上线" in page.text
    latest = client.get("/api/latest").json()
    assert latest["versionCode"] == 3
    data = client.get("/download/" + latest["file"])
    assert data.status_code == 200
    assert data.headers["content-type"] == "application/vnd.android.package-archive"
    assert data.headers["X-Content-SHA256"] == latest["sha256"]
    assert hashlib.sha256(data.content).hexdigest() == latest["sha256"]
    assert client.get("/download/not-exist.apk").status_code == 404
    qr = client.get("/qr")
    assert qr.status_code == 200
    assert qr.headers["content-type"] == "image/png"
    assert qr.content.startswith(b"\x89PNG\r\n\x1a\n")

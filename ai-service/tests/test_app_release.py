"""应用升级接口：仅家长可读，最新发布来自 runtime/releases/manifest.json。"""

import hashlib
import json


def publish_release(root, code=5, name="0.5.0"):
    releases = root / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    data = b"fake-apk-" + str(code).encode()
    file_name = f"{code}-{name}.apk"
    (releases / file_name).write_bytes(data)
    entry = {
        "versionName": name,
        "versionCode": code,
        "channel": "debug",
        "sha256": hashlib.sha256(data).hexdigest(),
        "sizeBytes": len(data),
        "file": file_name,
        "notes": "测试版本",
        "publishedAt": 0,
    }
    (releases / "manifest.json").write_text(
        json.dumps({"latest": entry, "history": [entry]})
    )
    return entry


def test_app_update_requires_parent(system):
    client, store, robot, rh, parent, ph = system
    assert client.get("/v1/app/latest").status_code == 401
    assert client.get("/v1/app/latest", headers=rh).status_code == 403
    assert client.get("/v1/app/download", headers=rh).status_code == 403
    assert client.get("/v1/app/latest", headers=ph).status_code == 404
    assert client.get("/v1/app/download", headers=ph).status_code == 404


def test_app_update_latest_and_download(system):
    client, store, robot, rh, parent, ph = system
    entry = publish_release(store.root)
    result = client.get("/v1/app/latest", headers=ph)
    assert result.status_code == 200
    body = result.json()
    assert body["versionCode"] == 5
    assert body["versionName"] == "0.5.0"
    assert body["sha256"] == entry["sha256"]
    assert body["notes"] == "测试版本"
    assert "file" not in body
    data = client.get("/v1/app/download", headers=ph)
    assert data.status_code == 200
    assert data.headers["X-Content-SHA256"] == entry["sha256"]
    assert hashlib.sha256(data.content).hexdigest() == entry["sha256"]
    # APK 文件缺失时按“尚未发布”处理，不暴露半可用状态
    (store.root / "releases" / entry["file"]).unlink()
    assert client.get("/v1/app/latest", headers=ph).status_code == 404
    assert client.get("/v1/app/download", headers=ph).status_code == 404

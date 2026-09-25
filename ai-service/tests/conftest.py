import secrets

import pytest
from fastapi.testclient import TestClient
from robot_service.app import create_app


@pytest.fixture(autouse=True)
def no_automatic_audio_models(monkeypatch):
    # 单测显式驱动持久队列，避免加载真实模型或产生跨测试竞态。
    monkeypatch.setenv("ROBOT_PREPARE_AUDIO", "0")


@pytest.fixture
def system(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app, raise_server_exceptions=True) as client:
        inv = app.state.store.issue_invite("register")
        robot = client.post(
            "/v1/register", json={"invite": inv, "name": "test robot"}
        ).json()
        rh = {"Authorization": "Bearer " + robot["token"]}
        inv = client.post("/v1/pairing", headers=rh).json()["invite"]
        claim = client.post(
            "/v1/pairing/claim", json={"invite": inv, "name": "test parent"}
        ).json()
        client.post(
            "/v1/pairing/" + claim["pairId"] + "/decision",
            headers=rh,
            json={"approved": True},
        )
        token = secrets.token_urlsafe(32)
        parent = client.post(
            "/v1/pairing/" + claim["pairId"] + "/complete",
            json={"claim": claim["claim"], "token": token},
        ).json()
        ph = {"Authorization": "Bearer " + token}
        yield client, app.state.store, robot, rh, parent, ph

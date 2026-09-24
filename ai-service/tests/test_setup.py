import secrets
import time

import pytest
from fastapi.testclient import TestClient
from robot_service.app import create_app
from robot_service.cli import initialize
from robot_service.store import digest


@pytest.fixture
def setup_system(tmp_path):
    initialize(tmp_path, "127.0.0.1")
    app = create_app(tmp_path)
    with TestClient(app) as client:
        yield client, app.state.store


def begin(client):
    claim, token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    response = client.post(
        "/v1/setup/requests", json={"claimHash": digest(claim), "name": "小伙伴"}
    )
    assert response.status_code == 200
    return response.json()["requestId"], claim, token


def finish(client, rid, claim, token):
    return client.post(
        f"/v1/setup/requests/{rid}/complete", json={"claim": claim, "token": token}
    )


def test_connect_automatically_without_computer_approval(setup_system):
    client, store = setup_system
    assert client.get("/health").json()["automaticSetup"] is True
    rid, claim, token = begin(client)
    result = finish(client, rid, claim, token)
    assert result.status_code == 200
    assert result.json()["state"] == "completed"
    assert finish(client, rid, claim, token).json() == result.json()
    assert len(store.read("SELECT * FROM devices")) == 1
    assert finish(client, rid, claim, secrets.token_urlsafe(32)).status_code == 409
    assert (
        client.get(
            "/v1/devices", headers={"Authorization": f"Bearer {token}"}
        ).status_code
        == 200
    )
    assert client.get("/v1/devices").status_code == 401
    assert store.one("SELECT role FROM devices")["role"] == "robot"
    assert len(store.read("SELECT * FROM configs")) == 1


def test_wrong_claim_cannot_take_over_request(setup_system):
    client, store = setup_system
    rid, claim, token = begin(client)
    assert finish(client, rid, secrets.token_urlsafe(32), token).status_code == 403
    assert not store.read("SELECT * FROM devices")
    result = finish(client, rid, claim, token)
    assert result.json()["state"] == "completed"
    assert finish(client, rid, secrets.token_urlsafe(32), token).status_code == 403


def test_expiration_and_request_retries(setup_system):
    client, store = setup_system
    rid, claim, token = begin(client)
    repeated = client.post(
        "/v1/setup/requests", json={"claimHash": digest(claim)}
    ).json()
    assert repeated["requestId"] == rid
    with store.transaction() as db:
        db.execute("UPDATE setup_requests SET expires=?", (time.time() - 1,))
    assert finish(client, rid, claim, token).status_code == 410
    assert not store.read("SELECT * FROM devices")


def test_requests_are_bounded_and_validated(setup_system):
    client, store = setup_system
    assert (
        client.post("/v1/setup/requests", json={"claimHash": "short"}).status_code
        == 422
    )
    for _ in range(10):
        begin(client)
    assert (
        client.post(
            "/v1/setup/requests", json={"claimHash": digest("another")}
        ).status_code
        == 429
    )
    with store.transaction() as db:
        db.execute("UPDATE setup_requests SET expires=?", (time.time() - 1,))
    begin(client)
    assert len(store.read("SELECT * FROM setup_requests")) == 1

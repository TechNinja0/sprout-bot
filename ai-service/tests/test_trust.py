import secrets

from robot_service.schemas import Config
from robot_service.store import digest


def test_register_once_expiry_and_no_default_auth(system):
    c, s, r, rh, p, ph = system
    code = s.issue_invite("register", ttl=-1)
    assert (
        c.post("/v1/register", json={"invite": code, "name": "expired"}).status_code
        == 403
    )
    code = s.issue_invite("register")
    assert (
        c.post("/v1/register", json={"invite": code, "name": "new"}).status_code == 200
    )
    assert (
        c.post("/v1/register", json={"invite": code, "name": "again"}).status_code
        == 403
    )
    assert c.get("/v1/resources").status_code == 401
    assert (
        c.post(
            "/v1/resources",
            headers=rh,
            json={"kind": "book", "draft": {"title": "Book"}},
        ).status_code
        == 403
    )
    assert c.get("/v1/robots/other/config", headers=ph).status_code == 403


def test_pair_requires_local_confirmation_and_is_idempotent(system):
    c, s, r, rh, p, ph = system
    inv = c.post("/v1/pairing", headers=rh).json()["invite"]
    claim = c.post("/v1/pairing/claim", json={"invite": inv, "name": "other"}).json()
    token = secrets.token_urlsafe(32)
    body = {"claim": claim["claim"], "token": token}
    path = "/v1/pairing/" + claim["pairId"]
    assert c.post(path + "/complete", json=body).json()["state"] == "pending"
    assert (
        c.post(path + "/decision", headers=ph, json={"approved": True}).status_code
        == 403
    )
    assert (
        c.post(path + "/decision", headers=rh, json={"approved": True}).status_code
        == 200
    )
    result = c.post(path + "/complete", json=body).json()
    assert c.post(path + "/complete", json=body).json() == result
    assert (
        c.get("/v1/resources", headers={"Authorization": "Bearer " + token}).status_code
        == 200
    )
    assert (
        c.post("/v1/pairing/claim", json={"invite": inv, "name": "replay"}).status_code
        == 403
    )
    assert c.delete("/v1/devices/" + result["deviceId"], headers=rh).status_code == 200
    assert (
        c.get("/v1/resources", headers={"Authorization": "Bearer " + token}).status_code
        == 401
    )
    assert s.one("SELECT token_hash FROM devices WHERE id=?", (r["deviceId"],))[
        "token_hash"
    ] == digest(r["token"])


def test_config_online_ack_conflicts_and_expiry(system):
    c, s, r, rh, p, ph = system
    path = "/v1/robots/" + r["deviceId"] + "/config"
    body = {
        "requestId": secrets.token_hex(16),
        "expectedVersion": 0,
        "config": Config().model_dump(mode="json"),
    }
    assert c.post(path, headers=ph, json=body).status_code == 409
    c.post("/v1/heartbeat", headers=rh, json={})
    result = c.post(path, headers=ph, json=body)
    assert result.json()["state"] == "pending"
    assert c.get(path, headers=ph).json()["version"] == 0
    assert (
        c.post(
            path, headers=ph, json={**body, "requestId": secrets.token_hex(16)}
        ).status_code
        == 409
    )
    assert (
        c.post(
            "/v1/commands/" + body["requestId"] + "/ack",
            headers=rh,
            json={"applied": True, "version": 1},
        ).json()["state"]
        == "applied"
    )
    assert c.get(path, headers=ph).json()["version"] == 1
    assert (
        c.post(
            path, headers=ph, json={**body, "requestId": secrets.token_hex(16)}
        ).status_code
        == 409
    )
    body.update(expectedVersion=1, requestId=secrets.token_hex(16))
    assert c.post(path, headers=ph, json=body).status_code == 200
    with s.transaction() as db:
        db.execute("UPDATE commands SET expires=0 WHERE id=?", (body["requestId"],))
    assert c.get("/v1/commands", headers=rh).json() == []
    assert (
        c.post(
            "/v1/commands/" + body["requestId"] + "/ack",
            headers=rh,
            json={"applied": True, "version": 2},
        ).status_code
        == 409
    )
    assert (
        c.get("/v1/commands/" + body["requestId"], headers=ph).json()["state"]
        == "expired"
    )


def test_recovery_rotates_robot_and_revokes_all_parents(system):
    c, store, rob, rh, parent, ph = system
    token = store.issue_invite("recover", rob["deviceId"])
    recovered = c.post("/v1/recover", json={"invite": token})
    assert recovered.status_code == 200
    assert c.get("/v1/devices", headers=rh).status_code == 401
    assert c.get("/v1/devices", headers=ph).status_code == 401
    assert (
        c.get(
            "/v1/devices",
            headers={"Authorization": "Bearer " + recovered.json()["token"]},
        ).status_code
        == 200
    )
    assert c.post("/v1/recover", json={"invite": token}).status_code == 403


def test_control_offline_replay_and_ack(system):
    c, store, rob, rh, parent, ph = system
    path = "/v1/robots/" + rob["deviceId"] + "/control"
    body = {"requestId": "stop-request-0001", "action": "stop"}
    assert c.post(path, headers=ph, json=body).status_code == 409
    c.post("/v1/heartbeat", headers=rh, json={})
    assert c.post(path, headers=ph, json=body).json()["state"] == "pending"
    assert (
        c.post(path, headers=ph, json={**body, "action": "resume"}).status_code == 409
    )
    ack = "/v1/commands/" + body["requestId"] + "/ack"
    assert (
        c.post(ack, headers=rh, json={"applied": True, "version": 0}).json()["state"]
        == "applied"
    )
    assert (
        c.post(ack, headers=rh, json={"applied": True, "version": 0}).json()["state"]
        == "applied"
    )

import json


def test_report_is_scoped_sanitized_and_does_not_claim_hardware_test(
    system, monkeypatch
):
    import robot_service.intelligence as module

    client, store, robot, rh, _, ph = system

    async def health(*args):
        return {
            "llm": True,
            "asr": False,
            "tts": True,
            "ttsInfo": {"secret": "private-path"},
        }

    monkeypatch.setattr(module, "health", health)
    client.post(
        "/v1/heartbeat",
        headers=rh,
        json={
            "status": "private-child-name",
            "reason": "private-transcript",
            "appliedVersion": 3,
            "microphone": True,
            "serviceFailures": {"network": 2},
        },
    )
    report = client.get("/v1/diagnostics", headers=ph).json()
    assert report["robot"]["state"] == report["robot"]["reason"] == "unknown"
    assert report["robot"]["appliedVersion"] == 3
    assert report["robot"]["failures"]["network"] == 2
    assert report["modelAvailability"]["asr"] is False
    assert report["robot"]["microphone"] is True
    assert report["storageFreeBytes"] > 0
    for forbidden in (
        "private-",
        robot["token"],
        robot["deviceId"],
        "ttsInfo",
        "selfTest",
        'config"',
    ):
        assert forbidden not in json.dumps(report)
    assert client.get("/v1/diagnostics", headers=rh).status_code == 200
    assert client.get("/v1/diagnostics").status_code == 401
    with store.transaction() as db:
        db.execute("UPDATE devices SET last_seen=0 WHERE id=?", (robot["deviceId"],))
    assert client.get("/v1/diagnostics", headers=ph).json()["robot"]["online"] is False

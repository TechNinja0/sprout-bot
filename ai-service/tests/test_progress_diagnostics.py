import json

import pytest


def test_progress_failures_do_not_break_heartbeat_or_parent_diagnostics(system):
    client, store, robot, robot_headers, _, parent_headers = system
    counters = {"progress-overflow": 1, "progress-rejected": 2, "progress-response": 3}
    response = client.post(
        "/v1/heartbeat", headers=robot_headers, json={"serviceFailures": counters}
    )
    assert response.status_code == 200
    stored = json.loads(
        store.one("SELECT status FROM devices WHERE id=?", (robot["deviceId"],))[
            "status"
        ]
    )
    assert all(
        stored["serviceFailures"][key] == value for key, value in counters.items()
    )
    assert "progress_overflow" not in stored["serviceFailures"]
    devices = client.get("/v1/devices", headers=parent_headers).json()
    current = next(item for item in devices if item["id"] == robot["deviceId"])
    assert current["status"]["serviceFailures"]["progress-rejected"] == 2
    assert (
        client.post("/v1/heartbeat", headers=robot_headers, json={}).status_code == 200
    )


@pytest.mark.parametrize(
    "counters",
    [
        {"progress-overflow": -1},
        {"progress-response": 1_000_000_001},
        {"unbounded-private-detail": 1},
    ],
)
def test_diagnostic_counters_remain_bounded_and_allowlisted(system, counters):
    client, _, _, headers, _, _ = system
    assert (
        client.post(
            "/v1/heartbeat", headers=headers, json={"serviceFailures": counters}
        ).status_code
        == 422
    )

import uuid

import pytest
from pydantic import ValidationError
from robot_service.schemas import Config, Interaction


def test_old_config_defaults_to_audible_feedback():
    config = Config.model_validate({})
    assert config.interaction.wakeFeedback == "voice"
    assert config.interaction.playful is True
    for mode in ("voice", "chime", "visual"):
        assert Interaction(wakeFeedback=mode).wakeFeedback == mode
    with pytest.raises(ValidationError):
        Interaction(wakeFeedback="anything")


def test_parent_feedback_preferences_roundtrip(system):
    client, _, robot, robot_headers, _, parent = system
    robot_id = robot["deviceId"]
    client.post("/v1/heartbeat", headers=robot_headers, json={})
    endpoint = f"/v1/robots/{robot_id}/config"
    response = client.get(endpoint, headers=parent)
    assert response.status_code == 200
    current = response.json()
    current["config"]["interaction"] = {"wakeFeedback": "visual", "playful": False}
    request_id = str(uuid.uuid4())
    response = client.post(
        endpoint,
        headers=parent,
        json={
            "requestId": request_id,
            "expectedVersion": current["version"],
            "config": current["config"],
        },
    )
    assert response.status_code == 200
    response = client.post(
        f"/v1/commands/{request_id}/ack",
        headers=robot_headers,
        json={"applied": True, "version": current["version"] + 1},
    )
    assert response.status_code == 200
    assert client.get(endpoint, headers=parent).json()["config"]["interaction"] == {
        "wakeFeedback": "visual",
        "playful": False,
        "wakeSensitivity": "standard",
        "expressionIntensity": "gentle",
    }

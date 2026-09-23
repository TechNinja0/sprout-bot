import pytest
from pydantic import ValidationError
from robot_service.keywords import generate, phrase
from robot_service.schemas import Interaction


def test_sensitivity_only_changes_wake_lines_and_retains_default():
    generated = {
        level: generate("小伙伴", level).splitlines()
        for level in ("low", "standard", "high")
    }
    assert "#0.25 @wake" in generated["standard"][0]
    assert "#0.35 @wake" in generated["low"][0]
    assert "#0.15 @wake" in generated["high"][0]
    assert generated["low"][2:] == generated["standard"][2:] == generated["high"][2:]
    assert Interaction().wakeSensitivity == "standard"
    # PRD R03 的原话必须可由本地KWS处理；不能仅依赖联网自由对话。
    assert phrase("别看了", "camera_off").strip() in generated["standard"]
    with pytest.raises(ValidationError):
        Interaction(wakeSensitivity="arbitrary")


def test_keyword_endpoint_uses_saved_parent_setting(system):
    import json

    client, store, robot, rh, _, _ = system
    with store.transaction() as db:
        row = db.execute(
            "SELECT body FROM configs WHERE robot_id=?", (robot["deviceId"],)
        ).fetchone()
        config = json.loads(row["body"])
        config["interaction"]["wakeSensitivity"] = "low"
        db.execute(
            "UPDATE configs SET body=? WHERE robot_id=?",
            (json.dumps(config), robot["deviceId"]),
        )
    response = client.get("/v1/kws/keywords", headers=rh).json()
    assert "#0.35 @wake" in response["keywords"]

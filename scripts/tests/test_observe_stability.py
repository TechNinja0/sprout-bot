import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("observer", Path(__file__).parents[1] / "observe_stability.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
complete = {"event": "complete", "passed": True, "elapsedMs": 14400000}
ack = {"event": "configuration-restored", "acknowledged": True}


def test_completion_waits_for_cleanup_ack():
    assert module.terminal_status([complete], 14400) is None
    assert module.terminal_status([complete, dict(ack, acknowledged=False)], 14400) is None
    assert module.terminal_status([ack, complete], 14400) is None
    assert module.terminal_status([complete, ack], 14400) == "completed-awaiting-analysis"


def test_cleanup_failure_and_short_duration_cannot_pass():
    assert module.terminal_status([complete, {"event": "configuration-restore-failed"}], 14400) == "device-failed"
    assert module.terminal_status([dict(complete, elapsedMs=60000), ack], 14400) == "device-failed"
    assert module.terminal_status([complete, ack, {"event": "failed"}], 14400) == "device-failed"

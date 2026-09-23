"""诊断采用允许字段清单，绝不序列化配置、设备材料或模型原始响应。"""

import json
import shutil
import time

from fastapi import APIRouter, Depends, Request

from .auth import principal
from .schemas import FailureCounts

router = APIRouter(prefix="/v1")
STATES = set(
    "standby opening listening recognizing thinking speaking follow_up closing muted blocked self_check".split()
)
REASONS = {"ALLOWED", "SCHEDULED", "QUOTA", "MANUAL", "UNTRUSTED_TIME"}


@router.get("/diagnostics")
async def diagnostics(request: Request, user=Depends(principal)):
    from .intelligence import health

    store = request.app.state.store
    rid = user["id"] if user["role"] == "robot" else user["robot_id"]
    device = store.one("SELECT status,last_seen FROM devices WHERE id=?", (rid,))
    config = store.one("SELECT version FROM configs WHERE robot_id=?", (rid,))
    status = json.loads(device["status"]) if device else {}
    now = time.time()
    models = await health(request, user)
    return {
        "reportVersion": 1,
        "generatedAt": now,
        "serviceVersion": request.app.version,
        "protocolVersion": 1,
        "robot": {
            "online": bool(device and now - device["last_seen"] < 15),
            "lastSeen": device["last_seen"] if device else 0,
            "configVersion": config["version"] if config else 0,
            "appliedVersion": status.get("appliedVersion", 0),
            "state": status.get("status")
            if status.get("status") in STATES
            else "unknown",
            "reason": status.get("reason")
            if status.get("reason") in REASONS
            else "unknown",
            "camera": status.get("camera", False) is True,
            "microphone": status.get("microphone", False) is True,
            "failures": FailureCounts.model_validate(
                status.get("serviceFailures", {})
            ).model_dump(by_alias=True),
        },
        "modelAvailability": {
            key: models.get(key) is True for key in ("llm", "vlm", "asr", "tts", "ocr")
        },
        "storageFreeBytes": shutil.disk_usage(store.root).free,
    }

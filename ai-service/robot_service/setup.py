"""Automatic robot enrollment for a reachable, trusted household service."""

import secrets
import time

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from .auth import fail
from .schemas import Config
from .store import digest, dumps, uid

router = APIRouter(prefix="/v1/setup")


class SetupRequest(BaseModel):
    claimHash: str = Field(pattern=r"^[0-9a-f]{64}$")
    name: str = Field(default="家庭小伙伴", min_length=1, max_length=80)


class SetupComplete(BaseModel):
    claim: str = Field(min_length=32, max_length=128)
    token: str = Field(min_length=32, max_length=128)


@router.post("/requests")
def request_connection(body: SetupRequest, request: Request):
    store = request.app.state.store
    cert_path = store.root / "server.pem"
    if not cert_path.exists():
        fail(503, "请先在家庭电脑初始化服务")
    now = time.time()
    with store.transaction() as db:
        db.execute("DELETE FROM setup_requests WHERE expires<=?", (now,))
        prior = db.execute(
            "SELECT id,expires FROM setup_requests WHERE claim_hash=?",
            (body.claimHash,),
        ).fetchone()
        if prior:
            return {
                "requestId": prior["id"],
                "expiresIn": max(0, int(prior["expires"] - now)),
            }
        if db.execute("SELECT COUNT(*) FROM setup_requests").fetchone()[0] >= 10:
            fail(429, "待处理连接较多，请稍后再试")
        request_id = uid()
        db.execute(
            "INSERT INTO setup_requests(id,name,claim_hash,code,expires,state) VALUES(?,?,?,?,?,'approved')",
            (
                request_id,
                body.name,
                body.claimHash,
                "",
                now + 300,
            ),
        )
    return {"requestId": request_id, "expiresIn": 300}


@router.post("/requests/{request_id}/complete")
def complete_connection(request_id: str, body: SetupComplete, request: Request):
    store = request.app.state.store
    with store.transaction() as db:
        row = db.execute(
            "SELECT * FROM setup_requests WHERE id=?", (request_id,)
        ).fetchone()
        if not row or not secrets.compare_digest(row["claim_hash"], digest(body.claim)):
            fail(403, "连接请求不匹配，请重新连接")
        if row["expires"] <= time.time():
            fail(410, "连接请求已过期，请重新连接")
        if row["state"] == "completed":
            if not secrets.compare_digest(row["token_hash"], digest(body.token)):
                fail(409, "连接凭据不一致")
            return {"state": "completed", "deviceId": row["device_id"]}
        if row["state"] not in ("approved", "pending"):
            return {"state": row["state"]}
        device_id = uid()
        db.execute(
            "INSERT INTO devices(id,role,name,token_hash) VALUES(?,'robot',?,?)",
            (device_id, row["name"], digest(body.token)),
        )
        db.execute(
            "INSERT INTO configs VALUES(?,?,?)",
            (device_id, 0, dumps(Config().model_dump(mode="json"))),
        )
        db.execute(
            "UPDATE setup_requests SET state='completed',token_hash=?,device_id=? WHERE id=?",
            (digest(body.token), device_id, request_id),
        )
    return {"state": "completed", "deviceId": device_id}

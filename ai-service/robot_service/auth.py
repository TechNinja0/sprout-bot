import json
import secrets
import time

from fastapi import APIRouter, Depends, Header, HTTPException, Request

from .schemas import (
    Ack,
    ClaimPoll,
    Config,
    ConfigRequest,
    Control,
    Heartbeat,
    PairClaim,
    PairDecision,
    Recover,
    Register,
)
from .store import digest, dumps, uid

router = APIRouter(prefix="/v1")


def fail(code, message):
    raise HTTPException(code, message)


def principal(request: Request, authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        fail(401, "需要设备凭据")
    row = request.app.state.store.one(
        "SELECT * FROM devices WHERE token_hash=? AND revoked=0",
        (digest(authorization[7:]),),
    )
    if not row:
        fail(401, "凭据无效或已撤销")
    return row


def parent(user=Depends(principal)):
    if user["role"] != "parent" or not user["robot_id"]:
        fail(403, "需要已绑定家长权限")
    return user


def robot(user=Depends(principal)):
    if user["role"] != "robot":
        fail(403, "需要机器人权限")
    return user


def scope(user, robot_id):
    if (user["id"] if user["role"] == "robot" else user["robot_id"]) != robot_id:
        fail(403, "无权管理该机器人")


def consume(db, token, kind):
    row = db.execute("SELECT * FROM invites WHERE hash=?", (digest(token),)).fetchone()
    if (
        not row
        or row["kind"] != kind
        or row["consumed"]
        or row["expires"] <= time.time()
    ):
        fail(403, "连接码已过期或已使用")
    db.execute("UPDATE invites SET consumed=1 WHERE hash=?", (digest(token),))
    return row


@router.post("/register")
def register(body: Register, request: Request):
    store = request.app.state.store
    token, device_id = secrets.token_urlsafe(32), uid()
    with store.transaction() as db:
        consume(db, body.invite, "register")
        db.execute(
            "INSERT INTO devices(id,role,name,token_hash) VALUES(?,?,?,?)",
            (device_id, "robot", body.name, digest(token)),
        )
        db.execute(
            "INSERT INTO configs VALUES(?,?,?)",
            (device_id, 0, dumps(Config().model_dump(mode="json"))),
        )
    return {
        "deviceId": device_id,
        "token": token,
        "serviceId": store.meta("service_id"),
    }


@router.post("/pairing")
def invite(request: Request, user=Depends(robot)):
    return {
        "invite": request.app.state.store.issue_invite("pair", user["id"]),
        "expiresIn": 120,
        "robotId": user["id"],
    }


@router.post("/pairing/claim")
def claim(body: PairClaim, request: Request):
    pair_id, claim_secret = uid(), secrets.token_urlsafe(32)
    with request.app.state.store.transaction() as db:
        inv = consume(db, body.invite, "pair")
        db.execute(
            "INSERT INTO pairs(id,robot_id,name,claim_hash,expires,state) VALUES(?,?,?,?,?,?)",
            (
                pair_id,
                inv["robot_id"],
                body.name,
                digest(claim_secret),
                time.time() + 120,
                "pending",
            ),
        )
    return {"pairId": pair_id, "claim": claim_secret, "state": "pending"}


@router.get("/pairing/pending")
def pending(request: Request, user=Depends(robot)):
    return request.app.state.store.read(
        "SELECT id,name,expires FROM pairs WHERE robot_id=? AND state='pending' AND expires>?",
        (user["id"], time.time()),
    )


@router.post("/pairing/{pair_id}/decision")
def decision(pair_id: str, body: PairDecision, request: Request, user=Depends(robot)):
    with request.app.state.store.transaction() as db:
        row = db.execute("SELECT * FROM pairs WHERE id=?", (pair_id,)).fetchone()
        if (
            not row
            or row["robot_id"] != user["id"]
            or row["state"] != "pending"
            or row["expires"] <= time.time()
        ):
            fail(409, "配对不可确认")
        db.execute(
            "UPDATE pairs SET state=? WHERE id=?",
            ("approved" if body.approved else "rejected", pair_id),
        )
    return {"state": "approved" if body.approved else "rejected"}


@router.post("/pairing/{pair_id}/complete")
def complete(pair_id: str, body: ClaimPoll, request: Request):
    # 客户端生成最终令牌；服务只保存哈希，网络重试可幂等完成。
    with request.app.state.store.transaction() as db:
        row = db.execute("SELECT * FROM pairs WHERE id=?", (pair_id,)).fetchone()
        if not row or not secrets.compare_digest(row["claim_hash"], digest(body.claim)):
            fail(403, "配对凭据不正确")
        if row["expires"] <= time.time():
            fail(410, "配对已过期")
        if row["state"] == "completed":
            if row["token_hash"] != digest(body.token):
                fail(409, "令牌不一致")
            return {
                "state": "completed",
                "deviceId": row["parent_id"],
                "robotId": row["robot_id"],
            }
        if row["state"] != "approved":
            return {"state": row["state"]}
        parent_id = uid()
        db.execute(
            "INSERT INTO devices(id,role,name,token_hash,robot_id) VALUES(?,?,?,?,?)",
            (parent_id, "parent", row["name"], digest(body.token), row["robot_id"]),
        )
        db.execute(
            "UPDATE pairs SET state='completed',token_hash=?,parent_id=? WHERE id=?",
            (digest(body.token), parent_id, pair_id),
        )
    return {"state": "completed", "deviceId": parent_id, "robotId": row["robot_id"]}


@router.get("/devices")
def devices(request: Request, user=Depends(principal)):
    rid = user["id"] if user["role"] == "robot" else user["robot_id"]
    rows = request.app.state.store.read(
        "SELECT id,role,name,robot_id,revoked,last_seen,status FROM devices WHERE id=? OR robot_id=?",
        (rid, rid),
    )
    for row in rows:
        row["status"] = json.loads(row["status"])
        row["online"] = time.time() - row["last_seen"] < 15 and not row["revoked"]
    return rows


@router.delete("/devices/{device_id}")
def revoke(device_id: str, request: Request, user=Depends(principal)):
    store = request.app.state.store
    target = store.one("SELECT * FROM devices WHERE id=?", (device_id,))
    if not target:
        fail(404, "设备不存在")
    if user["role"] == "robot":
        if target["id"] != user["id"] and target["robot_id"] != user["id"]:
            fail(403, "无权撤销")
    elif target["id"] != user["id"] and target["robot_id"] != user["robot_id"]:
        fail(403, "无权撤销")
    with store.transaction() as db:
        db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device_id,))
        db.execute(
            "UPDATE commands SET state='cancelled' WHERE (parent_id=? OR robot_id=?) AND state='pending'",
            (device_id, device_id),
        )
        if target["role"] == "robot":
            db.execute("UPDATE devices SET revoked=1 WHERE robot_id=?", (device_id,))
            db.execute(
                "UPDATE pairs SET state='rejected' WHERE robot_id=?", (device_id,)
            )
            db.execute("UPDATE invites SET consumed=1 WHERE robot_id=?", (device_id,))
    return {"revoked": True}


@router.post("/heartbeat")
def heartbeat(body: Heartbeat, request: Request, user=Depends(robot)):
    store = request.app.state.store
    with store.transaction() as db:
        db.execute(
            "UPDATE devices SET last_seen=?,status=? WHERE id=?",
            (time.time(), dumps(body.model_dump(by_alias=True)), user["id"]),
        )
        db.execute(
            "UPDATE commands SET state='expired' WHERE expires<=? AND state='pending'",
            (time.time(),),
        )
    return {"serverTime": time.time(), "catalogVersion": int(store.meta("catalog"))}


@router.get("/robots/{robot_id}/config")
def config_get(robot_id: str, request: Request, user=Depends(principal)):
    scope(user, robot_id)
    row = request.app.state.store.one(
        "SELECT * FROM configs WHERE robot_id=?", (robot_id,)
    )
    return {"version": row["version"], "config": json.loads(row["body"])}


@router.post("/robots/{robot_id}/config")
def config_set(
    robot_id: str, body: ConfigRequest, request: Request, user=Depends(parent)
):
    scope(user, robot_id)
    if body.config.policy.overrideUntil > time.time() + 15 * 60:
        fail(422, "临时放行不得超过15分钟")
    store = request.app.state.store
    payload = dumps(body.config.model_dump(mode="json"))
    with store.transaction() as db:
        existing = db.execute(
            "SELECT * FROM commands WHERE id=?", (body.requestId,)
        ).fetchone()
        if existing:
            if (
                existing["parent_id"] != user["id"]
                or existing["body"] != payload
                or existing["expected"] != body.expectedVersion
            ):
                fail(409, "请求ID已用于不同操作")
            return {"requestId": body.requestId, "state": existing["state"]}
        dev = db.execute(
            "SELECT last_seen,revoked FROM devices WHERE id=?", (robot_id,)
        ).fetchone()
        if not dev or dev["revoked"] or time.time() - dev["last_seen"] >= 15:
            fail(409, "机器人离线，未排队")
        current = db.execute(
            "SELECT version FROM configs WHERE robot_id=?", (robot_id,)
        ).fetchone()[0]
        if current != body.expectedVersion:
            fail(409, "配置版本冲突，请刷新")
        if db.execute(
            "SELECT 1 FROM commands WHERE robot_id=? AND state='pending' AND expires>?",
            (robot_id, time.time()),
        ).fetchone():
            fail(409, "已有待应用配置")
        db.execute(
            "INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)",
            (
                body.requestId,
                robot_id,
                user["id"],
                "config",
                body.expectedVersion,
                payload,
                time.time() + 10,
                "pending",
                time.time(),
            ),
        )
    return {"requestId": body.requestId, "state": "pending"}


@router.get("/commands")
def commands(request: Request, user=Depends(robot)):
    rows = request.app.state.store.read(
        "SELECT id,kind,expected,body,expires FROM commands WHERE robot_id=? AND state='pending' AND expires>?",
        (user["id"], time.time()),
    )
    for r in rows:
        r["body"] = json.loads(r["body"])
    return rows


@router.get("/commands/{command_id}")
def command_state(command_id: str, request: Request, user=Depends(principal)):
    row = request.app.state.store.one(
        "SELECT * FROM commands WHERE id=?", (command_id,)
    )
    if not row:
        fail(404, "请求不存在")
    scope(user, row["robot_id"])
    return {
        "requestId": row["id"],
        "state": "expired"
        if row["state"] == "pending" and row["expires"] <= time.time()
        else row["state"],
    }


@router.post("/commands/{command_id}/ack")
def ack(command_id: str, body: Ack, request: Request, user=Depends(robot)):
    with request.app.state.store.transaction() as db:
        row = db.execute("SELECT * FROM commands WHERE id=?", (command_id,)).fetchone()
        if not row or row["robot_id"] != user["id"]:
            fail(404, "请求不存在")
        if (
            row["state"] == "applied"
            and body.applied
            and (row["kind"] == "control" or body.version == row["expected"] + 1)
        ):
            return {"state": "applied"}
        if row["state"] != "pending" or row["expires"] <= time.time():
            fail(409, "请求已失效")
        if row["kind"] == "control":
            db.execute(
                "UPDATE commands SET state=? WHERE id=?",
                ("applied" if body.applied else "rejected", command_id),
            )
            return {"state": "applied" if body.applied else "rejected"}
        current = db.execute(
            "SELECT version FROM configs WHERE robot_id=?", (user["id"],)
        ).fetchone()[0]
        if current != row["expected"] or (body.applied and body.version != current + 1):
            fail(409, "应用版本不匹配")
        if body.applied:
            db.execute(
                "UPDATE configs SET version=?,body=? WHERE robot_id=?",
                (body.version, row["body"], user["id"]),
            )
        db.execute(
            "UPDATE commands SET state=? WHERE id=?",
            ("applied" if body.applied else "rejected", command_id),
        )
    return {"state": "applied" if body.applied else "rejected"}


@router.post("/robots/{robot_id}/control")
def control(robot_id: str, body: Control, request: Request, user=Depends(parent)):
    scope(user, robot_id)
    store = request.app.state.store
    payload = dumps(body.model_dump())
    with store.transaction() as db:
        existing = db.execute(
            "SELECT * FROM commands WHERE id=?", (body.requestId,)
        ).fetchone()
        if existing:
            if existing["parent_id"] != user["id"] or existing["body"] != payload:
                fail(409, "请求ID冲突")
            return {"requestId": body.requestId, "state": existing["state"]}
        dev = db.execute(
            "SELECT last_seen,revoked FROM devices WHERE id=?", (robot_id,)
        ).fetchone()
        if not dev or dev["revoked"] or time.time() - dev["last_seen"] >= 15:
            fail(409, "机器人离线，未排队")
        if body.action == "playlist":
            playlist = db.execute(
                "SELECT resources FROM playlists WHERE id=?", (body.playlistId,)
            ).fetchone()
            if not playlist or not json.loads(playlist["resources"]):
                fail(409, "播放清单为空或不存在")
            for resource_id in json.loads(playlist["resources"]):
                if not db.execute(
                    "SELECT 1 FROM resources WHERE id=? AND status='published'",
                    (resource_id,),
                ).fetchone():
                    fail(409, "清单包含未发布资源，请先更新清单")
        if body.action in ("play", "download"):
            if not db.execute(
                "SELECT 1 FROM resources WHERE id=? AND status='published'",
                (body.resourceId,),
            ).fetchone():
                fail(409, "资源未发布")
        db.execute(
            "INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)",
            (
                body.requestId,
                robot_id,
                user["id"],
                "control",
                0,
                payload,
                time.time() + 10,
                "pending",
                time.time(),
            ),
        )
    return {"requestId": body.requestId, "state": "pending"}


@router.post("/recover")
def recover(body: Recover, request: Request):
    store = request.app.state.store
    token = secrets.token_urlsafe(32)
    with store.transaction() as db:
        invite = consume(db, body.invite, "recover")
        rid = invite["robot_id"]
        db.execute(
            "UPDATE devices SET token_hash=?,revoked=0 WHERE id=? AND role=?",
            (digest(token), rid, "robot"),
        )
        db.execute("UPDATE devices SET revoked=1 WHERE robot_id=?", (rid,))
        db.execute(
            "UPDATE commands SET state='cancelled' WHERE robot_id=? AND state='pending'",
            (rid,),
        )
        db.execute("UPDATE pairs SET state='rejected' WHERE robot_id=?", (rid,))
        db.execute("UPDATE invites SET consumed=1 WHERE robot_id=?", (rid,))
    return {"deviceId": rid, "token": token, "serviceId": store.meta("service_id")}

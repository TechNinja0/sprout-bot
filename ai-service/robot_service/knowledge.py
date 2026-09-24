"""家庭知识卡片：审核发布、内存检索、确定性回答，不调用模型。"""

import json
import re
import threading
import time
from collections import defaultdict
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import Field, field_validator, model_validator

from .auth import fail, parent
from .schemas_base import Strict
from .store import dumps, uid

router = APIRouter(prefix="/v1/knowledge", tags=["knowledge"])
MAX_CARDS = 5000
MISS = "这个问题我还没有找到可靠的讲解，暂时不能确定。可以和爸爸妈妈一起查一查。"
FOLLOWUPS = {
    "再详细讲讲",
    "详细一点",
    "详细说说",
    "再说详细点",
    "讲详细一点",
    "简单一点",
    "简单说",
    "简短一点",
    "一句话说",
    "再讲一遍",
    "再说一遍",
}


def normalize(text):
    text = re.sub(r"(?<=\d)\.(?=\d)", "小数点", text)
    text = re.sub(r"[-−](?=\d)", "负", text)
    text = text.replace("%", "百分比").replace("℃", "摄氏度").replace("°", "度")
    text = re.sub(r"[\W_]+", "", text.casefold(), flags=re.UNICODE)
    if text in FOLLOWUPS:
        return text
    # 只去掉明确的礼貌/详略要求，绝不去掉否定或颜色、数量等限定。
    text = re.sub(r"^(?:请)?(?:简单说说|简单说|详细讲讲)", "", text)
    text = re.sub(
        r"^(?:请)?(?:用一句话|一句话)?(?:告诉我|给我讲讲|给我说说|解释一下|讲讲|请问)",
        "",
        text,
    )
    text = re.sub(
        r"(?:请详细解释|请详细说说|详细讲讲|简单说说|简单一点|详细一点)$", "", text
    )
    return text


def factual_question(text):
    if re.search(
        r"你叫什么|你是谁|你好吗|你会|喜欢我|爱我|讨厌我|不理我|难过|伤心|害怕|生气|孤独|我不开心|英语|英文|故事|\benglish\b",
        text,
        re.I,
    ):
        return False
    return bool(
        re.search(
            r"在哪里|在哪儿|放哪|什么时候|哪里|哪儿|有.+吗|是.+吗|为什么|为何|怎么形成|怎么产生|怎么来的|是什么原理|是什么原因|是怎么|什么是|是什么|多少|多远|多久|多大|多高|会不会|是不是|能不能|自己发光|\b(?:why|what is|how does)\b",
            text,
            re.I,
        )
    )


def answer_mode(text):
    if re.search(r"一句话|简单|简短|短一点|少说|brief", text, re.I):
        return "brief"
    if re.search(r"详细|展开|多讲|多说|detail", text, re.I):
        return "detail"
    return "standard"


class Card(Strict):
    question: str = Field(min_length=1, max_length=120)
    aliases: list[str] = Field(default_factory=list, max_length=20)
    answer: str = Field(default="", max_length=550)
    briefAnswer: str = Field(default="", max_length=150)
    detailAnswer: str = Field(default="", max_length=580)
    category: str = Field(default="生活常识", min_length=1, max_length=30)
    kind: Literal["encyclopedia", "family"] = "encyclopedia"
    minAge: int = Field(default=3, ge=3, le=18)
    maxAge: int = Field(default=12, ge=3, le=18)
    source: str = Field(default="", max_length=200)
    sourceUrl: str = Field(default="", max_length=500)

    @field_validator(
        "question",
        "answer",
        "briefAnswer",
        "detailAnswer",
        "category",
        "source",
        "sourceUrl",
        mode="before",
    )
    @classmethod
    def trim(cls, value):
        return value.strip() if isinstance(value, str) else value

    @field_validator("aliases")
    @classmethod
    def aliases_valid(cls, values):
        if any(not normalize(v) or len(v) > 120 for v in values):
            raise ValueError("相似问法不能为空，每条最多120字")
        return list(dict.fromkeys(v.strip() for v in values))

    @field_validator("sourceUrl")
    @classmethod
    def url_valid(cls, value):
        if value and not re.fullmatch(r"https?://[^\s]+", value):
            raise ValueError("来源链接需要以 http:// 或 https:// 开头")
        return value

    @model_validator(mode="after")
    def valid_card(self):
        if not normalize(self.question):
            raise ValueError("请填写有效的核心问题")
        if self.minAge > self.maxAge:
            raise ValueError("最小年龄不能大于最大年龄")
        return self


class Create(Strict):
    id: str = Field(default_factory=uid, pattern=r"^[a-f0-9]{32}$")
    draft: Card


class Version(Strict):
    expectedVersion: int = Field(ge=1)


class Edit(Version):
    draft: Card


class Publish(Version):
    reviewed: Literal[True]


class Preview(Strict):
    text: str = Field(min_length=1, max_length=1000)
    age: int = Field(default=5, ge=3, le=18)
    mode: Literal["auto", "standard", "brief", "detail"] = "auto"
    draft: Card | None = None


@dataclass(frozen=True)
class Snapshot:
    entries: dict
    questions: dict
    postings: dict


def grams(text):
    # 排除通用问句词，避免把“苹果是什么”误提示为“云是什么”。
    text = re.sub(
        r"为什么|是什么|怎么|什么|为何|多少|是不是|能不能|会不会|的|吗|呢", "", text
    )
    return (
        {text[i : i + 2] for i in range(len(text) - 1)}
        if len(text) > 1
        else ({text} if text else set())
    )


def make_snapshot(entries):
    questions, postings = defaultdict(set), defaultdict(set)
    for ident, entry in entries.items():
        for question in [entry["card"]["question"], *entry["card"]["aliases"]]:
            normalized = normalize(question)
            questions[normalized].add(ident)
            for gram in grams(normalized):
                postings[gram].add(ident)
    return Snapshot(
        entries, dict(questions), {k: tuple(sorted(v)) for k, v in postings.items()}
    )


def render(entry, mode, age):
    card = entry["card"]
    if mode == "brief":
        answer = card["briefAnswer"] or re.split(r"(?<=[。！？.!?])", card["answer"])[0]
    elif mode == "detail" or (mode == "auto" and age >= 7):
        answer = card["detailAnswer"] or card["answer"]
    else:
        answer = card["answer"]
    return {
        "status": "matched",
        "text": answer,
        "knowledge": {
            "id": entry["id"],
            "version": entry["version"],
            "question": card["question"],
            "source": card["source"] or "家长提供",
            "sourceUrl": card["sourceUrl"],
            "kind": card["kind"],
            "origin": entry["origin"],
        },
        "candidates": [],
    }


class Knowledge:
    def __init__(self, store):
        self.store = store
        self.lock = threading.RLock()
        self.revision = 0
        seeds = json.loads(
            (Path(__file__).parent / "data/knowledge-seed.json").read_text()
        )
        with store.transaction() as db:
            for row in seeds:
                card = Card.model_validate(row["card"]).model_dump()
                blocked = db.execute(
                    "SELECT 1 FROM tombstones WHERE id=? AND kind='knowledge'",
                    (row["id"],),
                ).fetchone()
                if not blocked:
                    db.execute(
                        "INSERT OR IGNORE INTO knowledge_cards VALUES(?,?,?,?,?,?,?)",
                        (
                            row["id"],
                            1,
                            "builtin",
                            "enabled",
                            dumps(card),
                            dumps({"version": 1, "card": card}),
                            time.time(),
                        ),
                    )
        self.refresh()

    def refresh(self):
        with self.lock:
            entries = {}
            for row in self.store.read(
                "SELECT id,origin,published FROM knowledge_cards WHERE state='enabled'"
            ):
                entries[row["id"]] = {
                    "id": row["id"],
                    "origin": row["origin"],
                    **json.loads(row["published"]),
                }
            self.snapshot = make_snapshot(entries)
            self.revision += 1

    def query(self, text, age=5, mode="auto", previous_id=None, draft=None):
        start = time.perf_counter()
        snapshot = (
            self.snapshot
            if draft is None
            else make_snapshot(
                {
                    "draft": {
                        "id": "draft",
                        "origin": "parent",
                        "version": 0,
                        "card": draft.model_dump(),
                    }
                }
            )
        )
        question = normalize(text)
        selected_mode = answer_mode(text) if mode == "auto" else mode
        if mode == "auto" and selected_mode == "standard" and age >= 7:
            selected_mode = "auto"
        entries = snapshot.entries
        ids = snapshot.questions.get(question, set())
        if previous_id and question in FOLLOWUPS:
            ids = {previous_id}
        valid = [
            entries[k]
            for k in sorted(ids)
            if k in entries
            and entries[k]["card"]["minAge"] <= age <= entries[k]["card"]["maxAge"]
        ]
        if len(valid) == 1:
            result = render(valid[0], selected_mode, age)
        elif valid:
            result = {
                "status": "ambiguous",
                "text": "这个问题有不同的讲解，请说得更具体一点。",
                "candidates": [
                    {"id": x["id"], "question": x["card"]["question"]}
                    for x in valid[:3]
                ],
            }
        else:
            result = {"status": "miss", "text": MISS, "candidates": []}
            # 普通寒暄在常数时间返回，不构建候选集。
            if len(question) <= 160 and (factual_question(text) or draft is not None):
                counts = defaultdict(int)
                for gram in grams(question):
                    # 高频词不扫描整库；最多64条/词，候选最多64条。
                    for ident in snapshot.postings.get(gram, ())[:64]:
                        counts[ident] += 1
                for ident in sorted(counts, key=counts.get, reverse=True)[:64]:
                    if time.perf_counter() - start >= 0.004:
                        break
                    card = entries[ident]["card"]
                    if not card["minAge"] <= age <= card["maxAge"]:
                        continue
                    score = 0
                    for q in [card["question"], *card["aliases"]]:
                        if time.perf_counter() - start >= 0.004:
                            break
                        score = max(
                            score, SequenceMatcher(None, question, normalize(q)).ratio()
                        )
                    if score >= 0.62:
                        result["candidates"].append(
                            {
                                "id": ident,
                                "question": card["question"],
                                "score": round(score, 3),
                            }
                        )
                result["candidates"] = sorted(
                    result["candidates"], key=lambda x: x["score"], reverse=True
                )[:3]
                if result["candidates"]:
                    result["status"] = "clarify"
                    result["text"] = (
                        "我还不能确定你问的是不是“"
                        + result["candidates"][0]["question"].rstrip("？?")
                        + "”，可以再说具体一点吗？"
                    )
        result["lookupMs"] = round((time.perf_counter() - start) * 1000, 3)
        return result


def try_answer(request, text, age, prior, simple=False):
    recent = time.monotonic() - prior.get("at", 0) < 600
    previous = (prior.get("knowledge") or {}) if recent else {}
    pending = prior.get("pendingKnowledge") if recent else None
    confirmation = normalize(text)
    confirmed = bool(
        pending
        and confirmation in {"是", "是的", "对", "对的", "就是这个", "是这个", "没错"}
    )
    if pending and confirmation in {"不是", "不对", "不是这个", "不是的"}:
        return {
            "action": "speak",
            "status": "miss",
            "knowledgeStatus": "miss",
            "text": "好的，请把想问的问题完整说一遍。",
            "story": False,
        }
    mode = answer_mode(text)
    if mode == "standard":
        mode = "standard" if simple else "auto"
    result = request.app.state.knowledge.query(
        "再讲一遍" if confirmed else text,
        age,
        mode,
        previous_id=pending if confirmed else previous.get("id"),
    )
    if (
        confirmed
        or result["status"] == "matched"
        or result["status"] == "ambiguous"
        or factual_question(text)
        or (previous and normalize(text) in FOLLOWUPS)
    ):
        return {
            "action": "speak",
            **result,
            "knowledgeStatus": result["status"],
            "story": False,
        }
    return None


def detail(store, ident):
    row = store.one(
        "SELECT * FROM knowledge_cards WHERE id=? AND state!='deleted'", (ident,)
    )
    if not row:
        fail(404, "知识卡片不存在或已删除")
    row["draft"] = json.loads(row["draft"])
    row["published"] = json.loads(row["published"]) if row["published"] else None
    row["hasChanges"] = bool(
        row["published"] and row["draft"] != row["published"]["card"]
    )
    return row


def checked(db, ident, version):
    row = db.execute(
        "SELECT * FROM knowledge_cards WHERE id=? AND state!='deleted'", (ident,)
    ).fetchone()
    if not row:
        fail(404, "知识卡片不存在或已删除")
    if row["version"] != version:
        fail(409, "知识已被其他家长修改，请保留当前输入并返回列表重新读取")
    return row


def changed(request):
    request.app.state.knowledge.refresh()
    # 清除包含旧知识的上下文。使用独立的索引代次取消正在生成的旧回答。
    for sessions in (request.app.state.sessions, request.app.state.debug_sessions):
        for session in list(sessions.values()):
            if session.get("knowledgeSeen") or session.get("knowledge"):
                session["history"] = []


@router.get("")
def listing(
    request: Request,
    q: str = Query(default="", max_length=120),
    state: Literal["", "enabled", "draft", "disabled"] = "",
    user=Depends(parent),
):
    items, total = [], 0
    for row in request.app.state.store.read(
        "SELECT * FROM knowledge_cards WHERE state!='deleted' ORDER BY updated DESC,id"
    ):
        card = json.loads(row["draft"])
        if state and row["state"] != state:
            continue
        if q and q.casefold() not in dumps(card).casefold():
            continue
        total += 1
        if len(items) < 200:
            published = json.loads(row["published"]) if row["published"] else None
            items.append(
                {
                    "id": row["id"],
                    "version": row["version"],
                    "question": card["question"],
                    "category": card["category"],
                    "kind": card["kind"],
                    "state": row["state"],
                    "origin": row["origin"],
                    "hasChanges": bool(published and card != published["card"]),
                }
            )
    return {"items": items, "total": total}


@router.post("")
def create(body: Create, request: Request, user=Depends(parent)):
    store = request.app.state.store
    with request.app.state.knowledge.lock, store.transaction() as db:
        existing = db.execute(
            "SELECT draft,state FROM knowledge_cards WHERE id=?", (body.id,)
        ).fetchone()
        if existing:
            if existing["state"] != "draft" or existing["draft"] != dumps(
                body.draft.model_dump()
            ):
                fail(409, "这次新增已保存，请返回列表重新读取")
        else:
            if (
                db.execute(
                    "SELECT COUNT(*) FROM knowledge_cards WHERE state!='deleted'"
                ).fetchone()[0]
                >= MAX_CARDS
            ):
                fail(409, "知识库已达到5000条上限，请先整理内容")
            db.execute(
                "INSERT INTO knowledge_cards VALUES(?,?,?,?,?,?,?)",
                (
                    body.id,
                    1,
                    "parent",
                    "draft",
                    dumps(body.draft.model_dump()),
                    None,
                    time.time(),
                ),
            )
    return detail(store, body.id)


# 静态路径先于 /{ident}，避免 preview 被当作卡片ID。
@router.post("/preview")
def preview(body: Preview, request: Request, user=Depends(parent)):
    if body.draft is not None and not body.draft.answer:
        fail(422, "请先填写标准讲解，再试问预览")
    return {
        **request.app.state.knowledge.query(
            body.text, body.age, body.mode, draft=body.draft
        ),
        "draft": body.draft is not None,
    }


@router.get("/{ident}")
def read(ident: str, request: Request, user=Depends(parent)):
    return detail(request.app.state.store, ident)


@router.put("/{ident}")
def edit(ident: str, body: Edit, request: Request, user=Depends(parent)):
    store = request.app.state.store
    with request.app.state.knowledge.lock, store.transaction() as db:
        checked(db, ident, body.expectedVersion)
        db.execute(
            "UPDATE knowledge_cards SET version=version+1,draft=?,updated=? WHERE id=?",
            (dumps(body.draft.model_dump()), time.time(), ident),
        )
    return detail(store, ident)


@router.post("/{ident}/publish")
def publish(ident: str, body: Publish, request: Request, user=Depends(parent)):
    manager, store = request.app.state.knowledge, request.app.state.store
    with manager.lock:
        with store.transaction() as db:
            row = checked(db, ident, body.expectedVersion)
            card = Card.model_validate_json(row["draft"])
            if not card.answer or (card.kind == "encyclopedia" and not card.source):
                fail(422, "发布需要标准讲解；儿童百科还需要填写来源说明")
            version = row["version"] + 1
            db.execute(
                "UPDATE knowledge_cards SET version=?,state='enabled',published=?,updated=? WHERE id=?",
                (
                    version,
                    dumps({"version": version, "card": card.model_dump()}),
                    time.time(),
                    ident,
                ),
            )
        changed(request)
    return detail(store, ident)


@router.post("/{ident}/disable")
def disable(ident: str, body: Version, request: Request, user=Depends(parent)):
    manager, store = request.app.state.knowledge, request.app.state.store
    with manager.lock:
        with store.transaction() as db:
            checked(db, ident, body.expectedVersion)
            db.execute(
                "UPDATE knowledge_cards SET version=version+1,state='disabled',updated=? WHERE id=?",
                (time.time(), ident),
            )
        changed(request)
    return detail(store, ident)


@router.delete("/{ident}")
def delete(
    ident: str,
    request: Request,
    expectedVersion: int = Query(ge=1),
    user=Depends(parent),
):
    manager, store = request.app.state.knowledge, request.app.state.store
    with manager.lock:
        with store.transaction() as db:
            checked(db, ident, expectedVersion)
            db.execute(
                "UPDATE knowledge_cards SET version=version+1,state='deleted',draft='{}',published=NULL,updated=? WHERE id=?",
                (time.time(), ident),
            )
            db.execute(
                "INSERT OR IGNORE INTO tombstones VALUES(?,?,?)",
                (ident, "knowledge", time.time()),
            )
        changed(request)
    return {"deleted": True}

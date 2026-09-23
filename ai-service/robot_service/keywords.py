import re
from pathlib import Path

from fastapi import APIRouter, Depends, Request
from pypinyin import Style, pinyin

from .auth import robot

router = APIRouter(prefix="/v1")

CONTROLS = {
    "停止": "stop",
    "停一下": "stop",
    "别说了": "stop",
    "暂停": "stop",
    "继续": "resume",
    "下一页": "next",
    "上一页": "previous",
    "下一章": "next_chapter",
    "上一章": "previous_chapter",
    "休息吧": "rest",
    "再见": "rest",
    "别看了": "camera_off",
    "不要拍了": "camera_off",
}


def generate(nickname, sensitivity="standard"):
    if not re.fullmatch(r"[\u4e00-\u9fff]{2,6}", nickname) or nickname in CONTROLS:
        raise ValueError("唤醒昵称需2—6个汉字，且不能使用停止/继续等口令")
    lines = []
    for word, label in [
        (nickname, "wake"),
        ("你好" + nickname, "wake"),
        *CONTROLS.items(),
    ]:
        line = phrase(word, label).strip()
        if label == "wake":
            threshold = {"low": 0.35, "standard": 0.25, "high": 0.15}[sensitivity]
            line = line.replace(" @wake", f" #{threshold} @wake")
        lines.append(line)
    return "\n".join(lines) + "\n"


def phrase(word, label):
    initial = pinyin(word, style=Style.INITIALS, strict=False)
    final = pinyin(word, style=Style.FINALS_TONE, strict=False)
    parts = [x for a, b in zip(initial, final) for x in [a[0], b[0]] if x]
    supported = {
        line.split()[0]
        for line in (Path(__file__).parent / "data/kws-tokens.txt")
        .read_text()
        .splitlines()
    }
    if any(part not in supported for part in parts):
        raise ValueError("拼音暂不受本地唤醒模型支持")
    return " ".join(parts) + " @" + label + "\n"


def book_keywords(rid, metadata):
    lines = []
    for name in [metadata["title"], *metadata.get("aliases", [])]:
        if re.fullmatch(r"[\u4e00-\u9fff]{2,12}", name):
            try:
                lines.append(phrase("读" + name, "book_" + rid))
            except ValueError:
                pass
    return "".join(lines[:3])


@router.get("/kws/keywords")
def keywords(request: Request, user=Depends(robot)):
    import json

    row = request.app.state.store.one(
        "SELECT body,version FROM configs WHERE robot_id=?", (user["id"],)
    )
    config = json.loads(row["body"])
    nickname = config["nickname"]
    return {
        "version": row["version"],
        "nickname": nickname,
        "keywords": generate(
            nickname, config.get("interaction", {}).get("wakeSensitivity", "standard")
        ),
    }

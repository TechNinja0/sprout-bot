"""图书原页与合成分组分离；原文不改写，时间定位只接受可靠音频对齐。"""

import re

from .store import digest, dumps

SEGMENTATION_VERSION = "semantic-pages-2"
MAX_SEGMENT = 180
MAX_GROUP = 120


def semantic_chunks(text, limit=MAX_SEGMENT):
    """优先句末，其次从句/空白；仅无边界的超长文本才按上限切开。"""
    chunks = []
    remaining = text
    while len(remaining) > limit:
        window = remaining[:limit]
        ends, pauses = [], []
        for i, char in enumerate(window):
            if char in "。！？!?\n":
                end = i + 1
                while end < len(window) and window[end] in '”’」』）)]"':
                    end += 1
                ends.append(end)
            elif char == ".":
                # 小数、缩写和域名中的句点不是句末。
                following = remaining[i + 1 : i + 2]
                word = re.search(r"[A-Za-z.]+$", window[: i + 1])
                token = word[0].lower() if word else ""
                if (
                    (not following or following.isspace())
                    and token
                    not in {
                        "mr.",
                        "mrs.",
                        "ms.",
                        "dr.",
                        "prof.",
                        "e.g.",
                        "i.e.",
                        "u.s.",
                        "u.k.",
                    }
                    and not re.fullmatch(r"[a-z]\.", token)
                ):
                    ends.append(i + 1)
            if char in "，,；;：:" or char.isspace():
                pauses.append(i + 1)
        candidates = ends or pauses
        end = candidates[-1] if candidates else limit
        chunks.append(remaining[:end])
        remaining = remaining[end:]
    if remaining:
        chunks.append(remaining)
    return chunks


def segments(draft):
    result = []
    language = draft.get("language", "zh")
    if language == "bilingual":
        language = (
            "zh"
            if any(
                re.search(r"[\u3400-\u9fff]", p["text"])
                for p in draft["pages"]
                if not p.get("skip")
            )
            else "en"
        )
    for page_index, page in enumerate(draft["pages"]):
        if page.get("skip"):
            continue
        for index, chunk in enumerate(semantic_chunks(page["text"])):
            if not chunk.strip():
                # 不丢弃空白字符：附到本页已有段；空白整页由发布校验处理。
                if result and result[-1]["pageId"] == page["id"]:
                    result[-1]["text"] += chunk
                continue
            result.append(
                {
                    "id": digest(page["id"] + ":" + str(index) + ":" + chunk)[:24],
                    "pageId": page["id"],
                    "label": page.get("label", ""),
                    "chapter": page.get("chapter", ""),
                    "text": chunk,
                    "pronunciation": page.get("pronunciation", {}),
                    "language": language,
                    "synthesisVariant": page.get("synthesisVariant", 0),
                    "pageIndex": page_index,
                    "breakBefore": page.get("breakBefore", False),
                }
            )
    # 连续模式只合并相邻短页，不跨章节、空白/跳过页或家长指定的场景边界。
    groups = []
    page_counts = {}
    for seg in result:
        page_counts[seg["pageId"]] = page_counts.get(seg["pageId"], 0) + 1
    for seg in result:
        prior = groups[-1] if groups else []
        merge = (
            draft.get("readingMode", "follow_pages") == "continuous"
            and prior
            and len(prior) < 4
            and not seg["breakBefore"]
            and prior[-1]["pageIndex"] + 1 == seg["pageIndex"]
            and prior[-1]["chapter"] == seg["chapter"]
            and page_counts[seg["pageId"]] == page_counts[prior[-1]["pageId"]] == 1
            and sum(len(spoken_text(s)) for s in [*prior, seg]) <= MAX_GROUP
        )
        if merge:
            prior.append(seg)
        else:
            groups.append([seg])
    for group in groups:
        for seg in group:
            seg["groupId"] = group[0]["id"]
    return result


def spoken_text(segment):
    text = segment["text"]
    for src, dst in sorted(
        segment.get("pronunciation", {}).items(), key=lambda x: -len(x[0])
    ):
        if src:
            text = text.replace(src, dst)
    return text


def synthesis_group(body, segment):
    ident = segment.get("groupId", segment["id"])
    return [
        s for s in body.get("segments", []) if s.get("groupId", s["id"]) == ident
    ] or [segment]


def group_cache_key(body, group):
    return digest(
        dumps(
            {
                "profile": body["ttsProfile"],
                "segmentation": SEGMENTATION_VERSION,
                "mode": body.get("readingMode", "follow_pages"),
                "voice": {k: v for k, v in body["voice"].items() if k != "volume"},
                "group": group,
            }
        )
    )


def group_variant(group):
    variants = [s.get("synthesisVariant", 0) for s in group]
    return 0 if not any(variants) else int(digest(dumps(variants))[:7], 16)

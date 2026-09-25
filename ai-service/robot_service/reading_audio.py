"""共享试听与发布的合成结果；可靠跨页对齐后仍向播放器提供逐页 WAV。"""

import asyncio
import base64
import io
import json
import wave

from fastapi import HTTPException

from .reading_speech import group_cache_key, group_variant, spoken_text
from .schemas import Voice
from .store import digest, dumps


def duration_ms(audio):
    with wave.open(io.BytesIO(audio)) as wav:
        return wav.getnframes() * 1000 // wav.getframerate()


def marker_path(directory, group):
    return directory / (group[0]["id"] + ".alignment.json")


def complete_group(directory, group, verify=True):
    """最后写清单作为提交标记；中断写入不能把半组声音当完整缓存。"""
    try:
        manifest = json.loads(marker_path(directory, group).read_text())
        if [s["id"] for s in manifest["segments"]] != [s["id"] for s in group]:
            return None
        for row in manifest["segments"]:
            path = directory / (row["id"] + ".wav")
            if path.stat().st_size != row["bytes"]:
                return None
            if verify and digest(path.read_bytes()) != row["sha256"]:
                return None
        return manifest
    except (OSError, ValueError, KeyError, TypeError):
        return None


def write_group(store, directory, group, audio, mode, offsets):
    rows = []
    # 调用者持有content_lock，并在写入前重新确认发布/草稿仍有效。
    marker_path(directory, group).unlink(missing_ok=True)
    for seg, data, offset in zip(group, audio, offsets, strict=True):
        store.write_content(directory / (seg["id"] + ".wav"), data)
        rows.append(
            {
                "id": seg["id"],
                "pageId": seg.get("pageId", ""),
                "startMs": offset[0],
                "endMs": offset[1],
                "sha256": digest(data),
                "bytes": len(data),
            }
        )
    store.write_content(
        marker_path(directory, group), dumps({"mode": mode, "segments": rows}).encode()
    )


def cache_directory(store, rid, body, group):
    return store.root / "audio" / ("previews-" + rid) / group_cache_key(body, group)


async def render_group(request, body, group, bulk=False):
    from .intelligence import root, speech, worker
    from .speech_alignment import split_aligned_audio
    from .speech_text import normalize_text

    voice = Voice.model_validate(body["voice"])
    texts = [spoken_text(s) for s in group]
    language = group[0].get("language")
    if language not in ("zh", "en"):
        language = None
    variant = group_variant(group)

    async def say(text, variant=variant):
        return await speech(
            request,
            text,
            voice,
            True,
            background=True,
            bulk=bulk,
            language=language,
            variant=variant,
        )

    # 仅已安装的本地对齐模型参与；不下载模型，也不在缺模型时浪费一次合成。
    align_available = (root(request) / "faster-whisper-small/model.bin").is_file()
    if len(group) > 1 and align_available:
        combined = await say((" " if language == "en" else "").join(texts))
        if duration_ms(combined) <= 30000:
            from .tts import output_quality

            try:
                result = await worker(
                    request,
                    {
                        "kind": "align",
                        "audio": base64.b64encode(
                            output_quality(combined, "standard")
                        ).decode(),
                        "language": language,
                    },
                    background=True,
                    timeout=45,
                )
                pieces = split_aligned_audio(
                    combined,
                    [normalize_text(t, language or "zh") for t in texts],
                    result.get("words", []),
                )
            except (HTTPException, ValueError, RuntimeError, OSError):
                pieces = None
            if pieces:
                return (
                    [p["audio"] for p in pieces],
                    "aligned",
                    [(p["startMs"], p["endMs"]) for p in pieces],
                )
    # 对齐失败按页生成，不按字数猜时间；重试不会覆盖已经发布的其他组。
    audio = [
        await say(text, s.get("synthesisVariant", 0))
        for s, text in zip(group, texts, strict=True)
    ]
    if body.get("readingMode") == "follow_pages":
        audio = [
            page_pause(data) if is_page_end(body, seg) else data
            for seg, data in zip(group, audio, strict=True)
        ]
    offsets, cursor = [], 0
    for data in audio:
        end = cursor + duration_ms(data)
        offsets.append((cursor, end))
        cursor = end
    return audio, "per_page" if len(group) == 1 else "fallback", offsets


def is_page_end(body, segment):
    parts = body.get("segments", [])
    index = next((i for i, s in enumerate(parts) if s["id"] == segment["id"]), None)
    return index is not None and (
        index + 1 == len(parts) or parts[index + 1]["pageId"] != segment["pageId"]
    )


def page_pause(audio):
    """跟书模式页末至少保留450ms数字静音，不删除任何原采样。"""
    import numpy as np

    with wave.open(io.BytesIO(audio)) as wav:
        rate, params = wav.getframerate(), wav.getparams()
        pcm = wav.readframes(wav.getnframes())
    if params.nchannels != 1 or params.sampwidth != 2:
        raise ValueError("需要单声道PCM16")
    samples = np.frombuffer(pcm, dtype="<i2")
    active = np.flatnonzero(samples)
    if not len(active):
        return audio
    padding = max(0, round(rate * 0.45) - (len(samples) - int(active[-1]) - 1))
    out = io.BytesIO()
    with wave.open(out, "wb") as wav:
        wav.setparams(params)
        wav.writeframes(pcm + bytes(padding * 2))
    return out.getvalue()


async def ensure_group(manager, request, rid, body, group, validate, bulk=False):
    directory = cache_directory(manager.store, rid, body, group)
    key = ("preview-" + rid, group_cache_key(body, group))
    if not complete_group(directory, group):
        pending = manager.inflight.get(key)
        if pending is None:

            async def render():
                audio, mode, offsets = await render_group(request, body, group, bulk)
                with manager.store.content_lock:
                    validate()
                    write_group(manager.store, directory, group, audio, mode, offsets)

            pending = asyncio.create_task(render())
            manager.inflight[key] = pending

            def finished(task):
                manager.inflight.pop(key, None)
                if not task.cancelled():
                    task.exception()

            pending.add_done_callback(finished)
        await asyncio.shield(pending)
    with manager.store.content_lock:
        validate()
        metadata = complete_group(directory, group)
        if metadata is None:
            raise HTTPException(503, "音频组尚未完整生成，请重试")
        return directory, metadata

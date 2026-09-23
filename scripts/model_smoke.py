#!/usr/bin/env python3
"""原创测试资料、真实本地模型；不冒充真实家庭样书或儿童验收。"""

import base64
import io
import json
import os
import secrets
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont
from robot_service.app import create_app

ROOT = Path(__file__).resolve().parents[1]
os.environ["ROBOT_MODELS"] = str(ROOT / "runtime/models")
output = ROOT / ".artifacts/development"
output.mkdir(parents=True, exist_ok=True)
report = {
    "evidence": "原创合成图文与真实本地模型；不是家庭样书/儿童声学验收",
    "checks": [],
    "passed": False,
}
(output / "model-integration.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2)
)


def mark(name, start, details):
    report["checks"].append(
        {
            "name": name,
            "seconds": round(time.monotonic() - start, 2),
            "details": details,
        }
    )
    print(name + " PASS", flush=True)


def check(response):
    assert response.status_code == 200, (response.status_code, response.text[:300])
    return response.json()


with tempfile.TemporaryDirectory(prefix="robot-model-smoke-") as folder:
    app = create_app(folder)
    with TestClient(app) as client:
        inv = app.state.store.issue_invite("register")
        robot = check(
            client.post("/v1/register", json={"invite": inv, "name": "原创资料测试"})
        )
        rh = {"Authorization": "Bearer " + robot["token"]}
        invite = check(client.post("/v1/pairing", headers=rh))["invite"]
        claim = check(
            client.post(
                "/v1/pairing/claim", json={"invite": invite, "name": "测试家长"}
            )
        )
        check(
            client.post(
                "/v1/pairing/" + claim["pairId"] + "/decision",
                headers=rh,
                json={"approved": True},
            )
        )
        token = secrets.token_urlsafe(32)
        check(
            client.post(
                "/v1/pairing/" + claim["pairId"] + "/complete",
                json={"claim": claim["claim"], "token": token},
            )
        )
        ph = {"Authorization": "Bearer " + token}
        book = check(
            client.post(
                "/v1/resources",
                headers=ph,
                json={
                    "kind": "book",
                    "draft": {
                        "title": "MY RED CAT",
                        "language": "en",
                        "edition": "原创测试版",
                        "source": "项目原创测试",
                    },
                },
            )
        )
        rid = book["id"]
        font = (
            ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 65)
            if os.uname().sysname == "Darwin"
            else ImageFont.truetype("DejaVuSans.ttf", 65)
        )
        cover = Image.new("RGB", (1000, 700), "white")
        draw = ImageDraw.Draw(cover)
        draw.text((100, 80), "MY RED CAT", font=font, fill="black")
        draw.ellipse((350, 250, 650, 550), fill="red")
        b = io.BytesIO()
        cover.save(b, format="PNG")
        cover_bytes = b.getvalue()
        (output / "original-test-cover.png").write_bytes(cover_bytes)
        start = time.monotonic()
        cover_upload = check(
            client.post(
                f"/v1/resources/{rid}/assets?purpose=cover&expectedVersion=1",
                headers=ph,
                files={"file": ("cover.png", cover_bytes, "image/png")},
            )
        )
        # PDF包含可选文字页与扫描图页，真实执行混合解析。
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

        writer = PdfWriter()
        page = writer.add_blank_page(width=600, height=800)
        font_ref = writer._add_object(
            DictionaryObject(
                {
                    NameObject("/Type"): NameObject("/Font"),
                    NameObject("/Subtype"): NameObject("/Type1"),
                    NameObject("/BaseFont"): NameObject("/Helvetica"),
                }
            )
        )
        page[NameObject("/Resources")] = DictionaryObject(
            {NameObject("/Font"): DictionaryObject({NameObject("/F1"): font_ref})}
        )
        stream = DecodedStreamObject()
        stream.set_data(b"BT /F1 30 Tf 60 600 Td (Hello, little red cat.) Tj ET")
        page[NameObject("/Contents")] = writer._add_object(stream)
        scanned = Image.new("RGB", (1000, 700), "white")
        ImageDraw.Draw(scanned).text(
            (80, 200), "The cat has a red ball.", font=font, fill="black"
        )
        scan = io.BytesIO()
        scanned.save(scan, format="PDF")
        writer.add_page(PdfReader(io.BytesIO(scan.getvalue())).pages[0])
        pdf = io.BytesIO()
        writer.write(pdf)
        upload = check(
            client.post(
                f"/v1/resources/{rid}/assets?purpose=pages&expectedVersion=2",
                headers=ph,
                files={"file": ("mixed.pdf", pdf.getvalue(), "application/pdf")},
            )
        )
        for _ in range(600):
            job = app.state.store.one(
                "SELECT * FROM jobs WHERE id=?", (upload["jobId"],)
            )
            if job["state"] not in ("queued", "processing"):
                break
            time.sleep(0.1)
        assert job["state"] == "needs_review", job["error"]
        book = check(client.get("/v1/resources/" + rid, headers=ph))
        pages = book["draft"]["pages"]
        assert len(pages) == 2
        assert "Hello" in pages[0]["text"] and "red ball" in pages[1]["text"], pages
        mark(
            "mixed_pdf_real_ocr",
            start,
            {"pages": len(pages), "text": [p["text"] for p in pages]},
        )
        # 原文审核先保存；试听确认是第二次提交，避免把旧试听状态复用到新正文。
        for p in pages:
            p["reviewed"] = True
        book["draft"]["complete"] = True
        book = check(
            client.put(
                "/v1/resources/" + rid,
                headers=ph,
                json={"expectedVersion": book["draft_version"], "draft": book["draft"]},
            )
        )
        start = time.monotonic()
        audio = client.post(
            "/v1/speech/preview",
            headers=ph,
            json={"text": pages[0]["text"], "voice": {}},
        )
        assert audio.status_code == 200 and audio.content.startswith(b"RIFF"), (
            audio.text[:100] if audio.status_code != 200 else ""
        )
        mark("real_tts_preview", start, {"audioBytes": len(audio.content)})
        book["draft"]["auditioned"] = True
        book = check(
            client.put(
                "/v1/resources/" + rid,
                headers=ph,
                json={"expectedVersion": book["draft_version"], "draft": book["draft"]},
            )
        )
        pub = check(
            client.post(
                "/v1/resources/" + rid + "/publish",
                headers=ph,
                json={
                    "expectedVersion": book["draft_version"],
                    "requestId": secrets.token_hex(16),
                },
            )
        )
        start = time.monotonic()
        recognized = check(
            client.post(
                "/v1/books/recognize",
                headers=rh,
                files={"file": ("cover.png", cover_bytes, "image/png")},
            )
        )
        assert (
            recognized["status"] == "MATCH"
            and recognized["candidates"][0]["resourceId"] == rid
        )
        mark("real_cover_ocr_lookup", start, {"status": recognized["status"]})
        manifest = check(client.get("/v1/resources/" + rid + "/manifest", headers=rh))
        start = time.monotonic()
        for segment in manifest["segments"]:
            a = client.get(
                f"/v1/resources/{rid}/audio/{segment['id']}?revisionId={pub['revisionId']}",
                headers=rh,
            )
            assert a.status_code == 200 and a.headers["x-content-sha256"]
        mark(
            "published_original_segment_audio",
            start,
            {"segments": len(manifest["segments"])},
        )
        # 真实语音模型回读；合成输入只验证接口/编码链路，不代表儿童识别率。
        for sentence, expected in [
            ("你好，我是小伙伴，我们一起读书吧。", "小伙伴"),
            ("Hello, little red cat.", "red cat"),
        ]:
            start = time.monotonic()
            audio = client.post(
                "/v1/speech/preview", headers=ph, json={"text": sentence, "voice": {}}
            )
            assert audio.status_code == 200
            with wave.open(io.BytesIO(audio.content)) as source:
                rate = source.getframerate()
                samples = np.frombuffer(
                    source.readframes(source.getnframes()), dtype="<i2"
                )
            values = np.interp(
                np.arange(int(len(samples) * 16000 / rate)) * rate / 16000,
                np.arange(len(samples)),
                samples,
            ).astype("<i2")
            speech = io.BytesIO()
            with wave.open(speech, "wb") as dest:
                dest.setnchannels(1)
                dest.setsampwidth(2)
                dest.setframerate(16000)
                dest.writeframes(values.tobytes())
            recognized_speech = check(
                client.post(
                    "/v1/speech/recognize",
                    headers=rh,
                    files={"file": ("original.wav", speech.getvalue(), "audio/wav")},
                )
            )
            assert expected.casefold() in recognized_speech["text"].casefold(), (
                recognized_speech
            )
            mark(
                "real_synthetic_speech_roundtrip",
                start,
                {"input": sentence, "recognized": recognized_speech["text"]},
            )
        silence = io.BytesIO()
        with wave.open(silence, "wb") as dest:
            dest.setnchannels(1)
            dest.setsampwidth(2)
            dest.setframerate(16000)
            dest.writeframes(bytes(16000 * 2 * 2))
        start = time.monotonic()
        silent_result = check(
            client.post(
                "/v1/speech/recognize",
                headers=rh,
                files={"file": ("silence.wav", silence.getvalue(), "audio/wav")},
            )
        )
        assert not silent_result["text"], silent_result
        mark("real_asr_silence", start, {"text": silent_result["text"]})
        start = time.monotonic()
        answer = check(
            client.post(
                "/v1/turns",
                headers=rh,
                json={
                    "sessionId": secrets.token_hex(16),
                    "text": "图片中的圆是什么颜色？",
                    "image": base64.b64encode(cover_bytes).decode(),
                    "imageAgeMs": 0,
                },
            )
        )
        assert "红" in answer["text"] or "red" in answer["text"].lower(), answer
        mark("real_local_vision", start, {"answer": answer["text"]})
        check(client.post("/v1/resources/" + rid + "/unlist", headers=ph))
        assert (
            client.get("/v1/resources/" + rid + "/manifest", headers=rh).status_code
            == 409
        )
report["passed"] = True
(output / "model-integration.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2)
)

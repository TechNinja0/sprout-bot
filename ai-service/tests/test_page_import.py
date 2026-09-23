import io
import json
import sqlite3
from types import SimpleNamespace

import pytest
from PIL import Image
from robot_service.extract import extract
from robot_service.imports import ingest_asset, process_job
from robot_service.inbox import import_directory
from robot_service.store import SCHEMA, Store


def new_book(c, ph):
    return c.post(
        "/v1/resources",
        headers=ph,
        json={
            "kind": "book",
            "draft": {
                "title": "按页识别",
                "pages": [
                    {"id": "reviewed-page", "text": "已校对内容", "reviewed": True}
                ],
            },
        },
    ).json()


def png():
    buffer = io.BytesIO()
    Image.new("RGB", (20, 40), "white").save(buffer, format="PNG")
    return buffer.getvalue()


def fake_extraction(monkeypatch, pages, during=None):
    def run(command, **kwargs):
        if during:
            during({k: v for k, v in json.loads(command[-1]).items() if k != "stream"})
        return SimpleNamespace(stdout=json.dumps(pages).encode())

    monkeypatch.setattr("robot_service.imports.subprocess.run", run)


def sync_pool(c, monkeypatch):
    monkeypatch.setattr(c.app.state.import_pool, "submit", lambda fn, *args: fn(*args))


def test_old_database_migration_is_repeatable(tmp_path):
    with sqlite3.connect(tmp_path / "library.sqlite3") as db:
        db.executescript(SCHEMA)
        db.execute("INSERT INTO meta VALUES('schema','1')")
        db.execute(
            "INSERT INTO jobs VALUES('job','book','asset',1,'cancelled','{}',NULL,0)"
        )
    for _ in range(2):
        store = Store(tmp_path)
        assert store.meta("schema") == "2"
        assert json.loads(store.one("SELECT * FROM jobs")["options"]) == {}


def test_partial_ocr_retry_preserves_other_reviewed_page_and_replaces_only_target(
    system, monkeypatch
):
    c, store, _, _, _, ph = system
    book = new_book(c, ph)
    original = book["draft"]["pages"][0]
    sync_pool(c, monkeypatch)
    fake_extraction(
        monkeypatch,
        [
            {
                "sourcePage": 0,
                "text": "",
                "extractionError": "TimeoutExpired",
                "qualityWarnings": ["OCR_FAILED"],
            }
        ],
    )
    path = "/v1/resources/" + book["id"]
    response = c.post(
        path + "/assets?purpose=pages&expectedVersion=1",
        headers=ph,
        files={"file": ("page.png", png(), "image/png")},
    )
    assert response.status_code == 200
    draft = c.get(path, headers=ph).json()
    target = draft["draft"]["pages"][1]
    assert target["label"] == ""  # 原稿未识别印刷页码，不能把导入顺序冒充页码。
    assert target["qualityWarnings"] == ["OCR_FAILED"]
    assert draft["draft"]["pages"][0] == original
    received = []
    fake_extraction(
        monkeypatch,
        [
            {
                "sourcePage": 0,
                "text": "旋转后的内容",
                "confidence": 0.6,
                "qualityWarnings": ["LOW_CONFIDENCE"],
            }
        ],
        during=received.append,
    )
    response = c.post(
        path + "/pages/" + target["id"] + "/extract",
        headers=ph,
        json={"expectedVersion": 2, "rotation": 90},
    )
    assert response.status_code == 200
    draft = c.get(path, headers=ph).json()
    assert len(draft["draft"]["pages"]) == 2
    assert draft["draft"]["pages"][0] == original
    changed = draft["draft"]["pages"][1]
    assert changed["id"] == target["id"] and changed["sourceRotation"] == 90
    assert changed["text"] == "旋转后的内容" and not changed["reviewed"]
    assert received == [{"targetPageId": target["id"], "sourcePage": 0, "rotation": 90}]
    image = c.get(
        "/v1/assets/" + target["sourceAsset"] + "/page/0?rotation=90", headers=ph
    )
    assert image.status_code == 200, image.text
    assert Image.open(io.BytesIO(image.content)).size == (40, 20)
    assert (
        c.get("/v1/assets/" + target["sourceAsset"] + "/page/1", headers=ph).status_code
        == 404
    )


def test_retake_and_stale_result_cannot_overwrite_parent_edit(system, monkeypatch):
    c, store, _, _, _, ph = system
    book = new_book(c, ph)
    rid = book["id"]
    uploaded = ingest_asset(store, rid, "new.png", png(), "pages", 1, "reviewed-page")

    def change_draft(options):
        assert options["targetPageId"] == "reviewed-page"
        draft = c.get("/v1/resources/" + rid, headers=ph).json()["draft"]
        draft["pages"][0]["text"] = "识别期间修改的文字"
        assert (
            c.put(
                "/v1/resources/" + rid,
                headers=ph,
                json={"expectedVersion": 1, "draft": draft},
            ).status_code
            == 200
        )

    fake_extraction(monkeypatch, [{"sourcePage": 0, "text": "迟到结果"}], change_draft)
    process_job(store, uploaded["jobId"])
    assert (
        store.one("SELECT state FROM jobs WHERE id=?", (uploaded["jobId"],))["state"]
        == "stale"
    )
    assert (
        c.get("/v1/resources/" + rid, headers=ph).json()["draft"]["pages"][0]["text"]
        == "识别期间修改的文字"
    )
    fake_extraction(monkeypatch, [{"sourcePage": 0, "text": "重拍的新正文"}])
    sync_pool(c, monkeypatch)
    retried = c.post("/v1/jobs/" + uploaded["jobId"] + "/retry", headers=ph)
    assert retried.status_code == 200
    page = c.get("/v1/resources/" + rid, headers=ph).json()["draft"]["pages"][0]
    assert (
        page["id"] == "reviewed-page"
        and page["text"] == "重拍的新正文"
        and not page["reviewed"]
    )
    assert page["sourceAsset"] == uploaded["assetId"]


def test_failed_retake_preserves_corrected_text_and_source(system, monkeypatch):
    c, store, _, _, _, ph = system
    book = new_book(c, ph)
    job = ingest_asset(store, book["id"], "new.png", png(), "pages", 1, "reviewed-page")
    fake_extraction(
        monkeypatch,
        [
            {
                "sourcePage": 0,
                "text": "",
                "extractionError": "RuntimeError",
                "qualityWarnings": ["OCR_FAILED"],
            }
        ],
    )
    process_job(store, job["jobId"])
    page = c.get("/v1/resources/" + book["id"], headers=ph).json()["draft"]["pages"][0]
    assert page["text"] == "已校对内容" and page["sourceAsset"] == ""
    assert page["extractionError"] == "RuntimeError"
    assert not page["reviewed"]
    assert (
        store.one("SELECT state FROM jobs WHERE id=?", (job["jobId"],))["state"]
        == "failed"
    )
    fake_extraction(monkeypatch, [{"sourcePage": 0, "text": "重拍识别成功"}])
    sync_pool(c, monkeypatch)
    assert c.post("/v1/jobs/" + job["jobId"] + "/retry", headers=ph).status_code == 200
    page = c.get("/v1/resources/" + book["id"], headers=ph).json()["draft"]["pages"][0]
    assert page["sourceAsset"] == job["assetId"] and page["text"] == "重拍识别成功"


def test_cancel_during_extraction_discards_late_pages_and_retry_uses_new_job(
    system, monkeypatch
):
    c, store, _, _, _, ph = system
    book = new_book(c, ph)
    path = "/v1/resources/" + book["id"]
    job = ingest_asset(store, book["id"], "page.png", png(), "pages", 1)

    def cancel_while_running(options):
        assert (
            c.get("/v1/jobs/" + job["jobId"], headers=ph).json()["state"]
            == "processing"
        )
        assert (
            c.post("/v1/jobs/" + job["jobId"] + "/cancel", headers=ph).status_code
            == 200
        )

    fake_extraction(
        monkeypatch,
        [{"sourcePage": 0, "text": "取消后迟到的内容"}],
        cancel_while_running,
    )
    process_job(store, job["jobId"])
    unchanged = c.get(path, headers=ph).json()
    assert unchanged["draft"] == book["draft"]
    assert unchanged["draft_version"] == book["draft_version"]
    assert c.get("/v1/jobs/" + job["jobId"], headers=ph).json()["state"] == "cancelled"

    fake_extraction(monkeypatch, [{"sourcePage": 0, "text": "恢复识别的正文"}])
    sync_pool(c, monkeypatch)
    retry = c.post("/v1/jobs/" + job["jobId"] + "/retry", headers=ph).json()
    assert retry["jobId"] != job["jobId"]
    restored = c.get(path, headers=ph).json()
    assert restored["draft"]["pages"][0] == book["draft"]["pages"][0]
    assert restored["draft"]["pages"][1]["text"] == "恢复识别的正文"
    process_job(store, job["jobId"])
    assert c.get(path, headers=ph).json() == restored


def test_extract_rotation_and_image_failure_are_page_scoped(tmp_path, monkeypatch):
    path = tmp_path / "page.png"
    path.write_bytes(png())

    def recognize(data):
        assert Image.open(io.BytesIO(data)).size == (40, 20)
        return {"text": "单页正文", "blocks": [{"confidence": 0.5}]}

    monkeypatch.setattr("robot_service.extract.ocr", recognize)
    result = extract(path, ".png", 0, 90)[0]
    assert result["qualityWarnings"] == ["LOW_CONFIDENCE"]

    def error(data):
        raise TimeoutError("private path and text")

    monkeypatch.setattr("robot_service.extract.ocr", error)
    result = extract(path, ".png")[0]
    assert result["extractionError"] == "TimeoutError"
    assert "private" not in json.dumps(result)
    with pytest.raises(ValueError):
        extract(path, ".png", 1)


def test_directory_import_natural_order_drafts_only_and_duplicates(tmp_path):
    store = Store(tmp_path)
    folder = tmp_path / "inbox" / "小书"
    folder.mkdir(parents=True)
    (folder / "10.txt").write_text("相同文字")
    (folder / "2.txt").write_text("第二页")
    (folder / "11.txt").write_text("相同文字")
    (folder / ".DS_Store").write_bytes(b"finder metadata")
    result = import_directory(store, "小书")
    row = store.one("SELECT * FROM resources WHERE id=?", (result["resourceId"],))
    assert row["status"] == "draft" and not row["published_id"]
    draft = json.loads(row["draft"])
    assert [p["text"] for p in draft["pages"]] == ["第二页", "相同文字", "相同文字"]
    assert all(not p["reviewed"] for p in draft["pages"])
    assert "DUPLICATE" in draft["pages"][1]["qualityWarnings"]
    assert len(list(folder.iterdir())) == 4
    for invalid in ("../outside", str(folder), "."):
        with pytest.raises(ValueError):
            import_directory(store, invalid)
    (folder / "link.txt").symlink_to(folder / "2.txt")
    with pytest.raises(ValueError):
        import_directory(store, "小书")


def test_pdf_partial_failure_keeps_page_order_and_single_page_retry(
    tmp_path, monkeypatch
):
    from pypdf import PdfWriter

    path = tmp_path / "book.pdf"
    writer = PdfWriter()
    for _ in range(3):
        writer.add_blank_page(width=80, height=100)
    with path.open("wb") as handle:
        writer.write(handle)
    calls = []

    def recognize(data):
        calls.append(len(calls))
        if len(calls) == 2:
            raise TimeoutError()
        return {"text": "识别正文" + str(len(calls)), "blocks": []}

    monkeypatch.setattr("robot_service.extract.ocr", recognize)
    pages = extract(path, ".pdf")
    assert [p["sourcePage"] for p in pages] == [0, 1, 2]
    assert pages[0]["text"] and pages[2]["text"]
    assert pages[1]["text"] == "" and pages[1]["extractionError"] == "TimeoutError"
    calls.clear()
    retried = extract(path, ".pdf", source_page=1)
    assert len(calls) == 1 and len(retried) == 1 and retried[0]["sourcePage"] == 1


def test_batch_timeout_preserves_completed_pages_and_pending_positions(
    system, monkeypatch
):
    import subprocess

    c, store, _, _, _, ph = system
    book = new_book(c, ph)
    job = ingest_asset(store, book["id"], "pages.txt", b"one\ftwo\fthree", "pages", 1)
    completed = {"sourcePage": 0, "text": "第一张已完成"}
    output = (
        json.dumps({"sources": [0, 1, 2]})
        + "\n"
        + json.dumps({"page": completed})
        + '\n{"page":'
    ).encode()

    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired("extract", 180, output=output)

    monkeypatch.setattr("robot_service.imports.subprocess.run", timeout)
    process_job(store, job["jobId"])
    draft = c.get("/v1/resources/" + book["id"], headers=ph).json()["draft"]
    assert len(draft["pages"]) == 4
    assert draft["pages"][0]["reviewed"] and draft["pages"][0]["text"] == "已校对内容"
    assert draft["pages"][1]["text"] == "第一张已完成"
    assert [p["sourcePage"] for p in draft["pages"][1:]] == [0, 1, 2]
    assert all(p["extractionError"] == "BatchTimeout" for p in draft["pages"][2:])
    result = store.one("SELECT * FROM jobs WHERE id=?", (job["jobId"],))
    assert (
        result["state"] == "needs_review"
        and json.loads(result["result"])["failedPages"] == 2
    )

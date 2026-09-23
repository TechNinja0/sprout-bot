"""有界离线素材解析；可在隔离子进程调用。"""

import io
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = 25_000_000


def ocr(data: bytes):
    image = Image.open(io.BytesIO(data))
    if image.width * image.height > 25_000_000:
        raise ValueError("图片超过2500万像素")
    image.load()
    image = ImageOps.exif_transpose(image).convert("RGB")
    image.thumbnail((2200, 2200))
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    data = buf.getvalue()
    helper = os.environ.get(
        "ROBOT_OCR_BINARY", str(Path(__file__).resolve().parents[1] / "native/ocr")
    )
    if Path(helper).is_file():
        p = subprocess.run(
            [helper], input=data, capture_output=True, timeout=25, check=True
        )
        return json.loads(p.stdout)
    if shutil.which("tesseract"):
        p = subprocess.run(
            ["tesseract", "stdin", "stdout", "-l", "chi_sim+eng"],
            input=data,
            capture_output=True,
            timeout=25,
            check=True,
        )
        return {"text": p.stdout.decode(), "blocks": []}
    raise RuntimeError(
        "OCR未安装：macOS编译native/ocr.swift，Linux安装Tesseract及chi_sim/eng"
    )


def image_ocr(data, rotation=0):
    if rotation:
        with Image.open(io.BytesIO(data)) as original:
            image = ImageOps.exif_transpose(original).rotate(-rotation, expand=True)
            buffer = io.BytesIO()
            image.save(buffer, format="PNG")
            data = buffer.getvalue()
    return ocr(data)


def page_result(text, index, blocks=()):
    if len(text) > 20000:
        raise ValueError("单页文字超限")
    confidences = [
        b["confidence"]
        for b in blocks
        if isinstance(b.get("confidence"), (int, float)) and 0 <= b["confidence"] <= 1
    ]
    confidence = min(confidences) if confidences else None
    warnings = [] if text.strip() else ["BLANK"]
    if confidence is not None and confidence < 0.75:
        warnings.append("LOW_CONFIDENCE")
    return {
        "text": text,
        "sourcePage": index,
        "confidence": confidence,
        "qualityWarnings": warnings,
        "extractionError": "",
    }


def failed_page(index, exc):
    return {
        "text": "",
        "sourcePage": index,
        "confidence": None,
        "qualityWarnings": ["OCR_FAILED"],
        "extractionError": type(exc).__name__,
    }


def extract(
    path: Path,
    extension: str,
    source_page=None,
    rotation=0,
    on_start=None,
    on_page=None,
):
    def emit(page):
        if on_page:
            on_page(page)
        return page

    def begin(indices):
        if on_start:
            on_start(list(indices))

    if rotation not in (0, 90, 180, 270) or (
        source_page is not None and source_page < 0
    ):
        raise ValueError("页码或旋转角度错误")
    if extension in (".txt", ".md"):
        if rotation:
            raise ValueError("文本不可旋转")
        text = path.read_text(encoding="utf-8-sig")
        if len(text) > 500000:
            raise ValueError("文字超限")
        pages = []
        for part in text.split("\f"):
            for start in range(0, max(1, len(part)), 20000):
                pages.append(page_result(part[start : start + 20000], len(pages)))
        if source_page is not None:
            pages = [pages[source_page]]
        begin([p["sourcePage"] for p in pages])
        for page in pages:
            emit(page)
        return pages
    if extension == ".pdf":
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(path)
        try:
            if len(pdf) > 300:
                raise ValueError("PDF超过300页")
            indices = range(len(pdf)) if source_page is None else [source_page]
            if source_page is not None and source_page >= len(pdf):
                raise ValueError("页不存在")
            pages = []
            begin(indices)
            for i in indices:
                page = None
                try:
                    page = pdf[i]
                    tp = page.get_textpage()
                    try:
                        text = tp.get_text_range()
                    finally:
                        tp.close()
                    blocks = []
                    if rotation or len(text.strip()) < 2 or "\ufffd" in text:
                        bitmap = page.render(scale=min(2, 2200 / max(page.get_size())))
                        try:
                            buffer = io.BytesIO()
                            bitmap.to_pil().save(buffer, format="PNG")
                            result = image_ocr(buffer.getvalue(), rotation)
                            text, blocks = result["text"], result.get("blocks", [])
                        finally:
                            bitmap.close()
                    pages.append(emit(page_result(text, i, blocks)))
                except Exception as exc:
                    # 保留原页顺序和失败占位；一页失败不丢掉其他已识别页面。
                    pages.append(emit(failed_page(i, exc)))
                finally:
                    if page is not None:
                        page.close()
            return pages
        finally:
            pdf.close()
    if extension in (".png", ".jpg", ".jpeg", ".webp"):
        if source_page not in (None, 0):
            raise ValueError("图片只有一页")
        begin([0])
        try:
            result = image_ocr(path.read_bytes(), rotation)
            return [emit(page_result(result["text"], 0, result.get("blocks", [])))]
        except Exception as exc:
            return [emit(failed_page(0, exc))]
    raise ValueError("不支持的文字素材格式")


if __name__ == "__main__":
    try:
        options = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}

        def output(value):
            sys.stdout.write(json.dumps(value, ensure_ascii=False) + "\n")
            sys.stdout.flush()

        streaming = options.get("stream", False)
        value = extract(
            Path(sys.argv[1]),
            sys.argv[2],
            options.get("sourcePage"),
            options.get("rotation", 0),
            (lambda sources: output({"sources": sources})) if streaming else None,
            (lambda page: output({"page": page})) if streaming else None,
        )
        if streaming:
            output({"done": True})
        else:
            sys.stdout.write(json.dumps(value, ensure_ascii=False))
    except Exception as exc:
        sys.stderr.write(type(exc).__name__)
        sys.exit(2)

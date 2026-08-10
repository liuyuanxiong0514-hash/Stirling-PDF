from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .table_structure import OcrBox, boxes_to_table, estimate_quality


NO_TABLE_MESSAGE = (
    "\u672a\u8bc6\u522b\u5230\u660e\u663e\u8868\u683c\uff0c"
    "\u8bf7\u4e0a\u4f20\u66f4\u6e05\u6670\u3001\u65e0\u503e\u659c\u3001"
    "\u65e0\u906e\u6321\u7684\u56fe\u7247\u6216\u626b\u63cf\u4ef6\u3002"
)


def extract_image_table(image_path: Path) -> dict[str, Any]:
    processed_path = preprocess_image(image_path)
    ocr = get_ocr()
    raw_result = ocr.predict(str(processed_path))
    boxes = parse_paddle_result(raw_result)
    table = boxes_to_table(boxes)
    quality = estimate_quality(boxes, table)

    if not table:
        return {
            "success": False,
            "tables": [],
            "message": NO_TABLE_MESSAGE,
            "quality": quality,
        }

    return {
        "success": True,
        "tables": [table],
        "message": "\u56fe\u7247\u8868\u683c\u8bc6\u522b\u5b8c\u6210",
        "quality": quality,
    }


def extract_image_text(image_path: Path) -> dict[str, Any]:
    processed_path = preprocess_image(image_path)
    ocr = get_ocr()
    raw_result = ocr.predict(str(processed_path))
    boxes = parse_paddle_result(raw_result)
    table = boxes_to_table(boxes)
    quality = estimate_quality(boxes, table)
    sorted_boxes = sorted(boxes, key=lambda box: (box.y1, box.x1))
    text = "\n".join(box.text for box in sorted_boxes if box.text.strip())

    if not text.strip():
        return {
            "success": False,
            "text": "",
            "blocks": [],
            "message": "\u672a\u8bc6\u522b\u5230\u6709\u6548\u6587\u5b57\uff0c\u8bf7\u4e0a\u4f20\u66f4\u6e05\u6670\u7684\u56fe\u7247\u3002",
            "quality": quality,
        }

    return {
        "success": True,
        "text": text,
        "blocks": [
            {
                "text": box.text,
                "x": round(box.x1, 2),
                "y": round(box.y1, 2),
                "width": round(box.x2 - box.x1, 2),
                "height": round(box.y2 - box.y1, 2),
            }
            for box in sorted_boxes
        ],
        "message": "\u56fe\u7247\u6587\u5b57\u8bc6\u522b\u5b8c\u6210",
        "quality": quality,
    }


def preprocess_image(image_path: Path) -> Path:
    image = cv2.imdecode(np.fromfile(str(image_path), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return image_path

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    denoised = cv2.fastNlMeansDenoising(gray, h=10)
    binary = cv2.adaptiveThreshold(
        denoised,
        255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        31,
        9,
    )

    output_path = image_path.with_name(image_path.stem + "-processed.png")
    cv2.imencode(".png", binary)[1].tofile(str(output_path))
    return output_path


@lru_cache(maxsize=1)
def get_ocr():
    try:
        from paddleocr import PaddleOCR
    except Exception as exc:  # pragma: no cover - depends on runtime install
        raise RuntimeError("PaddleOCR is not installed or failed to load") from exc

    return PaddleOCR(
        ocr_version="PP-OCRv4",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        lang="ch",
    )


def parse_paddle_result(raw_result: Any) -> list[OcrBox]:
    boxes: list[OcrBox] = []
    if not raw_result:
        return boxes

    for page in raw_result:
        if not page:
            continue
        if isinstance(page, dict):
            boxes.extend(parse_paddle_v3_page(page))
            continue
        boxes.extend(parse_paddle_v2_page(page))
    return boxes


def parse_paddle_v2_page(page: Any) -> list[OcrBox]:
    boxes: list[OcrBox] = []
    for item in page:
        if len(item) < 2:
            continue
        points = item[0]
        text_info = item[1]
        if not points or not text_info:
            continue

        text = str(text_info[0]).strip()
        confidence = float(text_info[1]) if len(text_info) > 1 else 0.0
        boxes.append(to_ocr_box(text, confidence, points))
    return boxes


def parse_paddle_v3_page(page: dict[str, Any]) -> list[OcrBox]:
    texts = page.get("rec_texts") or []
    scores = page.get("rec_scores") or []
    polys = page.get("rec_polys") or page.get("dt_polys") or []
    boxes: list[OcrBox] = []

    for index, text in enumerate(texts):
        if not str(text).strip() or index >= len(polys):
            continue
        confidence = float(scores[index]) if index < len(scores) else 0.0
        boxes.append(to_ocr_box(str(text).strip(), confidence, polys[index]))

    return boxes


def to_ocr_box(text: str, confidence: float, points: Any) -> OcrBox:
    xs = [float(point[0]) for point in points]
    ys = [float(point[1]) for point in points]
    return OcrBox(
        text=text,
        x1=min(xs),
        y1=min(ys),
        x2=max(xs),
        y2=max(ys),
        confidence=confidence,
    )

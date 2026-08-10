from __future__ import annotations

from fastapi import FastAPI, File, UploadFile

from .image_table_extract import extract_image_table, extract_image_text
from .pdf_scan_extract import extract_scan_pdf_tables
from .utils import (
    ALLOWED_IMAGE_SUFFIXES,
    ALLOWED_PDF_SUFFIXES,
    cleanup_paths,
    file_suffix,
    save_upload_to_temp,
)


app = FastAPI(title="Stirling-PDF OCR Service")

INSTALL_ERROR_MESSAGE = (
    "\u004f\u0043\u0052\u8bc6\u522b\u670d\u52a1\u6682\u672a\u6b63\u786e\u5b89\u88c5\uff0c"
    "\u8bf7\u5b89\u88c5 PaddleOCR/PaddlePaddle \u540e\u91cd\u8bd5\u3002"
)
NO_TABLE_MESSAGE = (
    "\u672a\u8bc6\u522b\u5230\u660e\u663e\u8868\u683c\uff0c"
    "\u8bf7\u4e0a\u4f20\u66f4\u6e05\u6670\u3001\u65e0\u503e\u659c\u3001"
    "\u65e0\u906e\u6321\u7684\u56fe\u7247\u6216\u626b\u63cf\u4ef6\u3002"
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/extract-image-table")
def extract_image_table_endpoint(file: UploadFile = File(...)):
    suffix = file_suffix(file.filename, ".png")
    if suffix not in ALLOWED_IMAGE_SUFFIXES:
        return {
            "success": False,
            "tables": [],
            "message": "\u4ec5\u652f\u6301 PNG\u3001JPG\u3001JPEG \u56fe\u7247",
            "quality": "\u8f83\u5dee",
        }

    temp_path = save_upload_to_temp(file.file, suffix)
    try:
        return extract_image_table(temp_path)
    except RuntimeError:
        return {
            "success": False,
            "tables": [],
            "message": INSTALL_ERROR_MESSAGE,
            "quality": "\u8f83\u5dee",
        }
    except Exception:
        return {
            "success": False,
            "tables": [],
            "message": NO_TABLE_MESSAGE,
            "quality": "\u8f83\u5dee",
        }
    finally:
        cleanup_paths([temp_path, temp_path.with_name(temp_path.stem + "-processed.png")])


@app.post("/extract-image-text")
def extract_image_text_endpoint(file: UploadFile = File(...)):
    suffix = file_suffix(file.filename, ".png")
    if suffix not in ALLOWED_IMAGE_SUFFIXES:
        return {
            "success": False,
            "text": "",
            "blocks": [],
            "message": "\u4ec5\u652f\u6301 PNG\u3001JPG\u3001JPEG\u3001WEBP \u56fe\u7247",
            "quality": "\u8f83\u5dee",
        }

    temp_path = save_upload_to_temp(file.file, suffix)
    try:
        return extract_image_text(temp_path)
    except RuntimeError:
        return {
            "success": False,
            "text": "",
            "blocks": [],
            "message": INSTALL_ERROR_MESSAGE,
            "quality": "\u8f83\u5dee",
        }
    except Exception:
        return {
            "success": False,
            "text": "",
            "blocks": [],
            "message": "\u672a\u8bc6\u522b\u5230\u6709\u6548\u6587\u5b57\uff0c\u8bf7\u4e0a\u4f20\u66f4\u6e05\u6670\u7684\u56fe\u7247\u3002",
            "quality": "\u8f83\u5dee",
        }
    finally:
        cleanup_paths([temp_path, temp_path.with_name(temp_path.stem + "-processed.png")])


@app.post("/extract-scan-pdf-table")
def extract_scan_pdf_table_endpoint(file: UploadFile = File(...)):
    suffix = file_suffix(file.filename, ".pdf")
    if suffix not in ALLOWED_PDF_SUFFIXES:
        return {
            "success": False,
            "pages": [],
            "message": "\u4ec5\u652f\u6301 PDF \u6587\u4ef6",
            "quality": "\u8f83\u5dee",
        }

    temp_path = save_upload_to_temp(file.file, suffix)
    try:
        return extract_scan_pdf_tables(temp_path)
    except RuntimeError:
        return {
            "success": False,
            "pages": [],
            "message": INSTALL_ERROR_MESSAGE,
            "quality": "\u8f83\u5dee",
        }
    except Exception:
        return {
            "success": False,
            "pages": [],
            "message": NO_TABLE_MESSAGE,
            "quality": "\u8f83\u5dee",
        }
    finally:
        cleanup_paths([temp_path])

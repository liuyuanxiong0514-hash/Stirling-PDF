from __future__ import annotations

import os
import tempfile
from pathlib import Path
from typing import BinaryIO


ALLOWED_IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}
ALLOWED_PDF_SUFFIXES = {".pdf"}


def save_upload_to_temp(file: BinaryIO, suffix: str) -> Path:
    suffix = suffix.lower()
    fd, path = tempfile.mkstemp(prefix="stirling-ocr-", suffix=suffix)
    with os.fdopen(fd, "wb") as out:
        while chunk := file.read(1024 * 1024):
            out.write(chunk)
    return Path(path)


def file_suffix(filename: str | None, fallback: str = "") -> str:
    if not filename:
        return fallback
    return Path(filename).suffix.lower() or fallback


def cleanup_paths(paths: list[Path]) -> None:
    for path in paths:
        try:
            if path.exists():
                path.unlink()
        except OSError:
            pass

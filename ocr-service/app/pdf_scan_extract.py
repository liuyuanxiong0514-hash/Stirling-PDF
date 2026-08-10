from __future__ import annotations

from pathlib import Path
from typing import Any

from .image_table_extract import NO_TABLE_MESSAGE, extract_image_table


PDF_CONVERT_ERROR = (
    "\u626b\u63cf\u7248 PDF \u8f6c\u56fe\u7247\u5931\u8d25\uff0c"
    "\u8bf7\u786e\u8ba4\u5df2\u5b89\u88c5 Poppler\u3002"
)


def extract_scan_pdf_tables(pdf_path: Path) -> dict[str, Any]:
    try:
        images = pdf_to_images(pdf_path)
    except Exception:
        return {
            "success": False,
            "pages": [],
            "message": PDF_CONVERT_ERROR,
            "quality": "\u8f83\u5dee",
        }

    pages = []
    qualities: list[str] = []
    temp_images: list[Path] = []
    try:
        for index, image in enumerate(images, start=1):
            image_path = pdf_path.with_name(f"{pdf_path.stem}-page-{index}.png")
            image.save(image_path)
            temp_images.append(image_path)

            result = extract_image_table(image_path)
            qualities.append(result.get("quality", "\u4e00\u822c"))
            if result.get("success"):
                pages.append({"page": index, "tables": result.get("tables", [])})
    finally:
        for image_path in temp_images:
            try:
                image_path.unlink()
            except OSError:
                pass

    if not pages:
        return {
            "success": False,
            "pages": [],
            "message": NO_TABLE_MESSAGE,
            "quality": worst_quality(qualities),
        }

    return {
        "success": True,
        "pages": pages,
        "message": "\u626b\u63cf\u7248 PDF \u8868\u683c\u8bc6\u522b\u5b8c\u6210",
        "quality": worst_quality(qualities),
    }


def worst_quality(qualities: list[str]) -> str:
    order = {"\u8f83\u597d": 3, "\u4e00\u822c": 2, "\u8f83\u5dee": 1}
    if not qualities:
        return "\u4e00\u822c"
    return min(qualities, key=lambda item: order.get(item, 2))


def pdf_to_images(pdf_path: Path):
    try:
        from pdf2image import convert_from_path

        return convert_from_path(str(pdf_path), dpi=220)
    except Exception:
        return pdf_to_images_with_pdfium(pdf_path)


def pdf_to_images_with_pdfium(pdf_path: Path):
    import pypdfium2 as pdfium
    from PIL import Image

    pdf = pdfium.PdfDocument(str(pdf_path))
    images: list[Image.Image] = []
    scale = 220 / 72
    try:
        for index in range(len(pdf)):
            page = pdf[index]
            bitmap = page.render(scale=scale)
            images.append(bitmap.to_pil())
    finally:
        pdf.close()
    return images

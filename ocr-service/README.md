# Stirling-PDF OCR Service

Python OCR microservice for extracting simple table data from screenshots, images, and scanned PDFs.
It also exposes plain image text extraction for the Java image information extraction workflow.

## Install

Create a virtual environment first:

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

If `paddlepaddle` installation fails, install the CPU package from PaddlePaddle's official index for your Python version and platform, then rerun the remaining requirements:

```bash
python -m pip install paddlepaddle
python -m pip install paddleocr fastapi uvicorn[standard] opencv-python pillow numpy pandas openpyxl pdf2image python-multipart
```

`pdf2image` also requires Poppler. On Windows, install Poppler and add its `bin` directory to `PATH`.

## Run

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8100
```

The Java application expects this default URL:

```text
http://localhost:8100
```

Override it in Java with:

```properties
excel.ocr.service-url=http://localhost:8100
```

## Endpoints

```text
POST /extract-image-table
POST /extract-image-text
POST /extract-scan-pdf-table
GET /health
```

`POST /extract-image-text` returns full OCR text and positioned text blocks:

```json
{
  "success": true,
  "text": "recognized text",
  "blocks": [
    {
      "text": "姓名 张三",
      "x": 10,
      "y": 20,
      "width": 100,
      "height": 20
    }
  ],
  "message": "图片文字识别完成",
  "quality": "清晰"
}
```

This stage focuses on common simple tables. Complex merged cells, rotated pages, severe skew, handwritten content, and low-resolution images may require later tuning.

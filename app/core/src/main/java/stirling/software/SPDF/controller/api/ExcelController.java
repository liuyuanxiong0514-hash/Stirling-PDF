package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelExportService;
import stirling.software.SPDF.service.ExcelExportService.ExcelSheet;
import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;
import stirling.software.SPDF.service.OcrTableExtractService;
import stirling.software.SPDF.service.OcrTableExtractService.OcrExtractResult;
import stirling.software.SPDF.service.PdfTableExtractService;
import stirling.software.SPDF.service.PdfTableExtractService.PdfTableExtractResult;

@RestController
@RequestMapping("/api/excel")
@Tag(name = "Excel", description = "Excel utility endpoints")
@RequiredArgsConstructor
public class ExcelController {

    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final long MAX_UPLOAD_BYTES = 50L * 1024L * 1024L;
    private static final String NO_TABLE_MESSAGE =
            "\u672a\u8bc6\u522b\u5230\u660e\u663e\u8868\u683c\uff0c\u8bf7\u4e0a\u4f20\u66f4\u6e05\u6670\u3001\u65e0\u503e\u659c\u3001\u65e0\u906e\u6321\u7684\u56fe\u7247\u6216\u626b\u63cf\u4ef6\u3002";

    private final ExcelExportService excelExportService;
    private final PdfTableExtractService pdfTableExtractService;
    private final OcrTableExtractService ocrTableExtractService;

    @PostMapping(value = "/extract-table", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Extract PDF or OCR image tables and export them to Excel")
    public ExtractTableResponse extractTable(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return ExtractTableResponse.failure(
                    "\u8bf7\u5148\u9009\u62e9\u9700\u8981\u8bc6\u522b\u7684\u6587\u4ef6");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ExtractTableResponse.failure(
                    "\u6587\u4ef6\u8fc7\u5927\uff0c\u8bf7\u4e0a\u4f20 50MB \u4ee5\u5185\u7684\u6587\u4ef6");
        }
        if (!isPdf(file) && !isImage(file)) {
            return ExtractTableResponse.failure(
                    "\u8bf7\u4e0a\u4f20 PNG\u3001JPG\u3001JPEG \u6216 PDF \u6587\u4ef6");
        }

        if (isPdf(file)) {
            PdfTableExtractResult result = pdfTableExtractService.extractTables(file);
            if (result.success()) {
                ExportedExcel exportedExcel =
                        excelExportService.exportSheets(result.sheets(), "pdf_table_extract.xlsx");
                return ExtractTableResponse.success(
                        result.message(),
                        "\u8f83\u597d",
                        firstPreviewRows(result.sheets()),
                        exportedExcel);
            }

            OcrExtractResult ocrResult = ocrTableExtractService.extractScanPdfTable(file);
            return toResponse(ocrResult, "ocr_table_extract.xlsx");
        }

        if (isImage(file)) {
            OcrExtractResult ocrResult = ocrTableExtractService.extractImageTable(file);
            return toResponse(ocrResult, "ocr_table_extract.xlsx");
        }

        return ExtractTableResponse.failure(NO_TABLE_MESSAGE);
    }

    @GetMapping("/download/{fileName}")
    @Operation(summary = "Download generated Excel export")
    public ResponseEntity<Resource> downloadExcel(@PathVariable String fileName)
            throws IOException {
        Resource resource = excelExportService.getExportedFile(fileName);
        if (resource == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            new ByteArrayResource(
                                    "\u0045\u0078\u0063\u0065\u006c\u6587\u4ef6\u4e0d\u5b58\u5728\u6216\u5df2\u8fc7\u671f"
                                            .getBytes(StandardCharsets.UTF_8)));
        }

        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        excelExportService.getDisplayFileName(fileName),
                                        StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    private boolean isPdf(MultipartFile file) {
        return hasContentType(file, "application/pdf") || hasExtension(file, ".pdf");
    }

    private boolean isImage(MultipartFile file) {
        return hasContentType(file, "image/png")
                || hasContentType(file, "image/jpeg")
                || hasExtension(file, ".png")
                || hasExtension(file, ".jpg")
                || hasExtension(file, ".jpeg");
    }

    private boolean hasContentType(MultipartFile file, String contentType) {
        return contentType.equalsIgnoreCase(file.getContentType());
    }

    private boolean hasExtension(MultipartFile file, String extension) {
        String fileName = file.getOriginalFilename();
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(extension);
    }

    private List<List<String>> firstPreviewRows(List<ExcelSheet> sheets) {
        return sheets.stream()
                .map(ExcelSheet::rows)
                .filter(rows -> !rows.isEmpty())
                .findFirst()
                .orElse(List.of());
    }

    private ExtractTableResponse toResponse(OcrExtractResult result, String displayFileName)
            throws IOException {
        if (!result.success() || result.sheets().isEmpty()) {
            return ExtractTableResponse.failure(
                    result.message() == null || result.message().isBlank()
                            ? NO_TABLE_MESSAGE
                            : result.message(),
                    result.quality());
        }

        ExportedExcel exportedExcel =
                excelExportService.exportSheets(result.sheets(), displayFileName);
        return ExtractTableResponse.success(
                result.message(),
                result.quality(),
                firstPreviewRows(result.sheets()),
                exportedExcel);
    }

    public record ExtractTableResponse(
            boolean success,
            String message,
            List<List<String>> previewData,
            String downloadUrl,
            String fileName,
            String quality) {

        public static ExtractTableResponse success(
                String message,
                String quality,
                List<List<String>> previewData,
                ExportedExcel exportedExcel) {
            return new ExtractTableResponse(
                    true,
                    message,
                    previewData,
                    "/api/excel/download/"
                            + URLEncoder.encode(
                                    exportedExcel.storageFileName(), StandardCharsets.UTF_8),
                    exportedExcel.displayFileName(),
                    quality);
        }

        public static ExtractTableResponse failure(String message) {
            return failure(message, null);
        }

        public static ExtractTableResponse failure(String message, String quality) {
            return new ExtractTableResponse(false, message, List.of(), null, null, quality);
        }
    }
}

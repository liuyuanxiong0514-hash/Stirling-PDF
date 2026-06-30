package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;

@RestController
@RequestMapping("/api/excel")
@Tag(name = "Excel", description = "Excel utility endpoints")
@RequiredArgsConstructor
public class ExcelController {

    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExcelExportService excelExportService;

    @PostMapping(value = "/extract-table", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Generate an Excel file from mock table extraction data")
    public ExtractTableResponse extractTable(@RequestParam("file") MultipartFile file)
            throws IOException {
        List<List<String>> previewData =
                List.of(
                        List.of("姓名", "金额", "备注"),
                        List.of("张三", "100", "示例数据"),
                        List.of("李四", "200", "示例数据"));
        ExportedExcel exportedExcel = excelExportService.exportTable(previewData);

        return new ExtractTableResponse(
                true,
                "Excel文件已生成，下一阶段将接入PDF表格提取和OCR识别",
                previewData,
                "/api/excel/download/"
                        + URLEncoder.encode(
                                exportedExcel.storageFileName(), StandardCharsets.UTF_8),
                exportedExcel.displayFileName());
    }

    @GetMapping("/download/{fileName}")
    @Operation(summary = "Download generated Excel export")
    public ResponseEntity<Resource> downloadExcel(@PathVariable String fileName)
            throws IOException {
        Resource resource = excelExportService.getExportedFile(fileName);
        if (resource == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(new ByteArrayResource("Excel文件不存在或已过期".getBytes(StandardCharsets.UTF_8)));
        }

        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("table_extract_result.xlsx", StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    public record ExtractTableResponse(
            boolean success,
            String message,
            List<List<String>> previewData,
            String downloadUrl,
            String fileName) {}
}

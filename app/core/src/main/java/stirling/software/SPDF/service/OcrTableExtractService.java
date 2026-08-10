package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import stirling.software.SPDF.service.ExcelExportService.ExcelSheet;

import tools.jackson.databind.ObjectMapper;

@Service
public class OcrTableExtractService {

    private static final String OCR_SERVICE_NOT_STARTED_MESSAGE =
            "\u004f\u0043\u0052\u8bc6\u522b\u670d\u52a1\u6682\u672a\u542f\u52a8\uff0c\u8bf7\u542f\u52a8 ocr-service \u540e\u91cd\u8bd5\u3002";
    private static final String NO_TABLE_MESSAGE =
            "\u672a\u8bc6\u522b\u5230\u660e\u663e\u8868\u683c\uff0c\u8bf7\u4e0a\u4f20\u66f4\u6e05\u6670\u3001\u65e0\u503e\u659c\u3001\u65e0\u906e\u6321\u7684\u56fe\u7247\u6216\u626b\u63cf\u4ef6\u3002";

    private final String serviceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OcrTableExtractService(
            @Value("${excel.ocr.service-url:http://localhost:8100}") String serviceUrl,
            ObjectMapper objectMapper) {
        this.serviceUrl = serviceUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public OcrExtractResult extractImageTable(MultipartFile file) {
        try {
            OcrImageResponse response =
                    postMultipart("/extract-image-table", file, OcrImageResponse.class);
            if (!response.success() || response.tables() == null || response.tables().isEmpty()) {
                return OcrExtractResult.failure(
                        messageOrDefault(response.message()), response.quality());
            }
            return OcrExtractResult.success(
                    response.message(), response.quality(), toSheets(response.tables(), "Sheet1"));
        } catch (Exception e) {
            return OcrExtractResult.failure(OCR_SERVICE_NOT_STARTED_MESSAGE, "较差");
        }
    }

    public OcrExtractResult extractScanPdfTable(MultipartFile file) {
        try {
            OcrPdfResponse response =
                    postMultipart("/extract-scan-pdf-table", file, OcrPdfResponse.class);
            if (!response.success() || response.pages() == null || response.pages().isEmpty()) {
                return OcrExtractResult.failure(
                        messageOrDefault(response.message()), response.quality());
            }

            List<ExcelSheet> sheets = new ArrayList<>();
            for (OcrPage page : response.pages()) {
                List<List<List<String>>> tables = page.tables();
                if (tables == null || tables.isEmpty()) {
                    continue;
                }
                sheets.add(new ExcelSheet("Page" + page.page(), flattenTables(tables)));
            }

            if (sheets.isEmpty()) {
                return OcrExtractResult.failure(
                        messageOrDefault(response.message()), response.quality());
            }

            return OcrExtractResult.success(response.message(), response.quality(), sheets);
        } catch (Exception e) {
            return OcrExtractResult.failure(OCR_SERVICE_NOT_STARTED_MESSAGE, "较差");
        }
    }

    private <T> T postMultipart(String path, MultipartFile file, Class<T> responseType)
            throws IOException, InterruptedException {
        String boundary = "----StirlingPdfOcr" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, file);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(serviceUrl + path))
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OCR service returned HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), responseType);
    }

    private byte[] multipartBody(String boundary, MultipartFile file) throws IOException {
        String fileName =
                file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        fileName = fileName.replace("\\", "_").replace("/", "_").replace("\"", "_");
        String contentType =
                file.getContentType() == null
                        ? "application/octet-stream"
                        : file.getContentType().toLowerCase(Locale.ROOT);
        String header =
                "--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + fileName
                        + "\"\r\n"
                        + "Content-Type: "
                        + contentType
                        + "\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] fileBytes = file.getBytes();
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(
                footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);
        return body;
    }

    private List<ExcelSheet> toSheets(List<List<List<String>>> tables, String sheetName) {
        return List.of(new ExcelSheet(sheetName, flattenTables(tables)));
    }

    private List<List<String>> flattenTables(List<List<List<String>>> tables) {
        List<List<String>> rows = new ArrayList<>();
        for (List<List<String>> table : tables) {
            if (table == null || table.isEmpty()) {
                continue;
            }
            if (!rows.isEmpty()) {
                rows.add(List.of());
            }
            rows.addAll(table);
        }
        return rows;
    }

    private String messageOrDefault(String message) {
        return message == null || message.isBlank() ? NO_TABLE_MESSAGE : message;
    }

    public record OcrExtractResult(
            boolean success, String message, String quality, List<ExcelSheet> sheets) {

        public static OcrExtractResult success(
                String message, String quality, List<ExcelSheet> sheets) {
            return new OcrExtractResult(true, message, quality, sheets);
        }

        public static OcrExtractResult failure(String message, String quality) {
            return new OcrExtractResult(false, message, quality, List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OcrImageResponse(
            boolean success, String message, String quality, List<List<List<String>>> tables) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OcrPdfResponse(
            boolean success, String message, String quality, List<OcrPage> pages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OcrPage(int page, List<List<List<String>>> tables) {}
}

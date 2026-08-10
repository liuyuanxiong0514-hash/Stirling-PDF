package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.service.ExcelExportService.ExcelSheet;
import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ImageInfoAiExtractService {

    private static final int MAX_FILES = 10;
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_COLUMNS = 50;
    private static final int MAX_ROWS = 5000;
    private static final int MAX_TEXT_CHARS_PER_IMAGE = 6000;
    private static final Set<String> ALLOWED_TYPES =
            Set.of(
                    "auto",
                    "table",
                    "invoice",
                    "id_card",
                    "business_license",
                    "express",
                    "contract",
                    "custom");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".webp");
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");

    private final String ocrServiceUrl;
    private final ExcelAiConfigurationService aiConfigurationService;
    private final ExcelExportService excelExportService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ImageInfoAiExtractService(
            @Value("${excel.ocr.service-url:http://localhost:8100}") String ocrServiceUrl,
            ExcelAiConfigurationService aiConfigurationService,
            ExcelExportService excelExportService,
            ObjectMapper objectMapper) {
        this.ocrServiceUrl = normalizeUrl(ocrServiceUrl);
        this.aiConfigurationService = aiConfigurationService;
        this.excelExportService = excelExportService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public ImageInfoExtractResult extract(
            MultipartFile[] files, String extractType, String customFields) throws IOException {
        validateFiles(files);
        String normalizedType = normalizeExtractType(extractType);
        List<String> parsedCustomFields = parseCustomFields(customFields);
        if ("custom".equals(normalizedType) && parsedCustomFields.isEmpty()) {
            return ImageInfoExtractResult.failure("请输入要提取的自定义字段。");
        }
        if (!aiConfigurationService.current().available()) {
            return ImageInfoExtractResult.failure("AI服务未启用，请配置 AI 服务；也可以只使用OCR文本查看功能。");
        }

        List<ImageOcrDocument> documents = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (MultipartFile file : files) {
            OcrTextResponse response = extractText(file);
            if (!response.success() || response.text() == null || response.text().isBlank()) {
                warnings.add(
                        safeFileName(file.getOriginalFilename())
                                + "："
                                + messageOrDefault(response.message(), "未识别到有效文字，请上传更清晰的图片。"));
                continue;
            }
            documents.add(
                    new ImageOcrDocument(
                            safeFileName(file.getOriginalFilename()),
                            truncate(response.text(), MAX_TEXT_CHARS_PER_IMAGE),
                            response.blocks() == null
                                    ? List.of()
                                    : response.blocks().stream().limit(80).toList(),
                            response.quality()));
        }

        if (documents.isEmpty()) {
            return ImageInfoExtractResult.failure(
                    warnings.isEmpty() ? "未识别到有效文字，请上传更清晰的图片。" : String.join("；", warnings));
        }

        StructuredExtraction structured;
        try {
            structured =
                    sanitizeStructuredExtraction(
                            callAiExtractor(documents, normalizedType, parsedCustomFields),
                            documents);
        } catch (IOException e) {
            log.warn("Image info AI extraction failed: {}", e.getMessage());
            return ImageInfoExtractResult.failure(
                    "AI提取失败，请检查 AI 服务地址、API Key 或模型名称。", joinedOcrText(documents), warnings);
        }
        if (structured.previewData().size() <= 1) {
            return ImageInfoExtractResult.failure(
                    "提取结果为空，请尝试指定要提取的字段。", structured.ocrText(), warnings);
        }
        warnings.addAll(structured.warnings());
        ExportedExcel exportedExcel =
                excelExportService.exportSheets(
                        List.of(
                                new ExcelSheet(
                                        sheetName(normalizedType), structured.previewData())),
                        "image_info_extract.xlsx");
        return ImageInfoExtractResult.success(
                "图片信息提取完成",
                structured.documentType(),
                structured.ocrText(),
                structured.previewData(),
                exportedExcel,
                warnings);
    }

    private OcrTextResponse extractText(MultipartFile file) throws IOException {
        try {
            return postMultipart("/extract-image-text", file, OcrTextResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OcrTextResponse.failure("OCR识别服务暂未启动，请启动 ocr-service 后重试。");
        } catch (Exception e) {
            log.warn("Image OCR text extraction failed: {}", e.getMessage());
            return OcrTextResponse.failure("OCR识别服务暂未启动，请启动 ocr-service 后重试。");
        }
    }

    private <T> T postMultipart(String path, MultipartFile file, Class<T> responseType)
            throws IOException, InterruptedException {
        String boundary = "----StirlingImageInfo" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, file);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(ocrServiceUrl + path))
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
        String fileName = safeFileName(file.getOriginalFilename());
        String contentType =
                file.getContentType() == null
                        ? "application/octet-stream"
                        : file.getContentType().toLowerCase(Locale.ROOT);
        String header =
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + fileName
                        + "\"\r\nContent-Type: "
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

    private RawAiExtraction callAiExtractor(
            List<ImageOcrDocument> documents, String extractType, List<String> customFields)
            throws IOException {
        var configuration = aiConfigurationService.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configuration.model());
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of(
                                "role",
                                "user",
                                "content",
                                userPrompt(documents, extractType, customFields))));
        body.put("temperature", 0.1);
        ExcelAiRuleParseService.applyProviderCompatibility(body, configuration);

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            ExcelAiRuleParseService.buildChatCompletionsUrl(
                                                    configuration.baseUrl())))
                            .version(HttpClient.Version.HTTP_1_1)
                            .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                            .header("Authorization", "Bearer " + configuration.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(body),
                                            StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("AI service returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IOException("AI service returned empty content");
            }
            String json = extractJsonObject(content.asText());
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return RawAiExtraction.from(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("AI extraction interrupted", e);
        } catch (RuntimeException e) {
            throw new IOException("AI returned invalid JSON", e);
        }
    }

    private String systemPrompt() {
        return String.join(
                "\n",
                "你是一个图片信息结构化提取器。",
                "你的任务是根据 OCR 识别文本，把图片中的信息整理成 JSON。",
                "你不能编造图片中没有的信息。",
                "无法识别的字段填空字符串。",
                "不要输出 Markdown。",
                "不要输出解释。",
                "只输出 JSON。",
                "如果是多张图片，每张图片生成一条记录。",
                "如果图片内容是清单或表格，可以生成多条明细记录。",
                "字段必须稳定。",
                "输出格式：{\"documentType\":\"\",\"columns\":[],\"rows\":[{}],\"warnings\":[]}。");
    }

    private String userPrompt(
            List<ImageOcrDocument> documents, String extractType, List<String> customFields)
            throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("extractType", extractType);
        payload.put("defaultFields", defaultFields(extractType, customFields));
        payload.put("documents", documents);
        payload.put(
                "rules",
                List.of(
                        "只根据 OCR 文本提取信息。",
                        "每行数据必须包含 文件名 字段。",
                        "如果不同图片字段不同，columns 使用字段并集。",
                        "字段最多 50 列，行数最多 5000 行。"));
        return objectMapper.writeValueAsString(payload);
    }

    private StructuredExtraction sanitizeStructuredExtraction(
            RawAiExtraction raw, List<ImageOcrDocument> documents) {
        String documentType = safeCell(raw.documentType());
        List<String> columns = new ArrayList<>();
        for (String column : raw.columns()) {
            String safeColumn = safeHeader(column);
            if (!safeColumn.isBlank() && !columns.contains(safeColumn)) {
                columns.add(safeColumn);
            }
            if (columns.size() >= MAX_COLUMNS) {
                break;
            }
        }

        if (!columns.contains("文件名")) {
            columns.add(0, "文件名");
        }
        for (Map<String, String> row : raw.rows()) {
            for (String key : row.keySet()) {
                String safeKey = safeHeader(key);
                if (!safeKey.isBlank()
                        && !columns.contains(safeKey)
                        && columns.size() < MAX_COLUMNS) {
                    columns.add(safeKey);
                }
            }
        }

        List<List<String>> previewData = new ArrayList<>();
        previewData.add(columns);
        int rowCount = 0;
        for (Map<String, String> row : raw.rows()) {
            if (rowCount >= MAX_ROWS) {
                break;
            }
            Map<String, String> safeRow = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String key = safeHeader(entry.getKey());
                if (!key.isBlank()) {
                    safeRow.put(key, entry.getValue());
                }
            }
            List<String> values = new ArrayList<>();
            for (String column : columns) {
                values.add(safeCell(safeRow.getOrDefault(column, "")));
            }
            if (values.stream().anyMatch(value -> !value.isBlank())) {
                previewData.add(values);
                rowCount++;
            }
        }

        return new StructuredExtraction(
                documentType.isBlank() ? "image_info" : documentType,
                joinedOcrText(documents),
                previewData,
                raw.warnings().stream().map(this::safeCell).filter(w -> !w.isBlank()).toList());
    }

    private void validateFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请先选择需要识别的图片。");
        }
        if (files.length > MAX_FILES) {
            throw new IllegalArgumentException("一次最多上传 10 张图片。");
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("图片文件为空，请重新选择。");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("单张图片不能超过 20MB。");
            }
            if (!isAllowedImage(file)) {
                throw new IllegalArgumentException("请上传 PNG、JPG、JPEG 或 WEBP 图片。");
            }
        }
    }

    private boolean isAllowedImage(MultipartFile file) {
        String contentType =
                file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        return ALLOWED_CONTENT_TYPES.contains(contentType)
                || ALLOWED_EXTENSIONS.contains(extension(file));
    }

    private String normalizeExtractType(String extractType) {
        String normalized =
                extractType == null ? "auto" : extractType.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_TYPES.contains(normalized) ? normalized : "auto";
    }

    private List<String> parseCustomFields(String customFields) {
        if (customFields == null || customFields.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String part : customFields.split("[,，\\n;；]")) {
            String field = safeHeader(part);
            if (!field.isBlank()) {
                fields.add(field);
            }
        }
        return new ArrayList<>(fields);
    }

    private List<String> defaultFields(String extractType, List<String> customFields) {
        return switch (extractType) {
            case "invoice" ->
                    List.of("文件名", "发票号码", "开票日期", "购买方", "销售方", "金额", "税额", "价税合计", "备注");
            case "id_card" -> List.of("文件名", "姓名", "性别", "民族", "出生日期", "身份证号", "地址");
            case "business_license" ->
                    List.of("文件名", "公司名称", "统一社会信用代码", "法定代表人", "注册资本", "成立日期", "注册地址", "经营范围");
            case "express" -> List.of("文件名", "收件人", "电话", "地址", "快递单号", "寄件人", "备注");
            case "contract" -> List.of("文件名", "甲方", "乙方", "合同金额", "签署日期", "合同期限", "项目名称", "关键事项");
            case "custom" -> customFields;
            case "table" -> List.of("文件名", "项目", "规格", "数量", "单价", "金额", "备注");
            default -> List.of("文件名");
        };
    }

    private String extractJsonObject(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed =
                    trimmed.replaceFirst("^```(?:json)?\\s*", "")
                            .replaceFirst("\\s*```$", "")
                            .trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI response is not JSON");
        }
        return trimmed.substring(start, end + 1);
    }

    private String safeHeader(String value) {
        String cleaned = safeCell(value).replaceAll("[<>]", "").trim();
        if (cleaned.toLowerCase(Locale.ROOT).contains("script")) {
            return "";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private String safeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ").trim();
    }

    private String sheetName(String extractType) {
        return "table".equals(extractType) ? "提取明细" : "图片信息提取";
    }

    private String joinedOcrText(List<ImageOcrDocument> documents) {
        StringBuilder builder = new StringBuilder();
        for (ImageOcrDocument document : documents) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("【").append(document.fileName()).append("】\n").append(document.text());
        }
        return builder.toString();
    }

    private String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String extension(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "upload" : fileName;
        return safe.replace("\\", "_").replace("/", "_").replace("\"", "_");
    }

    private static String normalizeUrl(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    public record ImageInfoExtractResult(
            boolean success,
            String message,
            String extractType,
            String ocrText,
            List<List<String>> previewData,
            ExportedExcel exportedExcel,
            List<String> warnings) {

        public static ImageInfoExtractResult success(
                String message,
                String extractType,
                String ocrText,
                List<List<String>> previewData,
                ExportedExcel exportedExcel,
                List<String> warnings) {
            return new ImageInfoExtractResult(
                    true, message, extractType, ocrText, previewData, exportedExcel, warnings);
        }

        public static ImageInfoExtractResult failure(String message) {
            return failure(message, "", List.of());
        }

        public static ImageInfoExtractResult failure(
                String message, String ocrText, List<String> warnings) {
            return new ImageInfoExtractResult(
                    false, message, null, ocrText, List.of(), null, warnings);
        }
    }

    private record ImageOcrDocument(
            String fileName, String text, List<OcrTextBlock> blocks, String quality) {}

    private record StructuredExtraction(
            String documentType,
            String ocrText,
            List<List<String>> previewData,
            List<String> warnings) {}

    private record RawAiExtraction(
            String documentType,
            String ocrText,
            List<String> columns,
            List<Map<String, String>> rows,
            List<String> warnings) {

        @SuppressWarnings("unchecked")
        static RawAiExtraction from(Map<String, Object> raw) {
            String documentType = String.valueOf(raw.getOrDefault("documentType", ""));
            List<String> columns = new ArrayList<>();
            Object rawColumns = raw.get("columns");
            if (rawColumns instanceof List<?> list) {
                for (Object item : list) {
                    columns.add(String.valueOf(item));
                }
            }

            List<Map<String, String>> rows = new ArrayList<>();
            Object rawRows = raw.get("rows");
            if (rawRows instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            row.put(
                                    String.valueOf(entry.getKey()),
                                    entry.getValue() == null
                                            ? ""
                                            : String.valueOf(entry.getValue()));
                        }
                        rows.add(row);
                    }
                }
            }

            List<String> warnings = new ArrayList<>();
            Object rawWarnings = raw.get("warnings");
            if (rawWarnings instanceof List<?> list) {
                for (Object item : list) {
                    warnings.add(String.valueOf(item));
                }
            }
            return new RawAiExtraction(documentType, "", columns, rows, warnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OcrTextResponse(
            boolean success,
            String text,
            List<OcrTextBlock> blocks,
            String message,
            String quality) {

        static OcrTextResponse failure(String message) {
            return new OcrTextResponse(false, "", List.of(), message, "较差");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OcrTextBlock(String text, double x, double y, double width, double height) {}
}

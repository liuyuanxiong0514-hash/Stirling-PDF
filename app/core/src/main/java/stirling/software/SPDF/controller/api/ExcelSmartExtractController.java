package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelAiConfigurationService;
import stirling.software.SPDF.service.ExcelAiConfigurationService.ConnectionTestResult;
import stirling.software.SPDF.service.ExcelAiConfigurationService.PublicAiConfiguration;
import stirling.software.SPDF.service.ExcelAiConfigurationService.UpdateAiConfiguration;
import stirling.software.SPDF.service.ExcelAiRuleParseService;
import stirling.software.SPDF.service.ExcelAiRuleParseService.AiRuleParseRequest;
import stirling.software.SPDF.service.ExcelAiRuleParseService.AiRuleParseResult;
import stirling.software.SPDF.service.ExcelAiRuleParseService.AiStatus;
import stirling.software.SPDF.service.ExcelRequirementParseService;
import stirling.software.SPDF.service.ExcelRequirementParseService.ParseRequirementRequest;
import stirling.software.SPDF.service.ExcelRequirementParseService.ParsedRequirement;
import stirling.software.SPDF.service.ExcelSmartExtractService;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractGenerateResult;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractPreview;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractRequest;

@RestController
@RequestMapping("/api/excel/smart-extract")
@Tag(name = "Excel Smart Extract", description = "Rule-based Excel extraction endpoints")
@RequiredArgsConstructor
public class ExcelSmartExtractController {

    private final ExcelSmartExtractService excelSmartExtractService;
    private final ExcelRequirementParseService excelRequirementParseService;
    private final ExcelAiRuleParseService excelAiRuleParseService;
    private final ExcelAiConfigurationService excelAiConfigurationService;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Preview Excel sheets, headers and data rows")
    public SmartExtractPreviewResponse preview(@RequestParam("file") MultipartFile file)
            throws IOException {
        SmartExtractPreview preview = excelSmartExtractService.preview(file);
        return SmartExtractPreviewResponse.success(preview);
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate a new Excel file from rule-based extraction")
    public SmartExtractGenerateResponse generate(@RequestBody SmartExtractRequest request)
            throws IOException {
        SmartExtractGenerateResult result = excelSmartExtractService.generate(request);
        return SmartExtractGenerateResponse.success(result);
    }

    @PostMapping(value = "/parse-requirement", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Parse a natural language extraction requirement into smart extract rules")
    public ParsedRequirement parseRequirement(@RequestBody ParseRequirementRequest request) {
        return excelRequirementParseService.parse(request);
    }

    @PostMapping(value = "/ai-parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Use AI to parse a natural language extraction requirement into rules")
    public AiRuleParseResult parseRequirementWithAi(@RequestBody AiRuleParseRequest request) {
        return excelAiRuleParseService.parse(request);
    }

    @GetMapping(value = "/ai-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Check AI configuration status for smart Excel extraction")
    public AiStatus aiStatus() {
        return excelAiRuleParseService.status();
    }

    @GetMapping(value = "/ai-config", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read the safe, public AI configuration")
    public PublicAiConfiguration aiConfiguration() {
        return excelAiConfigurationService.publicConfiguration();
    }

    @PostMapping(
            value = "/ai-config",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update the in-memory AI configuration")
    public PublicAiConfiguration updateAiConfiguration(@RequestBody UpdateAiConfiguration request) {
        return excelAiConfigurationService.update(request);
    }

    @PostMapping(value = "/ai-config/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Test the active AI configuration")
    public ConnectionTestResult testAiConfiguration() {
        return excelAiConfigurationService.testConnection();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public SmartExtractErrorResponse handleBadRequest(IllegalArgumentException exception) {
        return SmartExtractErrorResponse.failure(exception.getMessage());
    }

    public record SmartExtractPreviewResponse(
            boolean success,
            String message,
            String fileToken,
            List<String> sheets,
            List<String> headers,
            List<List<String>> previewData,
            Map<String, List<String>> sheetHeaders,
            Map<String, List<List<String>>> sheetPreviews) {

        public static SmartExtractPreviewResponse success(SmartExtractPreview preview) {
            return new SmartExtractPreviewResponse(
                    true,
                    preview.message(),
                    preview.fileToken(),
                    preview.sheets(),
                    preview.headers(),
                    preview.previewData(),
                    preview.sheetHeaders(),
                    preview.sheetPreviews());
        }
    }

    public record SmartExtractGenerateResponse(
            boolean success, String message, String downloadUrl, String fileName, int rowCount) {

        public static SmartExtractGenerateResponse success(SmartExtractGenerateResult result) {
            return new SmartExtractGenerateResponse(
                    true,
                    result.message(),
                    "/api/excel/download/"
                            + URLEncoder.encode(
                                    result.exportedExcel().storageFileName(),
                                    StandardCharsets.UTF_8),
                    result.exportedExcel().displayFileName(),
                    result.rowCount());
        }
    }

    public record SmartExtractErrorResponse(
            boolean success,
            String message,
            String fileToken,
            List<String> sheets,
            List<String> headers,
            List<List<String>> previewData,
            String downloadUrl,
            String fileName,
            int rowCount) {

        public static SmartExtractErrorResponse failure(String message) {
            return new SmartExtractErrorResponse(
                    false, message, null, List.of(), List.of(), List.of(), null, null, 0);
        }
    }
}

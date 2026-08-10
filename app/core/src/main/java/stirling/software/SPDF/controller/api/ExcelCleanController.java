package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelAiCleanRuleParseService;
import stirling.software.SPDF.service.ExcelAiCleanRuleParseService.AiCleanParseRequest;
import stirling.software.SPDF.service.ExcelAiCleanRuleParseService.AiCleanParseResult;
import stirling.software.SPDF.service.ExcelDataCleanService;
import stirling.software.SPDF.service.ExcelDataCleanService.CleanGenerateRequest;
import stirling.software.SPDF.service.ExcelDataCleanService.CleanGenerateResponse;
import stirling.software.SPDF.service.ExcelDataQualityService;
import stirling.software.SPDF.service.ExcelDataQualityService.CleanAnalyzeResponse;

@RestController
@RequestMapping("/api/excel/clean")
@Tag(name = "Excel Clean", description = "AI assisted Excel data quality analysis and cleaning")
@RequiredArgsConstructor
public class ExcelCleanController {

    private final ExcelDataQualityService excelDataQualityService;
    private final ExcelAiCleanRuleParseService excelAiCleanRuleParseService;
    private final ExcelDataCleanService excelDataCleanService;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload Excel and analyze data quality issues")
    public CleanAnalyzeResponse analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sheetName", required = false) String sheetName)
            throws IOException {
        return excelDataQualityService.analyze(file, sheetName);
    }

    @PostMapping(value = "/ai-parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Use AI to parse a natural language cleaning requirement into rules")
    public AiCleanParseResult aiParse(@RequestBody AiCleanParseRequest request) {
        return excelAiCleanRuleParseService.parse(request);
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate cleaned Excel from confirmed cleaning rules")
    public CleanGenerateResponse generate(@RequestBody CleanGenerateRequest request)
            throws IOException {
        return excelDataCleanService.generate(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ExcelCleanErrorResponse handleBadRequest(IllegalArgumentException exception) {
        return ExcelCleanErrorResponse.failure(exception.getMessage());
    }

    public record ExcelCleanErrorResponse(
            boolean success,
            String message,
            String fileToken,
            String sheetName,
            int rowCount,
            int columnCount,
            List<String> headers,
            List<List<String>> previewData,
            String downloadUrl,
            String fileName,
            List<String> warnings) {

        public static ExcelCleanErrorResponse failure(String message) {
            return new ExcelCleanErrorResponse(
                    false, message, null, null, 0, 0, List.of(), List.of(), null, null, List.of());
        }
    }
}

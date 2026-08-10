package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.Arrays;
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

import stirling.software.SPDF.service.ExcelAiMergeRuleParseService;
import stirling.software.SPDF.service.ExcelAiMergeRuleParseService.AiMergeParseRequest;
import stirling.software.SPDF.service.ExcelAiMergeRuleParseService.AiMergeParseResult;
import stirling.software.SPDF.service.ExcelMergeService;
import stirling.software.SPDF.service.ExcelMergeService.MergeGenerateRequest;
import stirling.software.SPDF.service.ExcelMergeService.MergeGenerateResponse;
import stirling.software.SPDF.service.ExcelMergeService.MergeUploadResponse;

@RestController
@RequestMapping("/api/excel/merge")
@Tag(name = "Excel Merge", description = "AI assisted multi Excel and multi sheet merge")
@RequiredArgsConstructor
public class ExcelMergeController {

    private final ExcelMergeService excelMergeService;
    private final ExcelAiMergeRuleParseService excelAiMergeRuleParseService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload multiple Excel files for merge analysis")
    public MergeUploadResponse upload(@RequestParam("files") MultipartFile[] files)
            throws IOException {
        return excelMergeService.upload(Arrays.asList(files));
    }

    @PostMapping(value = "/ai-parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Use AI to parse an Excel merge requirement into rules")
    public AiMergeParseResult aiParse(@RequestBody AiMergeParseRequest request) {
        return excelAiMergeRuleParseService.parse(request);
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate merged Excel from confirmed merge rules")
    public MergeGenerateResponse generate(@RequestBody MergeGenerateRequest request)
            throws IOException {
        return excelMergeService.generate(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ExcelMergeErrorResponse handleBadRequest(IllegalArgumentException exception) {
        return ExcelMergeErrorResponse.failure(exception.getMessage());
    }

    public record ExcelMergeErrorResponse(
            boolean success,
            String message,
            List<?> files,
            String downloadUrl,
            String fileName,
            int sourceCount,
            int sourceRowCount,
            int resultRowCount,
            int removedDuplicateCount,
            List<String> outputColumns) {

        public static ExcelMergeErrorResponse failure(String message) {
            return new ExcelMergeErrorResponse(
                    false, message, List.of(), null, null, 0, 0, 0, 0, List.of());
        }
    }
}

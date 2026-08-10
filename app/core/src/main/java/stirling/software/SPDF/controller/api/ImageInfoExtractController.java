package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;
import stirling.software.SPDF.service.ImageInfoAiExtractService;
import stirling.software.SPDF.service.ImageInfoAiExtractService.ImageInfoExtractResult;

@RestController
@RequestMapping("/api/excel")
@Tag(name = "Excel Image Info Extract", description = "Extract structured image information")
@RequiredArgsConstructor
public class ImageInfoExtractController {

    private final ImageInfoAiExtractService imageInfoAiExtractService;

    @PostMapping(value = "/image-info-extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Extract structured information from images and export to Excel")
    public ImageInfoExtractResponse extractImageInfo(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(name = "extractType", defaultValue = "auto") String extractType,
            @RequestParam(name = "customFields", required = false) String customFields)
            throws IOException {
        ImageInfoExtractResult result =
                imageInfoAiExtractService.extract(files, extractType, customFields);
        return ImageInfoExtractResponse.from(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ImageInfoExtractResponse handleBadRequest(IllegalArgumentException exception) {
        return ImageInfoExtractResponse.failure(exception.getMessage());
    }

    public record ImageInfoExtractResponse(
            boolean success,
            String message,
            String extractType,
            String ocrText,
            List<List<String>> previewData,
            String downloadUrl,
            String fileName,
            List<String> warnings) {

        static ImageInfoExtractResponse from(ImageInfoExtractResult result) {
            if (!result.success()) {
                return new ImageInfoExtractResponse(
                        false,
                        result.message(),
                        result.extractType(),
                        result.ocrText(),
                        result.previewData(),
                        null,
                        null,
                        result.warnings());
            }
            ExportedExcel exportedExcel = result.exportedExcel();
            return new ImageInfoExtractResponse(
                    true,
                    result.message(),
                    result.extractType(),
                    result.ocrText(),
                    result.previewData(),
                    "/api/excel/download/"
                            + URLEncoder.encode(
                                    exportedExcel.storageFileName(), StandardCharsets.UTF_8),
                    exportedExcel.displayFileName(),
                    result.warnings());
        }

        static ImageInfoExtractResponse failure(String message) {
            return new ImageInfoExtractResponse(
                    false, message, null, "", List.of(), null, null, List.of());
        }
    }
}

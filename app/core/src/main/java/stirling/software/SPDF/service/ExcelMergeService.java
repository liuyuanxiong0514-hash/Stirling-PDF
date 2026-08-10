package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelExportService.ExcelSheet;
import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractSort;

@Service
@RequiredArgsConstructor
public class ExcelMergeService {

    private static final long MAX_UPLOAD_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_FILES = 20;
    private static final int MAX_SHEETS_PER_FILE = 50;
    private static final int PREVIEW_ROW_LIMIT = 20;
    private static final String XLS_EXTENSION = ".xls";
    private static final String XLSX_EXTENSION = ".xlsx";

    private final ExcelExportService excelExportService;
    private final ExcelAppendMergeService excelAppendMergeService;
    private final ExcelJoinMergeService excelJoinMergeService;
    private final Map<String, UploadedMergeFile> uploadedFiles = new ConcurrentHashMap<>();

    private final Path uploadDirectory =
            Path.of(System.getProperty("java.io.tmpdir"), "stirling-pdf", "excel-merge", "uploads");

    public MergeUploadResponse upload(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("\u8bf7\u4e0a\u4f20 Excel \u6587\u4ef6");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException(
                    "\u4e00\u6b21\u6700\u591a\u652f\u6301 20 \u4e2a Excel \u6587\u4ef6");
        }
        Files.createDirectories(uploadDirectory);

        List<MergeUploadedFile> results = new ArrayList<>();
        for (MultipartFile file : files) {
            validateUpload(file);
            String token = UUID.randomUUID().toString();
            String extension = fileExtension(file.getOriginalFilename());
            Path uploadPath = uploadDirectory.resolve(token + extension).normalize();
            if (!uploadPath.startsWith(uploadDirectory)) {
                throw new IOException("Invalid Excel merge upload path");
            }
            file.transferTo(uploadPath);
            String displayName =
                    file.getOriginalFilename() == null
                            ? token + extension
                            : file.getOriginalFilename();
            uploadedFiles.put(token, new UploadedMergeFile(uploadPath, displayName));
            results.add(readFileInfo(token, displayName, uploadPath));
        }
        return new MergeUploadResponse(
                true, "\u0045\u0078\u0063\u0065\u006c\u89e3\u6790\u6210\u529f", results);
    }

    public MergeGenerateResponse generate(MergeGenerateRequest request) throws IOException {
        if (request == null || request.rules() == null) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u751f\u6210\u6216\u786e\u8ba4\u5408\u5e76\u89c4\u5219");
        }
        MergeRules rules = sanitizeRules(request.rules());
        List<MergeSourceData> sources = resolveSources(request.sources(), rules);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u81f3\u5c11\u9009\u62e9\u4e00\u4e2a\u6570\u636e\u6e90");
        }

        MergeResult result =
                switch (rules.mergeType()) {
                    case "append", "multi_sheet_append" ->
                            excelAppendMergeService.merge(sources, rules);
                    case "join" -> excelJoinMergeService.merge(sources, rules);
                    default ->
                            throw new IllegalArgumentException(
                                    "\u0041\u0049\u65e0\u6cd5\u5224\u65ad\u5408\u5e76\u65b9\u5f0f");
                };
        if (result.resultRowCount() <= 0) {
            throw new IllegalArgumentException(
                    "\u5408\u5e76\u5b8c\u6210\uff0c\u4f46\u6ca1\u6709\u751f\u6210\u6709\u6548\u6570\u636e\u3002");
        }

        ExportedExcel exportedExcel =
                excelExportService.exportSheets(
                        List.of(new ExcelSheet("MergeResult", result.table())),
                        "excel_merge_result.xlsx");
        return new MergeGenerateResponse(
                true,
                "\u8868\u683c\u5408\u5e76\u5b8c\u6210",
                "/api/excel/download/"
                        + URLEncoder.encode(
                                exportedExcel.storageFileName(), StandardCharsets.UTF_8),
                exportedExcel.displayFileName(),
                result.sourceCount(),
                result.sourceRowCount(),
                result.resultRowCount(),
                result.removedDuplicateCount(),
                result.outputColumns());
    }

    public List<MergeSourceData> resolveSources(
            List<MergeSourceRef> requestSources, MergeRules rules) throws IOException {
        List<MergeSourceRef> sourceRefs =
                requestSources != null && !requestSources.isEmpty()
                        ? requestSources
                        : rules.sources();
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u9009\u62e9\u9700\u8981\u5408\u5e76\u7684\u6570\u636e\u6e90");
        }
        List<MergeSourceData> sources = new ArrayList<>();
        for (MergeSourceRef sourceRef : sourceRefs) {
            UploadedMergeFile uploadedFile = uploadedFiles.get(sourceRef.fileToken());
            if (uploadedFile == null || !Files.isRegularFile(uploadedFile.path())) {
                throw new IllegalArgumentException(
                        "\u6587\u4ef6 Token \u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20");
            }
            try (Workbook workbook = openWorkbook(uploadedFile.path())) {
                Sheet sheet = getSheet(workbook, sourceRef.sheetName());
                List<String> headers = readHeaders(sheet);
                if (headers.isEmpty()) {
                    throw new IllegalArgumentException(
                            "\u672a\u8bc6\u522b\u5230\u8868\u5934\uff0c\u8bf7\u786e\u8ba4\u7b2c\u4e00\u884c\u4e3a\u5b57\u6bb5\u540d\u79f0");
                }
                sources.add(
                        new MergeSourceData(
                                sourceRef.fileToken(),
                                uploadedFile.displayName(),
                                sheet.getSheetName(),
                                headers,
                                readDataRows(sheet, headers)));
            }
        }
        return sources;
    }

    private MergeUploadedFile readFileInfo(String token, String displayName, Path uploadPath)
            throws IOException {
        try (Workbook workbook = openWorkbook(uploadPath)) {
            if (workbook.getNumberOfSheets() > MAX_SHEETS_PER_FILE) {
                throw new IllegalArgumentException(
                        "\u5355\u4e2a Excel \u7684 Sheet \u6570\u91cf\u8d85\u8fc7\u9650\u5236");
            }
            List<MergeSheetInfo> sheets = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                List<String> headers = readHeaders(sheet);
                sheets.add(
                        new MergeSheetInfo(
                                sheet.getSheetName(),
                                headers,
                                readRows(sheet, headers, PREVIEW_ROW_LIMIT),
                                Math.max(0, sheet.getLastRowNum() - sheet.getFirstRowNum())));
            }
            return new MergeUploadedFile(token, displayName, uploadPath.toFile().length(), sheets);
        }
    }

    private MergeRules sanitizeRules(MergeRules rules) {
        String mergeType =
                rules.mergeType() == null ? "" : rules.mergeType().toLowerCase(Locale.ROOT);
        if (!List.of("append", "join", "multi_sheet_append").contains(mergeType)) {
            throw new IllegalArgumentException(
                    "\u0041\u0049\u65e0\u6cd5\u5224\u65ad\u5408\u5e76\u65b9\u5f0f");
        }
        return new MergeRules(
                "merge",
                mergeType,
                rules.sources() == null ? List.of() : rules.sources(),
                rules.fieldMappings() == null ? List.of() : rules.fieldMappings(),
                rules.selectedColumns() == null ? List.of() : rules.selectedColumns(),
                rules.removeEmptyRows() == null ? Boolean.TRUE : rules.removeEmptyRows(),
                rules.deduplicate() == null ? Boolean.FALSE : rules.deduplicate(),
                rules.deduplicateColumns() == null ? List.of() : rules.deduplicateColumns(),
                rules.sort(),
                rules.join(),
                rules.addSourceColumn(),
                rules.sourceColumnName());
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("\u8bf7\u4e0a\u4f20 Excel \u6587\u4ef6");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "\u0045\u0078\u0063\u0065\u006c\u6587\u4ef6\u8fc7\u5927\uff0c\u8bf7\u4e0a\u4f20 50MB \u4ee5\u5185\u7684\u6587\u4ef6");
        }
        String extension = fileExtension(file.getOriginalFilename());
        if (!XLSX_EXTENSION.equals(extension) && !XLS_EXTENSION.equals(extension)) {
            throw new IllegalArgumentException("\u8bf7\u4e0a\u4f20 .xlsx \u6216 .xls \u6587\u4ef6");
        }
    }

    private Workbook openWorkbook(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return WorkbookFactory.create(inputStream);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "\u0045\u0078\u0063\u0065\u006c\u6587\u4ef6\u89e3\u6790\u5931\u8d25");
        }
    }

    private Sheet getSheet(Workbook workbook, String sheetName) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet != null) {
                return sheet;
            }
        }
        return workbook.getSheetAt(0);
    }

    private List<String> readHeaders(Sheet sheet) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            return List.of();
        }
        DataFormatter formatter = new DataFormatter();
        List<String> headers = new ArrayList<>();
        short lastCellNum = headerRow.getLastCellNum();
        for (int index = 0; index < lastCellNum; index++) {
            String header = formatter.formatCellValue(headerRow.getCell(index)).trim();
            if (header.isBlank()) {
                header = "Column" + (index + 1);
            }
            headers.add(toUniqueHeader(header, headers));
        }
        return headers;
    }

    private String toUniqueHeader(String header, List<String> existingHeaders) {
        String uniqueHeader = header;
        int suffix = 2;
        while (existingHeaders.contains(uniqueHeader)) {
            uniqueHeader = header + "_" + suffix;
            suffix++;
        }
        return uniqueHeader;
    }

    private List<List<String>> readRows(Sheet sheet, List<String> headers, int limit) {
        return readDataRows(sheet, headers).stream()
                .limit(limit)
                .map(row -> headers.stream().map(header -> row.getOrDefault(header, "")).toList())
                .toList();
    }

    private List<Map<String, String>> readDataRows(Sheet sheet, List<String> headers) {
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        for (int rowIndex = sheet.getFirstRowNum() + 1;
                rowIndex <= sheet.getLastRowNum();
                rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Map<String, String> rowData = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                rowData.put(
                        headers.get(columnIndex),
                        row == null || row.getCell(columnIndex) == null
                                ? ""
                                : formatter.formatCellValue(row.getCell(columnIndex)).trim());
            }
            rows.add(rowData);
        }
        return rows;
    }

    private String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        int index = lowerName.lastIndexOf('.');
        return index < 0 ? "" : lowerName.substring(index);
    }

    private record UploadedMergeFile(Path path, String displayName) {}

    public record MergeUploadResponse(
            boolean success, String message, List<MergeUploadedFile> files) {}

    public record MergeUploadedFile(
            String fileToken, String fileName, long fileSize, List<MergeSheetInfo> sheets) {}

    public record MergeSheetInfo(
            String sheetName, List<String> headers, List<List<String>> previewData, int rowCount) {}

    public record MergeSourceRef(
            String fileToken,
            String fileName,
            String sheetName,
            List<String> headers,
            List<List<String>> previewData) {}

    public record MergeSourceData(
            String fileToken,
            String fileName,
            String sheetName,
            List<String> headers,
            List<Map<String, String>> rows) {
        public String displayName() {
            return fileName + " / " + sheetName;
        }
    }

    public record MergeFieldMapping(
            String targetField, List<String> sourceFields, Double confidence) {}

    public record JoinRule(
            int leftSourceIndex,
            int rightSourceIndex,
            String leftKey,
            String rightKey,
            String joinType) {}

    public record MergeRules(
            String mode,
            String mergeType,
            List<MergeSourceRef> sources,
            List<MergeFieldMapping> fieldMappings,
            List<String> selectedColumns,
            Boolean removeEmptyRows,
            Boolean deduplicate,
            List<String> deduplicateColumns,
            SmartExtractSort sort,
            JoinRule join,
            Boolean addSourceColumn,
            String sourceColumnName) {}

    public record MergeGenerateRequest(List<MergeSourceRef> sources, MergeRules rules) {}

    public record MergeResult(
            List<List<String>> table,
            int sourceCount,
            int sourceRowCount,
            int removedDuplicateCount,
            int resultRowCount,
            List<String> outputColumns) {}

    public record MergeGenerateResponse(
            boolean success,
            String message,
            String downloadUrl,
            String fileName,
            int sourceCount,
            int sourceRowCount,
            int resultRowCount,
            int removedDuplicateCount,
            List<String> outputColumns) {}
}

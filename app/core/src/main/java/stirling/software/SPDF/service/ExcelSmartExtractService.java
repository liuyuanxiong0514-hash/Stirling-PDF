package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.SPDF.service.ExcelExportService.ExcelSheet;
import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;
import stirling.software.SPDF.service.ExcelSummaryService.SummaryResult;

@Service
public class ExcelSmartExtractService {

    private static final long MAX_UPLOAD_BYTES = 50L * 1024L * 1024L;
    private static final int PREVIEW_ROW_LIMIT = 20;
    private static final String XLS_EXTENSION = ".xls";
    private static final String XLSX_EXTENSION = ".xlsx";

    private final Path uploadDirectory;
    private final ExcelExportService excelExportService;
    private final ExcelSummaryService excelSummaryService;
    private final Map<String, UploadedExcelFile> uploadedFiles = new ConcurrentHashMap<>();

    public ExcelSmartExtractService(
            ExcelExportService excelExportService, ExcelSummaryService excelSummaryService) {
        this.excelExportService = excelExportService;
        this.excelSummaryService = excelSummaryService;
        this.uploadDirectory =
                Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "stirling-pdf",
                        "excel-smart-extract",
                        "uploads");
    }

    public SmartExtractPreview preview(MultipartFile file) throws IOException {
        validateUpload(file);
        Files.createDirectories(uploadDirectory);

        String token = UUID.randomUUID().toString();
        String extension = fileExtension(file.getOriginalFilename());
        Path uploadPath = uploadDirectory.resolve(token + extension).normalize();
        if (!uploadPath.startsWith(uploadDirectory)) {
            throw new IOException("Invalid Excel upload path");
        }
        file.transferTo(uploadPath);
        uploadedFiles.put(token, new UploadedExcelFile(uploadPath));
        // TODO: Add scheduled cleanup for old smart-extract uploads.

        try (Workbook workbook = openWorkbook(uploadPath)) {
            List<String> sheetNames = new ArrayList<>();
            Map<String, List<String>> sheetHeaders = new LinkedHashMap<>();
            Map<String, List<List<String>>> sheetPreviews = new LinkedHashMap<>();

            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                String sheetName = sheet.getSheetName();
                List<String> headers = readHeaders(sheet);
                List<List<String>> previewRows = readRows(sheet, headers, PREVIEW_ROW_LIMIT);
                sheetNames.add(sheetName);
                sheetHeaders.put(sheetName, headers);
                sheetPreviews.put(sheetName, previewRows);
            }

            String firstSheet = sheetNames.isEmpty() ? null : sheetNames.get(0);
            List<String> headers = firstSheet == null ? List.of() : sheetHeaders.get(firstSheet);
            if (headers == null || headers.isEmpty()) {
                throw new IllegalArgumentException(
                        "\u672a\u8bc6\u522b\u5230\u8868\u5934\uff0c\u8bf7\u786e\u8ba4\u7b2c\u4e00\u884c\u4e3a\u5b57\u6bb5\u540d\u79f0");
            }

            return new SmartExtractPreview(
                    true,
                    "\u0045\u0078\u0063\u0065\u006c\u89e3\u6790\u6210\u529f",
                    token,
                    sheetNames,
                    headers,
                    sheetPreviews.get(firstSheet),
                    sheetHeaders,
                    sheetPreviews);
        }
    }

    public SmartExtractGenerateResult generate(SmartExtractRequest request) throws IOException {
        UploadedExcelFile uploadedFile = uploadedFiles.get(request.fileToken());
        if (uploadedFile == null || !Files.isRegularFile(uploadedFile.path())) {
            throw new IllegalArgumentException(
                    "\u6587\u4ef6\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20");
        }

        try (Workbook workbook = openWorkbook(uploadedFile.path())) {
            Sheet sheet = getSheet(workbook, request.sheetName());
            List<String> headers = readHeaders(sheet);
            if (headers.isEmpty()) {
                throw new IllegalArgumentException(
                        "\u672a\u8bc6\u522b\u5230\u8868\u5934\uff0c\u8bf7\u786e\u8ba4\u7b2c\u4e00\u884c\u4e3a\u5b57\u6bb5\u540d\u79f0");
            }

            List<String> selectedColumns = selectedColumns(request.selectedColumns(), headers);
            Map<String, Integer> headerIndexes = headerIndexes(headers);
            validateFilters(request.filters(), headerIndexes);

            List<Map<String, String>> rows = readDataRows(sheet, headers);
            if (Boolean.TRUE.equals(request.removeEmptyRows())) {
                rows = rows.stream().filter(row -> !isEmptyRow(row, headers)).toList();
            }
            rows = applyFilters(rows, request.filters());

            if (excelSummaryService.isSummary(request)) {
                excelSummaryService.validate(request, headerIndexes);
                SummaryResult summaryResult = excelSummaryService.summarize(rows, request);
                ExportedExcel exportedExcel =
                        excelExportService.exportSheets(
                                List.of(new ExcelSheet("Summary", summaryResult.table())),
                                "smart_summary_result.xlsx");
                return new SmartExtractGenerateResult(
                        true,
                        "\u0045\u0078\u0063\u0065\u006c\u751f\u6210\u6210\u529f",
                        exportedExcel,
                        summaryResult.rowCount());
            }

            validateColumns(selectedColumns, headerIndexes);
            validateSort(request.sort(), headerIndexes);
            validateOptionalColumn(request.groupByColumn(), headerIndexes);

            if (Boolean.TRUE.equals(request.deduplicate())) {
                rows = deduplicate(rows, selectedColumns);
            }
            rows = sortRows(rows, request.sort());

            if (rows.isEmpty()) {
                throw new IllegalArgumentException(
                        "\u7b5b\u9009\u540e\u6ca1\u6709\u7b26\u5408\u6761\u4ef6\u7684\u6570\u636e");
            }

            List<ExcelSheet> outputSheets =
                    toOutputSheets(rows, selectedColumns, request.groupByColumn());
            ExportedExcel exportedExcel =
                    excelExportService.exportSheets(outputSheets, "smart_extract_result.xlsx");
            return new SmartExtractGenerateResult(
                    true,
                    "\u0045\u0078\u0063\u0065\u006c\u751f\u6210\u6210\u529f",
                    exportedExcel,
                    rows.size());
        }
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
        if (workbook.getNumberOfSheets() == 0) {
            throw new IllegalArgumentException(
                    "\u0045\u0078\u0063\u0065\u006c\u4e2d\u6ca1\u6709\u53ef\u7528\u7684 sheet");
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
        return headers.stream().filter(header -> !header.isBlank()).toList();
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
        int firstDataRow = sheet.getFirstRowNum() + 1;
        int lastRow = sheet.getLastRowNum();

        for (int rowIndex = firstDataRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Map<String, String> rowData = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                rowData.put(headers.get(columnIndex), readCell(row, columnIndex, formatter));
            }
            rows.add(rowData);
        }
        return rows;
    }

    private String readCell(Row row, int columnIndex, DataFormatter formatter) {
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private List<Map<String, String>> applyFilters(
            List<Map<String, String>> rows, List<SmartExtractFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> filters.stream().allMatch(filter -> matches(row, filter)))
                .toList();
    }

    private boolean matches(Map<String, String> row, SmartExtractFilter filter) {
        String value = row.getOrDefault(filter.column(), "");
        String type = safeLower(filter.type());
        return switch (type) {
            case "number" -> matchesNumber(value, filter);
            case "date" -> matchesDate(value, filter);
            default -> matchesText(value, filter);
        };
    }

    private boolean matchesText(String value, SmartExtractFilter filter) {
        String expected = filter.value() == null ? "" : filter.value();
        String operator = filter.operator() == null ? "contains" : filter.operator();
        if ("equals".equalsIgnoreCase(operator) || "=".equals(operator)) {
            return value.equals(expected);
        }
        return value.contains(expected);
    }

    private boolean matchesNumber(String value, SmartExtractFilter filter) {
        BigDecimal actual = parseNumber(value, filter.column());
        if ("between".equalsIgnoreCase(filter.operator())) {
            BigDecimal start = parseNumber(betweenStart(filter), filter.column());
            BigDecimal end = parseNumber(betweenEnd(filter), filter.column());
            return actual.compareTo(start) >= 0 && actual.compareTo(end) <= 0;
        }
        BigDecimal expected = parseNumber(filter.value(), filter.column());
        int compare = actual.compareTo(expected);
        return switch (filter.operator()) {
            case ">" -> compare > 0;
            case ">=" -> compare >= 0;
            case "<" -> compare < 0;
            case "<=" -> compare <= 0;
            case "=", "==", "equals" -> compare == 0;
            default ->
                    throw new IllegalArgumentException(
                            "\u7b5b\u9009\u6761\u4ef6\u683c\u5f0f\u9519\u8bef");
        };
    }

    private String betweenStart(SmartExtractFilter filter) {
        if (filter.startDate() != null && !filter.startDate().isBlank()) {
            return filter.startDate();
        }
        String[] values = splitBetweenValue(filter.value());
        return values[0];
    }

    private String betweenEnd(SmartExtractFilter filter) {
        if (filter.endDate() != null && !filter.endDate().isBlank()) {
            return filter.endDate();
        }
        String[] values = splitBetweenValue(filter.value());
        return values.length > 1 ? values[1] : "";
    }

    private String[] splitBetweenValue(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.split("\\s*(?:,|，|~|至|到)\\s*", 2);
    }

    private boolean matchesDate(String value, SmartExtractFilter filter) {
        LocalDate actual = parseDate(value, filter.column());
        LocalDate start = parseOptionalDate(filter.startDate(), filter.column());
        LocalDate end = parseOptionalDate(filter.endDate(), filter.column());
        if (start == null && filter.value() != null && !filter.value().isBlank()) {
            start = parseDate(filter.value(), filter.column());
        }
        if (start != null && actual.isBefore(start)) {
            return false;
        }
        return end == null || !actual.isAfter(end);
    }

    private BigDecimal parseNumber(String value, String column) {
        try {
            return new BigDecimal(value == null ? "" : value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    column
                            + "\u5b57\u6bb5\u5305\u542b\u975e\u6570\u5b57\u5185\u5bb9\uff0c\u8bf7\u68c0\u67e5\u540e\u91cd\u8bd5");
        }
    }

    private LocalDate parseOptionalDate(String value, String column) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseDate(value, column);
    }

    private LocalDate parseDate(String value, String column) {
        String trimmed = value == null ? "" : value.trim();
        List<DateTimeFormatter> formatters =
                List.of(
                        DateTimeFormatter.ISO_LOCAL_DATE,
                        DateTimeFormatter.ofPattern("yyyy/M/d"),
                        DateTimeFormatter.ofPattern("yyyy.M.d"),
                        DateTimeFormatter.ofPattern("M/d/yyyy"),
                        DateTimeFormatter.ofPattern("M/d/yy"));
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported date format.
            }
        }
        try {
            double excelDate = Double.parseDouble(trimmed);
            return DateUtil.getJavaDate(excelDate)
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    column
                            + "\u65e5\u671f\u683c\u5f0f\u65e0\u6cd5\u8bc6\u522b\uff0c\u8bf7\u68c0\u67e5\u540e\u91cd\u8bd5");
        }
    }

    private List<Map<String, String>> deduplicate(
            List<Map<String, String>> rows, List<String> selectedColumns) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String key =
                    selectedColumns.stream()
                            .map(column -> row.getOrDefault(column, ""))
                            .toList()
                            .toString();
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    private List<Map<String, String>> sortRows(
            List<Map<String, String>> rows, SmartExtractSort sort) {
        if (sort == null || sort.column() == null || sort.column().isBlank()) {
            return rows;
        }
        Comparator<Map<String, String>> comparator =
                Comparator.comparing(
                        row -> row.getOrDefault(sort.column(), ""), this::compareNatural);
        if ("desc".equalsIgnoreCase(sort.direction())) {
            comparator = comparator.reversed();
        }
        return rows.stream().sorted(comparator).toList();
    }

    private int compareNatural(String left, String right) {
        try {
            return parseNumber(left, "").compareTo(parseNumber(right, ""));
        } catch (IllegalArgumentException ignored) {
            return left.compareToIgnoreCase(right);
        }
    }

    private List<ExcelSheet> toOutputSheets(
            List<Map<String, String>> rows, List<String> selectedColumns, String groupByColumn) {
        if (groupByColumn == null || groupByColumn.isBlank()) {
            return List.of(new ExcelSheet("SmartExtract", toTable(rows, selectedColumns)));
        }

        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String groupName = row.getOrDefault(groupByColumn, "").trim();
            if (groupName.isBlank()) {
                groupName = "Blank";
            }
            grouped.computeIfAbsent(groupName, ignored -> new ArrayList<>()).add(row);
        }

        return grouped.entrySet().stream()
                .map(
                        entry ->
                                new ExcelSheet(
                                        entry.getKey(), toTable(entry.getValue(), selectedColumns)))
                .toList();
    }

    private List<List<String>> toTable(
            List<Map<String, String>> rows, List<String> selectedColumns) {
        List<List<String>> table = new ArrayList<>();
        table.add(selectedColumns);
        rows.forEach(
                row ->
                        table.add(
                                selectedColumns.stream()
                                        .map(column -> row.getOrDefault(column, ""))
                                        .toList()));
        return table;
    }

    private boolean isEmptyRow(Map<String, String> row, List<String> headers) {
        return headers.stream().allMatch(header -> row.getOrDefault(header, "").isBlank());
    }

    private List<String> selectedColumns(List<String> requestedColumns, List<String> headers) {
        if (requestedColumns == null || requestedColumns.isEmpty()) {
            return headers;
        }
        return requestedColumns;
    }

    private Map<String, Integer> headerIndexes(List<String> headers) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    private void validateColumns(List<String> columns, Map<String, Integer> headerIndexes) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u81f3\u5c11\u9009\u62e9\u4e00\u4e2a\u4fdd\u7559\u5b57\u6bb5");
        }
        columns.forEach(column -> validateOptionalColumn(column, headerIndexes));
    }

    private void validateFilters(
            List<SmartExtractFilter> filters, Map<String, Integer> headerIndexes) {
        if (filters == null) {
            return;
        }
        for (SmartExtractFilter filter : filters) {
            validateOptionalColumn(filter.column(), headerIndexes);
            if (!"date".equalsIgnoreCase(filter.type())
                    && (filter.value() == null || filter.value().isBlank())) {
                throw new IllegalArgumentException(
                        "\u7b5b\u9009\u6761\u4ef6\u683c\u5f0f\u9519\u8bef");
            }
        }
    }

    private void validateSort(SmartExtractSort sort, Map<String, Integer> headerIndexes) {
        if (sort != null) {
            validateOptionalColumn(sort.column(), headerIndexes);
        }
    }

    private void validateOptionalColumn(String column, Map<String, Integer> headerIndexes) {
        if (column != null && !column.isBlank() && !headerIndexes.containsKey(column)) {
            throw new IllegalArgumentException(
                    "\u9009\u62e9\u7684\u5217\u4e0d\u5b58\u5728\uff1a" + column);
        }
    }

    private String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        int index = lowerName.lastIndexOf('.');
        return index < 0 ? "" : lowerName.substring(index);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record UploadedExcelFile(Path path) {}

    public record SmartExtractPreview(
            boolean success,
            String message,
            String fileToken,
            List<String> sheets,
            List<String> headers,
            List<List<String>> previewData,
            Map<String, List<String>> sheetHeaders,
            Map<String, List<List<String>>> sheetPreviews) {}

    public record SmartExtractRequest(
            String mode,
            String fileToken,
            String sheetName,
            List<String> selectedColumns,
            List<SmartExtractFilter> filters,
            Boolean removeEmptyRows,
            Boolean deduplicate,
            SmartExtractSort sort,
            String groupByColumn,
            List<String> groupBy,
            List<SummaryMetric> metrics) {}

    public record SmartExtractFilter(
            String column,
            String operator,
            String value,
            String type,
            String startDate,
            String endDate) {}

    public record SmartExtractSort(String column, String direction) {}

    public record SummaryMetric(String column, String operation, String alias) {}

    public record SmartExtractGenerateResult(
            boolean success, String message, ExportedExcel exportedExcel, int rowCount) {}
}

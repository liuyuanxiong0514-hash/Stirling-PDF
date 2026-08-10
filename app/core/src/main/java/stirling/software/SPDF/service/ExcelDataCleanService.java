package stirling.software.SPDF.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelDataQualityService.UploadedCleanFile;
import stirling.software.SPDF.service.ExcelExportService.ExportedExcel;
import stirling.software.SPDF.service.ExcelExportService.TypedExcelSheet;

@Service
@RequiredArgsConstructor
public class ExcelDataCleanService {

    private final ExcelDataQualityService excelDataQualityService;
    private final ExcelCleanRuleValidator excelCleanRuleValidator;
    private final ExcelExportService excelExportService;

    public CleanGenerateResponse generate(CleanGenerateRequest request) throws IOException {
        if (request == null || request.fileToken() == null || request.rules() == null) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u4e0a\u4f20 Excel \u5e76\u786e\u8ba4\u6e05\u6d17\u89c4\u5219");
        }
        UploadedCleanFile uploadedFile =
                excelDataQualityService.getUploadedFile(request.fileToken());
        try (Workbook workbook = excelDataQualityService.openWorkbook(uploadedFile.path())) {
            Sheet sheet = excelDataQualityService.getSheet(workbook, request.sheetName());
            List<String> headers = excelDataQualityService.readHeaders(sheet);
            CleanRules rules = excelCleanRuleValidator.validate(request.rules(), headers);
            List<Map<String, Object>> rows =
                    new ArrayList<>(
                            excelDataQualityService.readRows(sheet, headers).stream()
                                    .map(row -> new LinkedHashMap<String, Object>(row))
                                    .toList());
            int sourceRowCount = rows.size();
            MutableCleanStatistics statistics = new MutableCleanStatistics(sourceRowCount);
            List<CleanExceptionRow> exceptions = new ArrayList<>();

            if (Boolean.TRUE.equals(rules.trimText())) {
                trimRows(rows, statistics);
            }
            if (Boolean.TRUE.equals(rules.normalizeFullWidth())) {
                normalizeFullWidth(rows);
            }
            if (Boolean.TRUE.equals(rules.removeEmptyRows())) {
                int before = rows.size();
                rows = rows.stream().filter(row -> !isEmptyRow(row, headers)).toList();
                statistics.removedEmptyRows = before - rows.size();
            }
            applyColumnRules(rows, rules, exceptions, statistics);
            applyValueMappings(rows, rules, statistics);
            if (rules.deduplicate() != null && Boolean.TRUE.equals(rules.deduplicate().enabled())) {
                int before = rows.size();
                rows = deduplicate(rows, deduplicateColumns(rules.deduplicate(), headers));
                statistics.removedDuplicates = before - rows.size();
            }
            statistics.resultRowCount = rows.size();

            List<TypedExcelSheet> sheets = new ArrayList<>();
            sheets.add(new TypedExcelSheet("CleanedData", toTable(headers, rows)));
            if (!exceptions.isEmpty()) {
                sheets.add(
                        new TypedExcelSheet(
                                "\u6e05\u6d17\u5f02\u5e38", exceptionTable(exceptions)));
            }
            ExportedExcel exportedExcel =
                    excelExportService.exportTypedSheets(sheets, "cleaned_result.xlsx");
            return new CleanGenerateResponse(
                    true,
                    "\u0045\u0078\u0063\u0065\u006c\u6570\u636e\u6e05\u6d17\u5b8c\u6210",
                    "/api/excel/download/"
                            + java.net.URLEncoder.encode(
                                    exportedExcel.storageFileName(),
                                    java.nio.charset.StandardCharsets.UTF_8),
                    exportedExcel.displayFileName(),
                    statistics.toRecord(),
                    exceptionWarnings(exceptions));
        }
    }

    private void trimRows(List<Map<String, Object>> rows, MutableCleanStatistics statistics) {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() instanceof String text && !text.equals(text.trim())) {
                    entry.setValue(text.trim());
                    statistics.trimmedCells++;
                }
            }
        }
    }

    private void normalizeFullWidth(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            row.replaceAll(
                    (key, value) ->
                            value instanceof String text
                                    ? ExcelDataQualityService.normalizeFullWidth(text)
                                    : value);
        }
    }

    private void applyColumnRules(
            List<Map<String, Object>> rows,
            CleanRules rules,
            List<CleanExceptionRow> exceptions,
            MutableCleanStatistics statistics) {
        for (ColumnRule rule : rules.columnRules()) {
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Map<String, Object> row = rows.get(rowIndex);
                Object value = row.get(rule.column());
                switch (rule.operation()) {
                    case "normalize_number" ->
                            normalizeNumber(
                                    row,
                                    rowIndex,
                                    rule.column(),
                                    value,
                                    rules.invalidValuePolicy(),
                                    exceptions,
                                    statistics);
                    case "normalize_date" ->
                            normalizeDate(
                                    row,
                                    rowIndex,
                                    rule.column(),
                                    value,
                                    rules.invalidValuePolicy(),
                                    exceptions,
                                    statistics);
                    case "remove_spaces" ->
                            row.put(
                                    rule.column(),
                                    ExcelDataQualityService.removeSpaces(
                                            String.valueOf(value == null ? "" : value)));
                    case "uppercase" ->
                            row.put(
                                    rule.column(),
                                    String.valueOf(value == null ? "" : value)
                                            .toUpperCase(java.util.Locale.ROOT));
                    case "lowercase" ->
                            row.put(
                                    rule.column(),
                                    String.valueOf(value == null ? "" : value)
                                            .toLowerCase(java.util.Locale.ROOT));
                    case "normalize_text" ->
                            row.put(
                                    rule.column(),
                                    String.valueOf(value == null ? "" : value).trim());
                    case "normalize_phone" ->
                            row.put(
                                    rule.column(),
                                    ExcelDataQualityService.removeSpaces(
                                            String.valueOf(value == null ? "" : value)));
                    case "replace" -> row.put(rule.column(), replaceValue(value, rule.options()));
                    default -> {
                        // Validated before execution.
                    }
                }
            }
        }
    }

    private void normalizeNumber(
            Map<String, Object> row,
            int rowIndex,
            String column,
            Object value,
            String policy,
            List<CleanExceptionRow> exceptions,
            MutableCleanStatistics statistics) {
        String raw = String.valueOf(value == null ? "" : value);
        if (raw.isBlank()) {
            return;
        }
        try {
            row.put(column, new BigDecimal(ExcelDataQualityService.normalizeNumberString(raw)));
            statistics.normalizedNumberCells++;
        } catch (NumberFormatException e) {
            handleInvalid(
                    row,
                    rowIndex,
                    column,
                    raw,
                    "\u65e0\u6cd5\u89e3\u6790\u4e3a\u6570\u5b57",
                    policy,
                    exceptions,
                    statistics);
        }
    }

    private void normalizeDate(
            Map<String, Object> row,
            int rowIndex,
            String column,
            Object value,
            String policy,
            List<CleanExceptionRow> exceptions,
            MutableCleanStatistics statistics) {
        String raw = String.valueOf(value == null ? "" : value);
        if (raw.isBlank()) {
            return;
        }
        LocalDate date = ExcelDataQualityService.parseLocalDate(raw);
        if (date == null) {
            handleInvalid(
                    row,
                    rowIndex,
                    column,
                    raw,
                    "\u65e0\u6cd5\u89e3\u6790\u4e3a\u65e5\u671f",
                    policy,
                    exceptions,
                    statistics);
            return;
        }
        row.put(column, date);
        statistics.normalizedDateCells++;
    }

    private Object replaceValue(Object value, Map<String, Object> options) {
        String text = String.valueOf(value == null ? "" : value);
        if (options == null) {
            return text;
        }
        String from = String.valueOf(options.getOrDefault("from", ""));
        String to = String.valueOf(options.getOrDefault("to", ""));
        return from.isBlank() ? text : text.replace(from, to);
    }

    private void handleInvalid(
            Map<String, Object> row,
            int rowIndex,
            String column,
            String raw,
            String issue,
            String policy,
            List<CleanExceptionRow> exceptions,
            MutableCleanStatistics statistics) {
        statistics.invalidValueCount++;
        if ("clear".equals(policy)) {
            row.put(column, "");
        }
        if ("keep_and_mark".equals(policy)) {
            exceptions.add(new CleanExceptionRow(rowIndex + 2, column, raw, issue));
        }
    }

    private void applyValueMappings(
            List<Map<String, Object>> rows, CleanRules rules, MutableCleanStatistics statistics) {
        for (ValueMapping valueMapping : rules.valueMappings()) {
            for (Map<String, Object> row : rows) {
                String current = String.valueOf(row.getOrDefault(valueMapping.column(), ""));
                Map<String, String> mappings =
                        valueMapping.mappings() == null ? Map.of() : valueMapping.mappings();
                String mapped = mappings.get(current);
                if (mapped == null) {
                    mapped = mappings.get(current.trim());
                }
                if (mapped != null && !mapped.equals(current)) {
                    row.put(valueMapping.column(), mapped);
                    statistics.mappedValues++;
                }
            }
        }
    }

    private List<Map<String, Object>> deduplicate(
            List<Map<String, Object>> rows, List<String> columns) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key =
                    columns.stream()
                            .map(column -> String.valueOf(row.getOrDefault(column, "")))
                            .toList()
                            .toString();
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    private List<String> deduplicateColumns(DeduplicateRule rule, List<String> headers) {
        return rule.columns() == null || rule.columns().isEmpty() ? headers : rule.columns();
    }

    private boolean isEmptyRow(Map<String, Object> row, List<String> headers) {
        return headers.stream()
                .allMatch(header -> String.valueOf(row.getOrDefault(header, "")).isBlank());
    }

    private List<List<Object>> toTable(List<String> headers, List<Map<String, Object>> rows) {
        List<List<Object>> table = new ArrayList<>();
        table.add(new ArrayList<>(headers));
        for (Map<String, Object> row : rows) {
            table.add(headers.stream().map(header -> row.getOrDefault(header, "")).toList());
        }
        return table;
    }

    private List<List<Object>> exceptionTable(List<CleanExceptionRow> exceptions) {
        List<List<Object>> table = new ArrayList<>();
        table.add(List.of("\u884c\u53f7", "\u5b57\u6bb5", "\u539f\u59cb\u503c", "\u95ee\u9898"));
        for (CleanExceptionRow exception : exceptions) {
            table.add(
                    List.of(
                            exception.rowNumber(),
                            exception.column(),
                            exception.originalValue(),
                            exception.issue()));
        }
        return table;
    }

    private List<String> exceptionWarnings(List<CleanExceptionRow> exceptions) {
        if (exceptions.isEmpty()) {
            return List.of();
        }
        return List.of(
                "\u5b58\u5728 "
                        + exceptions.size()
                        + " \u6761\u5f02\u5e38\u503c\uff0c\u5df2\u5199\u5165\u6e05\u6d17\u5f02\u5e38 Sheet\u3002");
    }

    public record CleanGenerateRequest(String fileToken, String sheetName, CleanRules rules) {}

    public record CleanRules(
            String mode,
            Boolean removeEmptyRows,
            DeduplicateRule deduplicate,
            Boolean trimText,
            Boolean normalizeFullWidth,
            List<ColumnRule> columnRules,
            List<ValueMapping> valueMappings,
            String invalidValuePolicy) {}

    public record DeduplicateRule(Boolean enabled, List<String> columns) {}

    public record ColumnRule(String column, String operation, Map<String, Object> options) {}

    public record ValueMapping(String column, Map<String, String> mappings) {}

    public record CleanStatistics(
            int sourceRowCount,
            int resultRowCount,
            int removedEmptyRows,
            int removedDuplicates,
            int trimmedCells,
            int normalizedNumberCells,
            int normalizedDateCells,
            int mappedValues,
            int invalidValueCount) {}

    private static final class MutableCleanStatistics {
        private final int sourceRowCount;
        private int resultRowCount;
        private int removedEmptyRows;
        private int removedDuplicates;
        private int trimmedCells;
        private int normalizedNumberCells;
        private int normalizedDateCells;
        private int mappedValues;
        private int invalidValueCount;

        private MutableCleanStatistics(int sourceRowCount) {
            this.sourceRowCount = sourceRowCount;
        }

        private CleanStatistics toRecord() {
            return new CleanStatistics(
                    sourceRowCount,
                    resultRowCount,
                    removedEmptyRows,
                    removedDuplicates,
                    trimmedCells,
                    normalizedNumberCells,
                    normalizedDateCells,
                    mappedValues,
                    invalidValueCount);
        }
    }

    private record CleanExceptionRow(
            int rowNumber, String column, String originalValue, String issue) {}

    public record CleanGenerateResponse(
            boolean success,
            String message,
            String downloadUrl,
            String fileName,
            CleanStatistics statistics,
            List<String> warnings) {}
}

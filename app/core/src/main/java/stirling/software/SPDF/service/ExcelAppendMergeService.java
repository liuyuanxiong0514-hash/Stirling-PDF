package stirling.software.SPDF.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.ExcelMergeService.MergeResult;
import stirling.software.SPDF.service.ExcelMergeService.MergeRules;
import stirling.software.SPDF.service.ExcelMergeService.MergeSourceData;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractSort;

@Service
@RequiredArgsConstructor
public class ExcelAppendMergeService {

    private final ExcelMergeFieldMappingService fieldMappingService;

    public MergeResult merge(List<MergeSourceData> sources, MergeRules rules) {
        List<String> outputColumns = new ArrayList<>(fieldMappingService.outputColumns(rules));
        if (outputColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "\u65e0\u6cd5\u8bc6\u522b\u8fd9\u4e9b\u8868\u683c\u4e4b\u95f4\u7684\u5b57\u6bb5\u5173\u7cfb\uff0c\u8bf7\u624b\u52a8\u9009\u62e9\u5b57\u6bb5\u6620\u5c04\u3002");
        }

        String sourceColumn = sourceColumnName(rules);
        if (Boolean.TRUE.equals(rules.addSourceColumn()) && !outputColumns.contains(sourceColumn)) {
            outputColumns.add(sourceColumn);
        }

        List<Map<String, String>> normalizedRows = new ArrayList<>();
        int sourceRowCount = 0;
        for (MergeSourceData source : sources) {
            for (Map<String, String> row : source.rows()) {
                sourceRowCount++;
                Map<String, String> normalized =
                        fieldMappingService.normalizeRow(source, row, rules);
                if (Boolean.TRUE.equals(rules.addSourceColumn())) {
                    normalized.put(sourceColumn, source.displayName());
                }
                if (Boolean.TRUE.equals(rules.removeEmptyRows())
                        && isEmpty(normalized, outputColumns)) {
                    continue;
                }
                normalizedRows.add(normalized);
            }
        }

        int beforeDeduplicate = normalizedRows.size();
        if (Boolean.TRUE.equals(rules.deduplicate())) {
            normalizedRows = deduplicate(normalizedRows, deduplicateColumns(rules, outputColumns));
        }
        normalizedRows = sort(normalizedRows, rules.sort());

        List<List<String>> table = toTable(normalizedRows, outputColumns);
        return new MergeResult(
                table,
                sources.size(),
                sourceRowCount,
                Math.max(0, beforeDeduplicate - normalizedRows.size()),
                normalizedRows.size(),
                outputColumns);
    }

    private String sourceColumnName(MergeRules rules) {
        return rules.sourceColumnName() == null || rules.sourceColumnName().isBlank()
                ? "\u6765\u6e90\u6587\u4ef6"
                : rules.sourceColumnName();
    }

    private List<String> deduplicateColumns(MergeRules rules, List<String> outputColumns) {
        return rules.deduplicateColumns() == null || rules.deduplicateColumns().isEmpty()
                ? outputColumns
                : rules.deduplicateColumns();
    }

    private List<Map<String, String>> deduplicate(
            List<Map<String, String>> rows, List<String> deduplicateColumns) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String key =
                    deduplicateColumns.stream()
                            .map(column -> row.getOrDefault(column, ""))
                            .toList()
                            .toString();
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    private List<Map<String, String>> sort(List<Map<String, String>> rows, SmartExtractSort sort) {
        if (sort == null || sort.column() == null || sort.column().isBlank()) {
            return rows;
        }
        Comparator<Map<String, String>> comparator =
                Comparator.comparing(row -> row.getOrDefault(sort.column(), ""), this::compare);
        if ("desc".equalsIgnoreCase(sort.direction())) {
            comparator = comparator.reversed();
        }
        return rows.stream().sorted(comparator).toList();
    }

    private int compare(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        } catch (NumberFormatException e) {
            return left.compareToIgnoreCase(right);
        }
    }

    private boolean isEmpty(Map<String, String> row, List<String> outputColumns) {
        return outputColumns.stream().allMatch(column -> row.getOrDefault(column, "").isBlank());
    }

    private List<List<String>> toTable(List<Map<String, String>> rows, List<String> outputColumns) {
        List<List<String>> table = new ArrayList<>();
        table.add(outputColumns);
        for (Map<String, String> row : rows) {
            table.add(outputColumns.stream().map(column -> row.getOrDefault(column, "")).toList());
        }
        return table;
    }
}

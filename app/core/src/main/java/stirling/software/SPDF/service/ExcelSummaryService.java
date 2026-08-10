package stirling.software.SPDF.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractRequest;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractSort;
import stirling.software.SPDF.service.ExcelSmartExtractService.SummaryMetric;

@Service
public class ExcelSummaryService {

    private static final List<String> ALLOWED_OPERATIONS =
            List.of("sum", "count", "avg", "max", "min");

    public SummaryResult summarize(List<Map<String, String>> rows, SmartExtractRequest request) {
        if (request.metrics() == null || request.metrics().isEmpty()) {
            throw new IllegalArgumentException(
                    "\u672a\u8bc6\u522b\u5230\u7edf\u8ba1\u6307\u6807\uff0c\u8bf7\u8bf4\u660e\u8981\u7edf\u8ba1\u91d1\u989d\u3001\u6570\u91cf\u3001\u5e73\u5747\u503c\u7b49\u3002");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("\u6c47\u603b\u540e\u7ed3\u679c\u4e3a\u7a7a\u3002");
        }

        List<String> groupBy = request.groupBy() == null ? List.of() : request.groupBy();
        for (String group : groupBy) {
            if (isMonthGroup(group)) {
                throw new IllegalArgumentException(
                        "\u5f53\u524d\u7248\u672c\u6682\u4e0d\u652f\u6301\u6708\u4efd\u5206\u7ec4\uff0c\u8bf7\u9009\u62e9\u5177\u4f53\u65e5\u671f\u5b57\u6bb5\u6216\u540e\u7eed\u5347\u7ea7\u3002");
            }
        }

        Map<List<String>, SummaryBucket> buckets = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            List<String> key =
                    groupBy.stream().map(group -> row.getOrDefault(group, "").trim()).toList();
            buckets.computeIfAbsent(key, ignored -> new SummaryBucket())
                    .add(row, request.metrics());
        }

        List<String> headers = new ArrayList<>();
        headers.addAll(groupBy);
        headers.addAll(request.metrics().stream().map(this::metricAlias).toList());

        List<List<String>> dataRows = new ArrayList<>();
        for (Map.Entry<List<String>, SummaryBucket> entry : buckets.entrySet()) {
            List<String> outputRow = new ArrayList<>(entry.getKey());
            outputRow.addAll(entry.getValue().values(request.metrics(), this::metricAlias));
            dataRows.add(outputRow);
        }

        dataRows = sortRows(dataRows, headers, request.sort());
        List<List<String>> table = new ArrayList<>();
        table.add(headers);
        table.addAll(dataRows);
        return new SummaryResult(table, dataRows.size());
    }

    public void validate(SmartExtractRequest request, Map<String, Integer> headerIndexes) {
        List<String> groupBy = request.groupBy() == null ? List.of() : request.groupBy();
        for (String group : groupBy) {
            if (isMonthGroup(group)) {
                continue;
            }
            validateColumn(group, headerIndexes);
        }
        if (request.metrics() == null || request.metrics().isEmpty()) {
            throw new IllegalArgumentException(
                    "\u672a\u8bc6\u522b\u5230\u7edf\u8ba1\u6307\u6807\uff0c\u8bf7\u8bf4\u660e\u8981\u7edf\u8ba1\u91d1\u989d\u3001\u6570\u91cf\u3001\u5e73\u5747\u503c\u7b49\u3002");
        }
        for (SummaryMetric metric : request.metrics()) {
            String operation = normalizeOperation(metric.operation());
            if (!ALLOWED_OPERATIONS.contains(operation)) {
                throw new IllegalArgumentException(
                        "\u5f53\u524d\u7248\u672c\u6682\u4e0d\u652f\u6301\u8be5\u590d\u6742\u7edf\u8ba1\uff0c\u8bf7\u5c1d\u8bd5\u4f7f\u7528\u66f4\u660e\u786e\u7684\u63cf\u8ff0\u3002");
            }
            if (!"count".equals(operation) && !"*".equals(metric.column())) {
                validateColumn(metric.column(), headerIndexes);
            }
            if ("count".equals(operation)) {
                continue;
            }
            if (metric.column() == null
                    || metric.column().isBlank()
                    || "*".equals(metric.column())) {
                throw new IllegalArgumentException(
                        "\u672a\u8bc6\u522b\u5230\u7edf\u8ba1\u6307\u6807\uff0c\u8bf7\u8bf4\u660e\u8981\u7edf\u8ba1\u91d1\u989d\u3001\u6570\u91cf\u3001\u5e73\u5747\u503c\u7b49\u3002");
            }
        }
    }

    public boolean isSummary(SmartExtractRequest request) {
        return request != null && "summary".equalsIgnoreCase(request.mode());
    }

    private List<List<String>> sortRows(
            List<List<String>> rows, List<String> headers, SmartExtractSort sort) {
        if (sort == null || sort.column() == null || sort.column().isBlank()) {
            return rows;
        }
        int columnIndex = headers.indexOf(sort.column());
        if (columnIndex < 0) {
            throw new IllegalArgumentException(
                    "\u6392\u5e8f\u5b57\u6bb5\u4e0d\u5728\u6c47\u603b\u7ed3\u679c\u4e2d\u3002");
        }
        Comparator<List<String>> comparator =
                Comparator.comparing(row -> row.get(columnIndex), this::compareNatural);
        if ("desc".equalsIgnoreCase(sort.direction())) {
            comparator = comparator.reversed();
        }
        return rows.stream().sorted(comparator).toList();
    }

    private int compareNatural(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        } catch (NumberFormatException e) {
            return left.compareToIgnoreCase(right);
        }
    }

    private void validateColumn(String column, Map<String, Integer> headerIndexes) {
        if (column == null || column.isBlank() || !headerIndexes.containsKey(column)) {
            throw new IllegalArgumentException(
                    "\u672a\u8bc6\u522b\u5230\u5206\u7ec4\u5b57\u6bb5\uff0c\u8bf7\u8bf4\u660e\u6309\u54ea\u4e2a\u5b57\u6bb5\u6c47\u603b\uff0c\u4f8b\u5982\u6309\u90e8\u95e8\u3001\u9879\u76ee\u6216\u4eba\u5458\u3002");
        }
    }

    private boolean isMonthGroup(String group) {
        return group != null && ("\u6708\u4efd".equals(group) || "month".equalsIgnoreCase(group));
    }

    private String metricAlias(SummaryMetric metric) {
        if (metric.alias() != null && !metric.alias().isBlank()) {
            return metric.alias();
        }
        return switch (normalizeOperation(metric.operation())) {
            case "sum" -> "\u603b" + metric.column();
            case "avg" -> "\u5e73\u5747" + metric.column();
            case "max" -> "\u6700\u5927" + metric.column();
            case "min" -> "\u6700\u5c0f" + metric.column();
            default -> "\u6570\u91cf";
        };
    }

    private String normalizeOperation(String operation) {
        return operation == null ? "" : operation.trim().toLowerCase();
    }

    private class SummaryBucket {
        private final Map<String, BigDecimal> sums = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Map<String, BigDecimal> maxValues = new LinkedHashMap<>();
        private final Map<String, BigDecimal> minValues = new LinkedHashMap<>();
        private int rowCount;

        void add(Map<String, String> row, List<SummaryMetric> metrics) {
            rowCount++;
            for (SummaryMetric metric : metrics) {
                String operation = normalizeOperation(metric.operation());
                if ("count".equals(operation)) {
                    continue;
                }
                BigDecimal value = parseNumber(row.get(metric.column()), metric.column());
                String alias = metricAlias(metric);
                sums.merge(alias, value, BigDecimal::add);
                counts.merge(alias, 1, Integer::sum);
                maxValues.merge(alias, value, BigDecimal::max);
                minValues.merge(alias, value, BigDecimal::min);
            }
        }

        List<String> values(
                List<SummaryMetric> metrics,
                java.util.function.Function<SummaryMetric, String> aliaser) {
            List<String> values = new ArrayList<>();
            for (SummaryMetric metric : metrics) {
                String operation = normalizeOperation(metric.operation());
                String alias = aliaser.apply(metric);
                values.add(
                        switch (operation) {
                            case "sum" -> formatDecimal(sums.getOrDefault(alias, BigDecimal.ZERO));
                            case "avg" ->
                                    formatDecimal(
                                            sums.getOrDefault(alias, BigDecimal.ZERO)
                                                    .divide(
                                                            BigDecimal.valueOf(
                                                                    counts.getOrDefault(alias, 1)),
                                                            2,
                                                            RoundingMode.HALF_UP));
                            case "max" ->
                                    formatDecimal(maxValues.getOrDefault(alias, BigDecimal.ZERO));
                            case "min" ->
                                    formatDecimal(minValues.getOrDefault(alias, BigDecimal.ZERO));
                            default -> String.valueOf(rowCount);
                        });
            }
            return values;
        }
    }

    private BigDecimal parseNumber(String value, String column) {
        try {
            return new BigDecimal(value == null ? "" : value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    column
                            + "\u5b57\u6bb5\u5305\u542b\u975e\u6570\u5b57\u5185\u5bb9\uff0c\u5df2\u8df3\u8fc7\u65e0\u6cd5\u8ba1\u7b97\u7684\u884c\u3002");
        }
    }

    private String formatDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record SummaryResult(List<List<String>> table, int rowCount) {}
}

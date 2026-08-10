package stirling.software.SPDF.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import stirling.software.SPDF.service.ExcelMergeService.JoinRule;
import stirling.software.SPDF.service.ExcelMergeService.MergeResult;
import stirling.software.SPDF.service.ExcelMergeService.MergeRules;
import stirling.software.SPDF.service.ExcelMergeService.MergeSourceData;

@Service
public class ExcelJoinMergeService {

    public MergeResult merge(List<MergeSourceData> sources, MergeRules rules) {
        if (sources.size() != 2 || rules.join() == null) {
            throw new IllegalArgumentException(
                    "\u5f53\u524d\u7248\u672c join \u53ea\u652f\u6301\u4e24\u4e2a\u6570\u636e\u6e90\u3002");
        }
        JoinRule join = rules.join();
        MergeSourceData left = sources.get(join.leftSourceIndex());
        MergeSourceData right = sources.get(join.rightSourceIndex());
        validateJoinKey(left, join.leftKey());
        validateJoinKey(right, join.rightKey());

        Map<String, List<Map<String, String>>> rightIndex = new LinkedHashMap<>();
        for (Map<String, String> rightRow : right.rows()) {
            String key = rightRow.getOrDefault(join.rightKey(), "");
            if (!key.isBlank()) {
                rightIndex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rightRow);
            }
        }

        List<String> outputColumns = rules.selectedColumns();
        if (outputColumns == null || outputColumns.isEmpty()) {
            outputColumns = mergedHeaders(left.headers(), right.headers());
        }

        List<Map<String, String>> outputRows = new ArrayList<>();
        for (Map<String, String> leftRow : left.rows()) {
            String key = leftRow.getOrDefault(join.leftKey(), "");
            List<Map<String, String>> matches = rightIndex.get(key);
            if (matches == null || matches.isEmpty()) {
                if ("left".equalsIgnoreCase(join.joinType())) {
                    outputRows.add(mergeRows(leftRow, Map.of(), outputColumns));
                }
                continue;
            }
            for (Map<String, String> rightRow : matches) {
                outputRows.add(mergeRows(leftRow, rightRow, outputColumns));
            }
        }

        if (outputRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "\u5408\u5e76\u5b8c\u6210\uff0c\u4f46\u6ca1\u6709\u751f\u6210\u6709\u6548\u6570\u636e\u3002");
        }

        List<List<String>> table = new ArrayList<>();
        table.add(outputColumns);
        for (Map<String, String> row : outputRows) {
            table.add(outputColumns.stream().map(column -> row.getOrDefault(column, "")).toList());
        }
        return new MergeResult(
                table,
                sources.size(),
                left.rows().size() + right.rows().size(),
                0,
                outputRows.size(),
                outputColumns);
    }

    private void validateJoinKey(MergeSourceData source, String key) {
        if (key == null || key.isBlank() || !source.headers().contains(key)) {
            throw new IllegalArgumentException(
                    "\u672a\u627e\u5230\u5173\u8054\u5b57\u6bb5\u201c"
                            + key
                            + "\u201d\uff0c\u8bf7\u68c0\u67e5\u4e24\u4e2a\u8868\u662f\u5426\u90fd\u5305\u542b\u8be5\u5b57\u6bb5\u3002");
        }
    }

    private List<String> mergedHeaders(List<String> leftHeaders, List<String> rightHeaders) {
        List<String> headers = new ArrayList<>(leftHeaders);
        for (String header : rightHeaders) {
            if (!headers.contains(header)) {
                headers.add(header);
            }
        }
        return headers;
    }

    private Map<String, String> mergeRows(
            Map<String, String> leftRow, Map<String, String> rightRow, List<String> outputColumns) {
        Map<String, String> output = new LinkedHashMap<>();
        for (String outputColumn : outputColumns) {
            output.put(
                    outputColumn,
                    leftRow.getOrDefault(outputColumn, rightRow.getOrDefault(outputColumn, "")));
        }
        return output;
    }
}

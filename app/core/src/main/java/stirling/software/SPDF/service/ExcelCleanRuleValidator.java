package stirling.software.SPDF.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class ExcelCleanRuleValidator {

    private static final Set<String> OPERATIONS =
            Set.of(
                    "normalize_number",
                    "normalize_date",
                    "normalize_text",
                    "normalize_phone",
                    "uppercase",
                    "lowercase",
                    "remove_spaces",
                    "replace");
    private static final Set<String> POLICIES = Set.of("keep", "keep_and_mark", "clear");
    private static final Set<String> DATE_FORMATS = Set.of("yyyy-MM-dd");

    public ExcelDataCleanService.CleanRules validate(
            ExcelDataCleanService.CleanRules rules, List<String> headers) {
        if (rules == null || !"clean".equalsIgnoreCase(rules.mode())) {
            throw new IllegalArgumentException("\u6e05\u6d17\u89c4\u5219\u683c\u5f0f\u9519\u8bef");
        }
        String policy =
                rules.invalidValuePolicy() == null || rules.invalidValuePolicy().isBlank()
                        ? "keep_and_mark"
                        : rules.invalidValuePolicy();
        if (!POLICIES.contains(policy)) {
            throw new IllegalArgumentException(
                    "\u5f02\u5e38\u503c\u5904\u7406\u7b56\u7565\u4e0d\u652f\u6301");
        }

        if (rules.deduplicate() != null && rules.deduplicate().columns() != null) {
            for (String column : rules.deduplicate().columns()) {
                validateColumn(column, headers);
            }
        }
        for (ExcelDataCleanService.ColumnRule rule : safeList(rules.columnRules())) {
            validateColumn(rule.column(), headers);
            if (!OPERATIONS.contains(rule.operation())) {
                throw new IllegalArgumentException(
                        "\u4e0d\u652f\u6301\u7684\u6e05\u6d17\u64cd\u4f5c\uff1a"
                                + rule.operation());
            }
            if ("normalize_date".equals(rule.operation())) {
                Object outputFormat = safeOptions(rule.options()).get("outputFormat");
                if (outputFormat != null
                        && !String.valueOf(outputFormat).isBlank()
                        && !DATE_FORMATS.contains(String.valueOf(outputFormat))) {
                    throw new IllegalArgumentException(
                            "\u4e0d\u652f\u6301\u7684\u65e5\u671f\u8f93\u51fa\u683c\u5f0f");
                }
            }
        }
        for (ExcelDataCleanService.ValueMapping mapping : safeList(rules.valueMappings())) {
            validateColumn(mapping.column(), headers);
        }
        return new ExcelDataCleanService.CleanRules(
                "clean",
                rules.removeEmptyRows(),
                rules.deduplicate(),
                rules.trimText(),
                rules.normalizeFullWidth(),
                safeList(rules.columnRules()),
                safeList(rules.valueMappings()),
                policy);
    }

    private void validateColumn(String column, List<String> headers) {
        if (column == null || column.isBlank() || !headers.contains(column)) {
            throw new IllegalArgumentException(
                    "\u9009\u62e9\u7684\u5217\u4e0d\u5b58\u5728\uff1a" + column);
        }
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private Map<String, Object> safeOptions(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}

package stirling.software.SPDF.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractFilter;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractRequest;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractSort;

@Service
public class ExcelRequirementParseService {

    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");

    public ParsedRequirement parse(ParseRequirementRequest request) {
        if (request.requirement() == null || request.requirement().isBlank()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u8f93\u5165\u8981\u63d0\u53d6\u7684\u9700\u6c42");
        }
        if (request.headers() == null || request.headers().isEmpty()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u4e0a\u4f20 Excel \u5e76\u8bc6\u522b\u8868\u5934");
        }

        String text = request.requirement().trim();
        List<String> headers = request.headers();
        List<String> selectedColumns = parseSelectedColumns(text, headers);
        List<SmartExtractFilter> filters = parseFilters(text, headers);
        boolean removeEmptyRows =
                containsAny(
                                text,
                                "\u5220\u9664\u7a7a\u884c",
                                "\u53bb\u7a7a\u884c",
                                "\u79fb\u9664\u7a7a\u884c")
                        || Boolean.TRUE.equals(request.defaultRemoveEmptyRows());
        boolean deduplicate =
                containsAny(
                                text,
                                "\u53bb\u91cd",
                                "\u5220\u9664\u91cd\u590d",
                                "\u53bb\u9664\u91cd\u590d")
                        || Boolean.TRUE.equals(request.defaultDeduplicate());
        SmartExtractSort sort = parseSort(text, headers);
        String groupByColumn = parseGroupBy(text, headers);

        SmartExtractRequest parsedRequest =
                new SmartExtractRequest(
                        "extract",
                        request.fileToken(),
                        request.sheetName(),
                        selectedColumns,
                        filters,
                        removeEmptyRows,
                        deduplicate,
                        sort,
                        groupByColumn,
                        List.of(),
                        List.of());

        return new ParsedRequirement(
                true,
                "\u9700\u6c42\u5df2\u89e3\u6790\uff0c\u8bf7\u786e\u8ba4\u89c4\u5219\u540e\u751f\u6210 Excel",
                parsedRequest);
    }

    private List<String> parseSelectedColumns(String text, List<String> headers) {
        for (String clause : clauses(text)) {
            if (containsAny(
                    clause,
                    "\u4fdd\u7559",
                    "\u53ea\u4fdd\u7559",
                    "\u63d0\u53d6\u5b57\u6bb5",
                    "\u4fdd\u7559\u5b57\u6bb5",
                    "\u9009\u62e9\u5b57\u6bb5")) {
                List<String> selected = headersInText(clause, headers);
                if (!selected.isEmpty()) {
                    return selected;
                }
            }
        }
        return headers;
    }

    private List<SmartExtractFilter> parseFilters(String text, List<String> headers) {
        List<SmartExtractFilter> filters = new ArrayList<>();
        for (String header : headers) {
            filters.addAll(parseNumberFilters(text, header));
            filters.addAll(parseDateFilters(text, header));
            parseTextFilter(text, header).ifPresent(filters::add);
        }
        return filters;
    }

    private List<SmartExtractFilter> parseNumberFilters(String text, String header) {
        List<SmartExtractFilter> filters = new ArrayList<>();
        Pattern pattern =
                Pattern.compile(
                        Pattern.quote(header)
                                + "\\s*(\u5927\u4e8e\u7b49\u4e8e|\u4e0d\u5c0f\u4e8e|>=|\u5927\u4e8e|>|\u5c0f\u4e8e\u7b49\u4e8e|\u4e0d\u5927\u4e8e|<=|\u5c0f\u4e8e|<|\u7b49\u4e8e|==|=)\\s*"
                                + NUMBER_PATTERN.pattern());
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            filters.add(
                    new SmartExtractFilter(
                            header,
                            toNumberOperator(matcher.group(1)),
                            matcher.group(2).replace(",", ""),
                            "number",
                            null,
                            null));
        }
        return filters;
    }

    private List<SmartExtractFilter> parseDateFilters(String text, String header) {
        Pattern rangePattern =
                Pattern.compile(
                        Pattern.quote(header)
                                + "\\s*(?:\u5728|\u4ece)?\\s*"
                                + DATE_PATTERN.pattern()
                                + "\\s*(?:\u5230|\u81f3|~|\u2014|-|to)\\s*"
                                + DATE_PATTERN.pattern(),
                        Pattern.CASE_INSENSITIVE);
        Matcher rangeMatcher = rangePattern.matcher(text);
        if (rangeMatcher.find()) {
            return List.of(
                    new SmartExtractFilter(
                            header,
                            "between",
                            null,
                            "date",
                            rangeMatcher.group(1),
                            rangeMatcher.group(2)));
        }

        Pattern singlePattern =
                Pattern.compile(
                        Pattern.quote(header)
                                + "\\s*(?:\u5728|\u4e3a|\u7b49\u4e8e|=)?\\s*"
                                + DATE_PATTERN.pattern());
        Matcher singleMatcher = singlePattern.matcher(text);
        if (singleMatcher.find()) {
            return List.of(
                    new SmartExtractFilter(
                            header, "=", singleMatcher.group(1), "date", null, null));
        }
        return List.of();
    }

    private java.util.Optional<SmartExtractFilter> parseTextFilter(String text, String header) {
        Pattern pattern =
                Pattern.compile(
                        Pattern.quote(header)
                                + "\\s*(?:\u5305\u542b|\u542b|\u7b49\u4e8e|=)\\s*([^,，;；。\\n\\r]+)");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        String value = cleanValue(matcher.group(1), header);
        if (value.isBlank()
                || NUMBER_PATTERN.matcher(value).matches()
                || DATE_PATTERN.matcher(value).matches()) {
            return java.util.Optional.empty();
        }
        String operator =
                matcher.group(0).contains("\u7b49\u4e8e") || matcher.group(0).contains("=")
                        ? "="
                        : "contains";
        return java.util.Optional.of(
                new SmartExtractFilter(header, operator, value, "text", null, null));
    }

    private SmartExtractSort parseSort(String text, List<String> headers) {
        for (String clause : clauses(text)) {
            for (String header : headers) {
                if (!clause.contains(header)) {
                    continue;
                }
                if (containsAny(clause, "\u964d\u5e8f", "desc", "\u4ece\u5927\u5230\u5c0f")) {
                    return new SmartExtractSort(header, "desc");
                }
                if (containsAny(clause, "\u5347\u5e8f", "asc", "\u4ece\u5c0f\u5230\u5927")) {
                    return new SmartExtractSort(header, "asc");
                }
            }
        }
        return null;
    }

    private String parseGroupBy(String text, List<String> headers) {
        if (!containsAny(
                text, "\u5206\u7ec4", "\u62c6\u5206sheet", "\u591a\u4e2asheet", "\u5206sheet")) {
            return null;
        }
        for (String clause : clauses(text)) {
            if (!containsAny(clause, "\u5206\u7ec4", "\u62c6\u5206", "sheet")) {
                continue;
            }
            for (String header : headers) {
                if (clause.contains(header)) {
                    return header;
                }
            }
        }
        return null;
    }

    private List<String> headersInText(String text, List<String> headers) {
        List<String> selected = new ArrayList<>();
        for (String header : headers) {
            if (text.contains(header)) {
                selected.add(header);
            }
        }
        return selected;
    }

    private String toNumberOperator(String operator) {
        return switch (operator) {
            case "\u5927\u4e8e\u7b49\u4e8e", "\u4e0d\u5c0f\u4e8e", ">=" -> ">=";
            case "\u5927\u4e8e", ">" -> ">";
            case "\u5c0f\u4e8e\u7b49\u4e8e", "\u4e0d\u5927\u4e8e", "<=" -> "<=";
            case "\u5c0f\u4e8e", "<" -> "<";
            default -> "=";
        };
    }

    private boolean containsNear(String text, String left, String right) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerLeft = left.toLowerCase(Locale.ROOT);
        String lowerRight = right.toLowerCase(Locale.ROOT);
        int leftIndex = lowerText.indexOf(lowerLeft);
        while (leftIndex >= 0) {
            int rightIndex = lowerText.indexOf(lowerRight);
            while (rightIndex >= 0) {
                if (Math.abs(leftIndex - rightIndex) <= 16) {
                    return true;
                }
                rightIndex = lowerText.indexOf(lowerRight, rightIndex + lowerRight.length());
            }
            leftIndex = lowerText.indexOf(lowerLeft, leftIndex + lowerLeft.length());
        }
        return false;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> clauses(String text) {
        return List.of(text.split("[,，;；。\\n\\r]+"));
    }

    private String cleanValue(String value, String header) {
        String cleaned = value.trim();
        for (String keyword :
                List.of(
                        "\u5e76",
                        "\u4e14",
                        "\u540c\u65f6",
                        "\u6309",
                        "\u6392\u5e8f",
                        "\u5206\u7ec4",
                        "\u53bb\u91cd",
                        "\u5220\u9664\u7a7a\u884c")) {
            int index = cleaned.indexOf(keyword);
            if (index > 0) {
                cleaned = cleaned.substring(0, index);
            }
        }
        return cleaned.replace(header, "").trim();
    }

    public record ParseRequirementRequest(
            String fileToken,
            String sheetName,
            List<String> headers,
            String requirement,
            Boolean defaultRemoveEmptyRows,
            Boolean defaultDeduplicate) {}

    public record ParsedRequirement(boolean success, String message, SmartExtractRequest request) {}
}

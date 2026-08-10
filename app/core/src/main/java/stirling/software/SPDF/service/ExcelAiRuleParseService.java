package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractFilter;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractRequest;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractSort;
import stirling.software.SPDF.service.ExcelSmartExtractService.SummaryMetric;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ExcelAiRuleParseService {

    private static final Set<String> ALLOWED_OPERATORS =
            Set.of("contains", "equals", ">", ">=", "<", "<=", "=", "between");
    private static final Set<String> ALLOWED_TYPES = Set.of("text", "number", "date");
    private static final Set<String> ALLOWED_DIRECTIONS = Set.of("asc", "desc");
    private static final Set<String> ALLOWED_MODES = Set.of("extract", "summary");
    private static final Set<String> ALLOWED_SUMMARY_OPERATIONS =
            Set.of("sum", "count", "avg", "max", "min");
    private static final String AI_DISABLED_MESSAGE =
            "\u0041\u0049\u89e3\u6790\u672a\u542f\u7528\uff0c\u8bf7\u914d\u7f6e\u0020\u0041\u0049\u0020\u670d\u52a1\u3002";
    private static final String AI_FAILED_MESSAGE =
            "\u0041\u0049\u89e3\u6790\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u0041\u0049\u670d\u52a1\u5730\u5740\u3001\u0041\u0050\u0049\u0020\u004b\u0065\u0079\u6216\u6a21\u578b\u540d\u79f0\u3002";

    private final ExcelAiConfigurationService configurationService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExcelFieldMatchService excelFieldMatchService;

    public ExcelAiRuleParseService(
            ExcelAiConfigurationService configurationService,
            ObjectMapper objectMapper,
            ExcelFieldMatchService excelFieldMatchService) {
        this.configurationService = configurationService;
        this.objectMapper = objectMapper;
        this.excelFieldMatchService = excelFieldMatchService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AiRuleParseResult parse(AiRuleParseRequest request) {
        validateRequest(request);
        if (!isAvailable()) {
            return AiRuleParseResult.failure(AI_DISABLED_MESSAGE, null, List.of());
        }

        try {
            String content = callOpenAiCompatible(request);
            SanitizedAiRules sanitized = sanitizeRules(parseRulesJson(content), request.headers());
            AiRules rules = sanitized.rules();
            if (!sanitized.hasMatchedRule()) {
                return AiRuleParseResult.failure(
                        "\u0041\u0049\u672a\u80fd\u51c6\u786e\u5339\u914d\u5b57\u6bb5\uff0c\u8bf7\u624b\u52a8\u9009\u62e9\u5b57\u6bb5\u540e\u751f\u6210\u0045\u0078\u0063\u0065\u006c\u3002",
                        rules,
                        sanitized.warnings());
            }
            return AiRuleParseResult.success(
                    "\u0041\u0049\u89e3\u6790\u6210\u529f",
                    rules.toSmartExtractRequest(request.fileToken(), request.sheetName()),
                    rules,
                    sanitized.warnings());
        } catch (Exception e) {
            log.warn(
                    "AI Excel rule parsing failed. baseUrlConfigured={}, model={}",
                    isBaseUrlConfigured(),
                    configurationService.current().model(),
                    e);
            return AiRuleParseResult.failure(AI_FAILED_MESSAGE, null, List.of());
        }
    }

    public AiStatus status() {
        var configuration = configurationService.current();
        return new AiStatus(
                configuration.available(),
                !configuration.baseUrl().isBlank(),
                !configuration.apiKey().isBlank(),
                configuration.model());
    }

    private boolean isAvailable() {
        return configurationService.current().available();
    }

    private boolean isBaseUrlConfigured() {
        return !configurationService.current().baseUrl().isBlank();
    }

    private boolean isApiKeyConfigured() {
        return !configurationService.current().apiKey().isBlank();
    }

    private void validateRequest(AiRuleParseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(AI_FAILED_MESSAGE);
        }
        if (request.userRequirement() == null || request.userRequirement().isBlank()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u8f93\u5165\u0041\u0049\u63d0\u53d6\u9700\u6c42\u3002");
        }
        if (request.headers() == null || request.headers().isEmpty()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u4e0a\u4f20\u0045\u0078\u0063\u0065\u006c\u5e76\u8bc6\u522b\u8868\u5934\u3002");
        }
        if (request.previewData() == null || request.previewData().isEmpty()) {
            throw new IllegalArgumentException(
                    "\u9884\u89c8\u6570\u636e\u4e3a\u7a7a\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u6587\u4ef6\u3002");
        }
        if (request.fileToken() == null || request.fileToken().isBlank()) {
            throw new IllegalArgumentException(
                    "\u6587\u4ef6\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20\u3002");
        }
    }

    private String callOpenAiCompatible(AiRuleParseRequest request)
            throws IOException, InterruptedException {
        var configuration = configurationService.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configuration.model());
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", userPrompt(request))));
        body.put("temperature", 0.1);
        applyProviderCompatibility(body, configuration);

        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(chatCompletionsUrl()))
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                        .header("Authorization", "Bearer " + configuration.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(body),
                                        StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response =
                httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI service returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode content = choice.path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            content = choice.path("text");
        }
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IOException("AI response content is empty");
        }
        return content.asText();
    }

    private String chatCompletionsUrl() {
        return buildChatCompletionsUrl(configurationService.current().baseUrl());
    }

    static String buildChatCompletionsUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    static void applyProviderCompatibility(
            Map<String, Object> body, ExcelAiConfigurationService.AiConfiguration configuration) {
        if (isDeepSeek(configuration)) {
            body.put("thinking", Map.of("type", "disabled"));
        }
    }

    private static boolean isDeepSeek(ExcelAiConfigurationService.AiConfiguration configuration) {
        return configuration.baseUrl().toLowerCase(Locale.ROOT).contains("api.deepseek.com")
                || configuration.model().toLowerCase(Locale.ROOT).startsWith("deepseek");
    }

    private String systemPrompt() {
        return String.join(
                "\n",
                "\u4f60\u662f\u4e00\u4e2a Excel \u6570\u636e\u5904\u7406\u89c4\u5219\u89e3\u6790\u5668\u3002",
                "\u4f60\u9700\u8981\u5224\u65ad\u7528\u6237\u9700\u6c42\u662f\u201c\u63d0\u53d6\u660e\u7ec6\u201d\u8fd8\u662f\u201c\u6c47\u603b\u7edf\u8ba1\u201d\u3002",
                "\u5982\u679c\u662f\u63d0\u53d6\u660e\u7ec6\uff0c\u8fd4\u56de mode = extract\u3002",
                "\u5982\u679c\u662f\u6c47\u603b\u7edf\u8ba1\uff0c\u8fd4\u56de mode = summary\u3002",
                "\u51fa\u73b0\u6c47\u603b\u3001\u7edf\u8ba1\u3001\u5408\u8ba1\u3001\u6c42\u548c\u3001\u603b\u91d1\u989d\u3001\u5e73\u5747\u3001\u6700\u5927\u3001\u6700\u5c0f\u3001\u6309\u90e8\u95e8\u7edf\u8ba1\u3001\u6309\u6708\u4efd\u7edf\u8ba1\u3001\u6bcf\u4e2a\u4eba\u7684\u91d1\u989d\u3001\u6bcf\u4e2a\u9879\u76ee\u7684\u8d39\u7528\u65f6\uff0cmode \u5e94\u4e3a summary\u3002",
                "\u6240\u6709\u6700\u7ec8\u7528\u4e8e selectedColumns\u3001filters.column\u3001sort.column\u3001groupByColumn \u7684\u5b57\u6bb5\uff0c\u5fc5\u987b\u6765\u81ea headers\u3002",
                "\u5f53 mode=summary \u65f6\uff0cgroupBy\u3001metrics.column\u3001filters.column \u5fc5\u987b\u6765\u81ea headers\uff0cmetrics.column \u53ef\u4ee5\u662f * \u4ee3\u8868\u8ba1\u6570\u3002",
                "\u5f53 mode=summary \u65f6\uff0coperation \u53ea\u80fd\u662f sum, count, avg, max, min\u3002",
                "\u5982\u679c\u7528\u6237\u8bf4\u7684\u5b57\u6bb5\u548c headers \u4e0d\u5b8c\u5168\u4e00\u81f4\uff0c\u9700\u8981\u6839\u636e\u8bed\u4e49\u5339\u914d\u6700\u63a5\u8fd1\u7684\u771f\u5b9e\u8868\u5934\u3002",
                "\u9700\u8981\u628a\u5b57\u6bb5\u5339\u914d\u8fc7\u7a0b\u5199\u5165 fieldMappings\u3002",
                "\u5982\u679c\u65e0\u6cd5\u786e\u5b9a\u5b57\u6bb5\uff0c\u8bf7\u4e0d\u8981\u7f16\u9020\u5b57\u6bb5\u3002",
                "\u4e0d\u8981\u89e3\u91ca\u3002",
                "\u4e0d\u8981\u8f93\u51fa Markdown\u3002",
                "\u4e0d\u8981\u8f93\u51fa\u4ee3\u7801\u5757\u3002",
                "\u53ea\u8f93\u51fa JSON\u3002",
                "\u5141\u8bb8\u7684 operator\uff1acontains, equals, >, >=, <, <=, between\u3002",
                "\u5141\u8bb8\u7684 type\uff1atext, number, date\u3002",
                "\u5141\u8bb8\u7684 sort.direction\uff1aasc, desc\u3002",
                "\u5982\u679c\u7528\u6237\u6ca1\u6709\u6307\u5b9a\u4fdd\u7559\u5b57\u6bb5\uff0cselectedColumns \u8fd4\u56de\u5168\u90e8 headers\u3002",
                "\u5982\u679c\u7528\u6237\u6ca1\u6709\u6307\u5b9a\u6392\u5e8f\uff0csort \u8fd4\u56de null\u3002",
                "\u5982\u679c\u7528\u6237\u6ca1\u6709\u6307\u5b9a\u5206\u7ec4\uff0cgroupByColumn \u8fd4\u56de null\u3002");
    }

    private String userPrompt(AiRuleParseRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headers", request.headers());
        payload.put("previewData", request.previewData().stream().limit(20).toList());
        payload.put("userRequirement", request.userRequirement());
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("mode", "extract");
        outputSchema.put("selectedColumns", List.of());
        outputSchema.put(
                "filters", List.of(Map.of("column", "", "operator", "", "value", "", "type", "")));
        outputSchema.put("removeEmptyRows", true);
        outputSchema.put("deduplicate", false);
        outputSchema.put("sort", Map.of("column", "", "direction", ""));
        outputSchema.put("groupByColumn", null);
        outputSchema.put("groupBy", List.of());
        outputSchema.put("metrics", List.of(Map.of("column", "", "operation", "sum", "alias", "")));
        outputSchema.put(
                "fieldMappings",
                List.of(Map.of("userField", "", "matchedHeader", "", "confidence", 0.0)));
        payload.put("outputSchema", outputSchema);
        return objectMapper.writeValueAsString(payload);
    }

    private AiRules parseRulesJson(String content) throws IOException {
        String json = extractJsonObject(content);
        Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
        return AiRules.fromRaw(raw);
    }

    private String extractJsonObject(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed =
                    trimmed.replaceFirst("^```(?:json)?\\s*", "")
                            .replaceFirst("\\s*```$", "")
                            .trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI response is not JSON");
        }
        return trimmed.substring(start, end + 1);
    }

    private SanitizedAiRules sanitizeRules(AiRules rules, List<String> headers) {
        List<String> warnings = new ArrayList<>();
        Map<String, AiFieldMapping> mappings = new LinkedHashMap<>();

        for (AiFieldMapping mapping : rules.fieldMappings()) {
            addMapping(mapping, headers, mappings, warnings);
        }

        String mode = normalizeMode(rules.mode());

        List<String> selectedColumns =
                rules.selectedColumns().stream()
                        .map(column -> matchColumn(column, headers, mappings, warnings))
                        .filter(column -> column != null && !column.isBlank())
                        .distinct()
                        .toList();
        boolean hasMatchedRule = "extract".equals(mode) && !selectedColumns.isEmpty();
        if (selectedColumns.isEmpty()) {
            selectedColumns = headers;
        }

        List<SmartExtractFilter> filters = new ArrayList<>();
        for (SmartExtractFilter filter : rules.filters()) {
            String matchedColumn = matchColumn(filter.column(), headers, mappings, warnings);
            if (matchedColumn == null || matchedColumn.isBlank()) {
                continue;
            }
            String operator = normalizeOperator(filter.operator());
            if (!ALLOWED_OPERATORS.contains(operator)) {
                continue;
            }
            String type = normalizeType(filter.type());
            filters.add(
                    new SmartExtractFilter(
                            matchedColumn,
                            operator,
                            filter.value() == null ? "" : filter.value(),
                            type,
                            filter.startDate(),
                            filter.endDate()));
        }
        hasMatchedRule = hasMatchedRule || !filters.isEmpty();

        String groupByColumn = matchColumn(rules.groupByColumn(), headers, mappings, warnings);
        hasMatchedRule = hasMatchedRule || groupByColumn != null;

        List<String> summaryGroupBy = new ArrayList<>();
        List<SummaryMetric> summaryMetrics = new ArrayList<>();
        if ("summary".equals(mode)) {
            for (String group : rules.groupBy()) {
                if ("\u6708\u4efd".equals(group) || "month".equalsIgnoreCase(group)) {
                    summaryGroupBy.add(group);
                    continue;
                }
                String matchedGroup = matchColumn(group, headers, mappings, warnings);
                if (matchedGroup != null && !matchedGroup.isBlank()) {
                    summaryGroupBy.add(matchedGroup);
                }
            }
            for (SummaryMetric metric : rules.metrics()) {
                String operation = normalizeSummaryOperation(metric.operation());
                if (!ALLOWED_SUMMARY_OPERATIONS.contains(operation)) {
                    continue;
                }
                String matchedColumn = "*";
                if (!"count".equals(operation) || !"*".equals(metric.column())) {
                    matchedColumn = matchColumn(metric.column(), headers, mappings, warnings);
                }
                if (matchedColumn == null || matchedColumn.isBlank()) {
                    continue;
                }
                summaryMetrics.add(
                        new SummaryMetric(
                                matchedColumn, operation, summaryAlias(metric, operation)));
            }
            hasMatchedRule = !summaryMetrics.isEmpty();
        }

        SmartExtractSort sort = sanitizeSort(rules.sort(), headers, mappings, warnings);
        if ("summary".equals(mode) && sort != null) {
            List<String> outputColumns = new ArrayList<>(summaryGroupBy);
            outputColumns.addAll(summaryMetrics.stream().map(SummaryMetric::alias).toList());
            if (!outputColumns.contains(sort.column())) {
                sort = sanitizeSummarySort(rules.sort(), summaryMetrics, warnings);
            }
        }
        if ("extract".equals(mode)) {
            hasMatchedRule = hasMatchedRule || sort != null;
        }

        AiRules sanitizedRules =
                new AiRules(
                        mode,
                        selectedColumns,
                        filters,
                        rules.removeEmptyRows() == null ? Boolean.TRUE : rules.removeEmptyRows(),
                        rules.deduplicate() == null ? Boolean.FALSE : rules.deduplicate(),
                        sort,
                        groupByColumn,
                        summaryGroupBy,
                        summaryMetrics,
                        new ArrayList<>(mappings.values()));
        return new SanitizedAiRules(sanitizedRules, warnings, hasMatchedRule);
    }

    private SmartExtractSort sanitizeSort(
            SmartExtractSort rawSort,
            List<String> headers,
            Map<String, AiFieldMapping> mappings,
            List<String> warnings) {
        if (rawSort == null
                || rawSort.direction() == null
                || !ALLOWED_DIRECTIONS.contains(rawSort.direction().toLowerCase(Locale.ROOT))) {
            return null;
        }
        String matchedColumn = matchColumn(rawSort.column(), headers, mappings, warnings);
        if (matchedColumn == null || matchedColumn.isBlank()) {
            return null;
        }
        return new SmartExtractSort(matchedColumn, rawSort.direction().toLowerCase(Locale.ROOT));
    }

    private SmartExtractSort sanitizeSummarySort(
            SmartExtractSort rawSort, List<SummaryMetric> metrics, List<String> warnings) {
        if (rawSort == null || rawSort.column() == null || rawSort.direction() == null) {
            return null;
        }
        String sortColumn = rawSort.column();
        for (SummaryMetric metric : metrics) {
            if (sortColumn.equals(metric.alias())
                    || sortColumn.equals(metric.column())
                    || sortColumn.contains(metric.column())) {
                return new SmartExtractSort(
                        metric.alias(), rawSort.direction().toLowerCase(Locale.ROOT));
            }
        }
        warnings.add(
                "\u6392\u5e8f\u5b57\u6bb5\u4e0d\u5728\u6c47\u603b\u7ed3\u679c\u4e2d\uff0c\u5df2\u5ffd\u7565\u3002");
        return null;
    }

    private void addMapping(
            AiFieldMapping mapping,
            List<String> headers,
            Map<String, AiFieldMapping> mappings,
            List<String> warnings) {
        if (mapping.userField() == null
                || mapping.userField().isBlank()
                || mapping.matchedHeader() == null
                || mapping.matchedHeader().isBlank()) {
            return;
        }
        if (!headers.contains(mapping.matchedHeader())) {
            excelFieldMatchService
                    .match(mapping.matchedHeader(), headers)
                    .ifPresent(
                            fieldMatch ->
                                    putMapping(
                                            mapping.userField(),
                                            fieldMatch.matchedHeader(),
                                            Math.min(mapping.confidence(), fieldMatch.confidence()),
                                            mappings,
                                            warnings));
            return;
        }
        putMapping(
                mapping.userField(),
                mapping.matchedHeader(),
                clampConfidence(mapping.confidence()),
                mappings,
                warnings);
    }

    private String matchColumn(
            String column,
            List<String> headers,
            Map<String, AiFieldMapping> mappings,
            List<String> warnings) {
        if (column == null || column.isBlank()) {
            return null;
        }
        if (headers.contains(column)) {
            return column;
        }
        if (mappings.containsKey(column)) {
            return mappings.get(column).matchedHeader();
        }
        return excelFieldMatchService
                .match(column, headers)
                .map(
                        fieldMatch -> {
                            putMapping(
                                    fieldMatch.userField(),
                                    fieldMatch.matchedHeader(),
                                    fieldMatch.confidence(),
                                    mappings,
                                    warnings);
                            return fieldMatch.matchedHeader();
                        })
                .orElse(null);
    }

    private void putMapping(
            String userField,
            String matchedHeader,
            double confidence,
            Map<String, AiFieldMapping> mappings,
            List<String> warnings) {
        double normalizedConfidence = clampConfidence(confidence);
        AiFieldMapping mapping = new AiFieldMapping(userField, matchedHeader, normalizedConfidence);
        mappings.put(userField, mapping);
        if (normalizedConfidence < 0.8) {
            warnings.add(
                    "\u5b57\u6bb5\u201c"
                            + userField
                            + "\u201d\u5339\u914d\u4e3a\u201c"
                            + matchedHeader
                            + "\u201d\uff0c\u7f6e\u4fe1\u5ea6\u8f83\u4f4e\uff0c\u8bf7\u786e\u8ba4\u3002");
        }
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0;
        }
        return Math.max(0, Math.min(1, confidence));
    }

    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "contains";
        }
        String normalized = operator.trim().toLowerCase(Locale.ROOT);
        return "==".equals(normalized) ? "=" : normalized;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "text";
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_TYPES.contains(normalized) ? normalized : "text";
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "extract" : mode.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_MODES.contains(normalized) ? normalized : "extract";
    }

    private String normalizeSummaryOperation(String operation) {
        return operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
    }

    private String summaryAlias(SummaryMetric metric, String operation) {
        if (metric.alias() != null && !metric.alias().isBlank()) {
            return metric.alias();
        }
        return switch (operation) {
            case "sum" -> "\u603b" + metric.column();
            case "avg" -> "\u5e73\u5747" + metric.column();
            case "max" -> "\u6700\u5927" + metric.column();
            case "min" -> "\u6700\u5c0f" + metric.column();
            default -> "\u6570\u91cf";
        };
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(ExcelAiRuleParseService::stringValue)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static List<SmartExtractFilter> filterList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<SmartExtractFilter> filters = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            filters.add(
                    new SmartExtractFilter(
                            stringValue(map.get("column")),
                            stringValue(map.get("operator")),
                            stringValue(map.get("value")),
                            stringValue(map.get("type")),
                            stringValue(map.get("startDate")),
                            stringValue(map.get("endDate"))));
        }
        return filters;
    }

    private static SmartExtractSort sortValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String column = stringValue(map.get("column"));
        String direction = stringValue(map.get("direction"));
        if (column.isBlank() || direction.isBlank()) {
            return null;
        }
        return new SmartExtractSort(column, direction);
    }

    private static List<SummaryMetric> metricList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<SummaryMetric> metrics = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            metrics.add(
                    new SummaryMetric(
                            stringValue(map.get("column")),
                            stringValue(map.get("operation")),
                            stringValue(map.get("alias"))));
        }
        return metrics;
    }

    private static List<AiFieldMapping> fieldMappingList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<AiFieldMapping> mappings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            mappings.add(
                    new AiFieldMapping(
                            stringValue(map.get("userField")),
                            stringValue(map.get("matchedHeader")),
                            doubleValue(map.get("confidence"))));
        }
        return mappings;
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record AiRuleParseRequest(
            String fileToken,
            String sheetName,
            List<String> headers,
            List<List<String>> previewData,
            String userRequirement) {}

    public record AiStatus(
            boolean enabled, boolean baseUrlConfigured, boolean apiKeyConfigured, String model) {}

    public record AiRuleParseResult(
            boolean success,
            String message,
            SmartExtractRequest request,
            AiRules rules,
            List<String> warnings) {

        public static AiRuleParseResult success(
                String message, SmartExtractRequest request, AiRules rules, List<String> warnings) {
            return new AiRuleParseResult(true, message, request, rules, warnings);
        }

        public static AiRuleParseResult failure(
                String message, AiRules rules, List<String> warnings) {
            return new AiRuleParseResult(false, message, null, rules, warnings);
        }
    }

    public record AiRules(
            String mode,
            List<String> selectedColumns,
            List<SmartExtractFilter> filters,
            Boolean removeEmptyRows,
            Boolean deduplicate,
            SmartExtractSort sort,
            String groupByColumn,
            List<String> groupBy,
            List<SummaryMetric> metrics,
            List<AiFieldMapping> fieldMappings) {

        static AiRules fromRaw(Map<String, Object> raw) {
            return new AiRules(
                    stringValue(raw.get("mode")).isBlank()
                            ? "extract"
                            : stringValue(raw.get("mode")),
                    stringList(raw.get("selectedColumns")),
                    filterList(raw.get("filters")),
                    booleanValue(raw.get("removeEmptyRows")),
                    booleanValue(raw.get("deduplicate")),
                    sortValue(raw.get("sort")),
                    stringValue(raw.get("groupByColumn")).isBlank()
                                    || "null"
                                            .equalsIgnoreCase(stringValue(raw.get("groupByColumn")))
                            ? null
                            : stringValue(raw.get("groupByColumn")),
                    stringList(raw.get("groupBy")),
                    metricList(raw.get("metrics")),
                    fieldMappingList(raw.get("fieldMappings")));
        }

        SmartExtractRequest toSmartExtractRequest(String fileToken, String sheetName) {
            return new SmartExtractRequest(
                    mode,
                    fileToken,
                    sheetName,
                    selectedColumns,
                    filters,
                    removeEmptyRows,
                    deduplicate,
                    sort,
                    groupByColumn,
                    groupBy,
                    metrics);
        }
    }

    public record AiFieldMapping(String userField, String matchedHeader, double confidence) {}

    private record SanitizedAiRules(AiRules rules, List<String> warnings, boolean hasMatchedRule) {}
}

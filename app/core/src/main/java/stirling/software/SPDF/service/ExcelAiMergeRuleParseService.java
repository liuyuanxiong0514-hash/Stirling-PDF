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

import stirling.software.SPDF.service.ExcelMergeService.JoinRule;
import stirling.software.SPDF.service.ExcelMergeService.MergeFieldMapping;
import stirling.software.SPDF.service.ExcelMergeService.MergeRules;
import stirling.software.SPDF.service.ExcelMergeService.MergeSourceRef;
import stirling.software.SPDF.service.ExcelSmartExtractService.SmartExtractSort;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ExcelAiMergeRuleParseService {

    private static final Set<String> ALLOWED_MERGE_TYPES =
            Set.of("append", "join", "multi_sheet_append");
    private static final String AI_DISABLED_MESSAGE =
            "\u0041\u0049\u89e3\u6790\u672a\u542f\u7528\uff0c\u8bf7\u914d\u7f6e\u0020\u0041\u0049\u0020\u670d\u52a1\u3002";
    private static final String AI_FAILED_MESSAGE =
            "\u0041\u0049\u5408\u5e76\u65b9\u6848\u89e3\u6790\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u0041\u0049\u670d\u52a1\u5730\u5740\u3001\u0041\u0050\u0049\u0020\u004b\u0065\u0079\u6216\u6a21\u578b\u540d\u79f0\u3002";

    private final ExcelAiConfigurationService configurationService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExcelAiMergeRuleParseService(
            ExcelAiConfigurationService configurationService, ObjectMapper objectMapper) {
        this.configurationService = configurationService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AiMergeParseResult parse(AiMergeParseRequest request) {
        validateRequest(request);
        if (!isAvailable()) {
            return AiMergeParseResult.failure(AI_DISABLED_MESSAGE, null, List.of());
        }
        try {
            String content = callOpenAiCompatible(request);
            MergeRules rules = sanitize(parseRulesJson(content), request.sources());
            return AiMergeParseResult.success(
                    "\u0041\u0049\u5408\u5e76\u65b9\u6848\u89e3\u6790\u6210\u529f",
                    rules,
                    lowConfidenceWarnings(rules));
        } catch (IllegalArgumentException e) {
            return AiMergeParseResult.failure(e.getMessage(), null, List.of());
        } catch (Exception e) {
            log.warn(
                    "AI Excel merge rule parsing failed. model={}",
                    configurationService.current().model(),
                    e);
            return AiMergeParseResult.failure(AI_FAILED_MESSAGE, null, List.of());
        }
    }

    private boolean isAvailable() {
        return configurationService.current().available();
    }

    private void validateRequest(AiMergeParseRequest request) {
        if (request == null
                || request.sources() == null
                || request.sources().isEmpty()
                || request.userRequirement() == null
                || request.userRequirement().isBlank()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u9009\u62e9\u6570\u636e\u6e90\u5e76\u8f93\u5165\u5408\u5e76\u9700\u6c42\u3002");
        }
    }

    private String callOpenAiCompatible(AiMergeParseRequest request)
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
        ExcelAiRuleParseService.applyProviderCompatibility(body, configuration);

        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        ExcelAiRuleParseService.buildChatCompletionsUrl(
                                                configuration.baseUrl())))
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

    private String systemPrompt() {
        return String.join(
                "\n",
                "\u4f60\u662f\u4e00\u4e2a Excel \u8868\u683c\u5408\u5e76\u89c4\u5219\u89e3\u6790\u5668\u3002",
                "\u4f60\u7684\u4efb\u52a1\u662f\u6839\u636e\u591a\u4e2a Excel \u6216 Sheet \u7684 headers\u3001\u5c11\u91cf\u9884\u89c8\u6570\u636e\u548c\u7528\u6237\u9700\u6c42\uff0c\u751f\u6210\u540e\u7aef\u53ef\u4ee5\u5b89\u5168\u6267\u884c\u7684\u5408\u5e76\u89c4\u5219 JSON\u3002",
                "\u4f60\u4e0d\u80fd\u76f4\u63a5\u751f\u6210 Excel\u3002",
                "\u4f60\u4e0d\u80fd\u8f93\u51fa\u4ee3\u7801\u3002",
                "\u4f60\u53ea\u80fd\u8fd4\u56de JSON\u3002",
                "\u5408\u5e76\u65b9\u5f0f\u53ea\u80fd\u662f append, join, multi_sheet_append\u3002",
                "append \u8868\u793a\u7ed3\u6784\u7c7b\u4f3c\u7684\u591a\u8868\u7eb5\u5411\u8ffd\u52a0\u3002",
                "join \u8868\u793a\u6839\u636e\u5173\u952e\u5b57\u6bb5\u5173\u8054\u4e24\u4e2a\u6570\u636e\u6e90\u3002",
                "multi_sheet_append \u8868\u793a\u540c\u4e00\u6587\u4ef6\u591a Sheet \u7eb5\u5411\u8ffd\u52a0\u3002",
                "\u5b57\u6bb5\u540d\u79f0\u4e0d\u4e00\u81f4\u65f6\uff0c\u9700\u8981\u5efa\u7acb fieldMappings\u3002",
                "\u6240\u6709 sourceFields \u5fc5\u987b\u771f\u5b9e\u5b58\u5728\u4e8e\u6570\u636e\u6e90 headers \u4e2d\u3002",
                "join \u7684 leftKey \u548c rightKey \u5fc5\u987b\u5206\u522b\u5b58\u5728\u4e8e\u5bf9\u5e94\u6570\u636e\u6e90\u3002",
                "\u4e0d\u8981\u89e3\u91ca\u3002\u4e0d\u8981\u8f93\u51fa Markdown\u3002\u53ea\u8f93\u51fa\u5408\u6cd5 JSON\u3002");
    }

    private String userPrompt(AiMergeParseRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sources", request.sources());
        payload.put("userRequirement", request.userRequirement());
        payload.put(
                "outputSchema",
                Map.of(
                        "mode",
                        "merge",
                        "mergeType",
                        "append",
                        "fieldMappings",
                        List.of(
                                Map.of(
                                        "targetField",
                                        "",
                                        "sourceFields",
                                        List.of(),
                                        "confidence",
                                        0.0)),
                        "selectedColumns",
                        List.of(),
                        "removeEmptyRows",
                        true,
                        "deduplicate",
                        false,
                        "deduplicateColumns",
                        List.of(),
                        "addSourceColumn",
                        false,
                        "sourceColumnName",
                        "\u6765\u6e90\u6587\u4ef6"));
        return objectMapper.writeValueAsString(payload);
    }

    private MergeRules parseRulesJson(String content) throws IOException {
        String json = extractJsonObject(content);
        Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
        return fromRaw(raw);
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

    private MergeRules sanitize(MergeRules rules, List<MergeSourceRef> sources) {
        String mergeType =
                rules.mergeType() == null ? "" : rules.mergeType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MERGE_TYPES.contains(mergeType)) {
            throw new IllegalArgumentException(
                    "\u0041\u0049\u65e0\u6cd5\u5224\u65ad\u5408\u5e76\u65b9\u5f0f");
        }
        List<String> allHeaders =
                sources.stream().flatMap(source -> source.headers().stream()).distinct().toList();
        List<MergeFieldMapping> mappings =
                rules.fieldMappings().stream()
                        .map(mapping -> sanitizeMapping(mapping, allHeaders))
                        .filter(mapping -> !mapping.sourceFields().isEmpty())
                        .toList();
        if ("append".equals(mergeType) && mappings.isEmpty()) {
            throw new IllegalArgumentException(
                    "\u65e0\u6cd5\u8bc6\u522b\u8fd9\u4e9b\u8868\u683c\u4e4b\u95f4\u7684\u5b57\u6bb5\u5173\u7cfb\uff0c\u8bf7\u624b\u52a8\u9009\u62e9\u5b57\u6bb5\u6620\u5c04\u3002");
        }
        return new MergeRules(
                "merge",
                mergeType,
                sources,
                mappings,
                rules.selectedColumns() == null
                        ? mappings.stream().map(MergeFieldMapping::targetField).toList()
                        : rules.selectedColumns(),
                rules.removeEmptyRows(),
                rules.deduplicate(),
                rules.deduplicateColumns(),
                rules.sort(),
                rules.join(),
                rules.addSourceColumn(),
                rules.sourceColumnName());
    }

    private MergeFieldMapping sanitizeMapping(MergeFieldMapping mapping, List<String> allHeaders) {
        List<String> sourceFields =
                mapping.sourceFields() == null
                        ? List.of()
                        : mapping.sourceFields().stream()
                                .filter(allHeaders::contains)
                                .distinct()
                                .toList();
        return new MergeFieldMapping(mapping.targetField(), sourceFields, mapping.confidence());
    }

    private List<String> lowConfidenceWarnings(MergeRules rules) {
        List<String> warnings = new ArrayList<>();
        for (MergeFieldMapping mapping : rules.fieldMappings()) {
            if (mapping.confidence() != null && mapping.confidence() < 0.8) {
                warnings.add(
                        "\u90e8\u5206\u5b57\u6bb5\u5339\u914d\u7f6e\u4fe1\u5ea6\u8f83\u4f4e\uff0c\u8bf7\u786e\u8ba4\u540e\u518d\u5408\u5e76\u3002");
                break;
            }
        }
        return warnings;
    }

    @SuppressWarnings("unchecked")
    private MergeRules fromRaw(Map<String, Object> raw) {
        return new MergeRules(
                stringValue(raw.get("mode")),
                stringValue(raw.get("mergeType")),
                List.of(),
                fieldMappings(raw.get("fieldMappings")),
                stringList(raw.get("selectedColumns")),
                booleanValue(raw.get("removeEmptyRows")),
                booleanValue(raw.get("deduplicate")),
                stringList(raw.get("deduplicateColumns")),
                sortValue(raw.get("sort")),
                joinValue(raw.get("join")),
                booleanValue(raw.get("addSourceColumn")),
                stringValue(raw.get("sourceColumnName")));
    }

    private List<MergeFieldMapping> fieldMappings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<MergeFieldMapping> mappings = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                mappings.add(
                        new MergeFieldMapping(
                                stringValue(map.get("targetField")),
                                stringList(map.get("sourceFields")),
                                doubleValue(map.get("confidence"))));
            }
        }
        return mappings;
    }

    private JoinRule joinValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new JoinRule(
                intValue(map.get("leftSourceIndex")),
                intValue(map.get("rightSourceIndex")),
                stringValue(map.get("leftKey")),
                stringValue(map.get("rightKey")),
                stringValue(map.get("joinType")).isBlank()
                        ? "left"
                        : stringValue(map.get("joinType")));
    }

    private SmartExtractSort sortValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String column = stringValue(map.get("column"));
        String direction = stringValue(map.get("direction"));
        return column.isBlank() || direction.isBlank()
                ? null
                : new SmartExtractSort(column, direction);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record AiMergeParseRequest(List<MergeSourceRef> sources, String userRequirement) {}

    public record AiMergeParseResult(
            boolean success, String message, MergeRules rules, List<String> warnings) {
        public static AiMergeParseResult success(
                String message, MergeRules rules, List<String> warnings) {
            return new AiMergeParseResult(true, message, rules, warnings);
        }

        public static AiMergeParseResult failure(
                String message, MergeRules rules, List<String> warnings) {
            return new AiMergeParseResult(false, message, rules, warnings);
        }
    }
}

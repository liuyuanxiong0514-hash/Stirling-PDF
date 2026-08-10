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
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.service.ExcelDataCleanService.CleanRules;
import stirling.software.SPDF.service.ExcelDataCleanService.ColumnRule;
import stirling.software.SPDF.service.ExcelDataCleanService.DeduplicateRule;
import stirling.software.SPDF.service.ExcelDataCleanService.ValueMapping;
import stirling.software.SPDF.service.ExcelDataQualityService.CleanIssues;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ExcelAiCleanRuleParseService {

    private static final String AI_DISABLED_MESSAGE =
            "\u0041\u0049\u89e3\u6790\u672a\u542f\u7528\uff0c\u8bf7\u914d\u7f6e\u0020\u0041\u0049\u0020\u670d\u52a1\u3002";
    private static final String AI_FAILED_MESSAGE =
            "\u0041\u0049\u6e05\u6d17\u65b9\u6848\u89e3\u6790\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u0020\u0041\u0049\u0020\u670d\u52a1\u5730\u5740\u3001\u0041\u0050\u0049\u0020\u004b\u0065\u0079\u0020\u6216\u6a21\u578b\u540d\u79f0\u3002";

    private final ExcelAiConfigurationService configurationService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExcelCleanRuleValidator excelCleanRuleValidator;

    public ExcelAiCleanRuleParseService(
            ExcelAiConfigurationService configurationService,
            ObjectMapper objectMapper,
            ExcelCleanRuleValidator excelCleanRuleValidator) {
        this.configurationService = configurationService;
        this.objectMapper = objectMapper;
        this.excelCleanRuleValidator = excelCleanRuleValidator;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AiCleanParseResult parse(AiCleanParseRequest request) {
        validateRequest(request);
        if (!isAvailable()) {
            return AiCleanParseResult.failure(AI_DISABLED_MESSAGE, null, List.of());
        }
        try {
            String content = callOpenAiCompatible(request);
            CleanRules rules =
                    excelCleanRuleValidator.validate(parseRulesJson(content), request.headers());
            return AiCleanParseResult.success(
                    "\u0041\u0049\u6e05\u6d17\u65b9\u6848\u89e3\u6790\u6210\u529f",
                    rules,
                    warnings(rules));
        } catch (IllegalArgumentException e) {
            return AiCleanParseResult.failure(e.getMessage(), null, List.of());
        } catch (Exception e) {
            log.warn(
                    "AI Excel clean rule parsing failed. model={}",
                    configurationService.current().model(),
                    e);
            return AiCleanParseResult.failure(AI_FAILED_MESSAGE, null, List.of());
        }
    }

    private boolean isAvailable() {
        return configurationService.current().available();
    }

    private void validateRequest(AiCleanParseRequest request) {
        if (request == null
                || request.headers() == null
                || request.headers().isEmpty()
                || request.userRequirement() == null
                || request.userRequirement().isBlank()) {
            throw new IllegalArgumentException(
                    "\u8bf7\u5148\u4e0a\u4f20 Excel \u5e76\u8f93\u5165\u6e05\u6d17\u9700\u6c42\u3002");
        }
    }

    private String callOpenAiCompatible(AiCleanParseRequest request)
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
                "\u4f60\u662f\u4e00\u4e2a Excel \u6570\u636e\u6e05\u6d17\u89c4\u5219\u89e3\u6790\u5668\u3002",
                "\u4f60\u53ea\u80fd\u628a\u7528\u6237\u7684\u81ea\u7136\u8bed\u8a00\u9700\u6c42\u8f6c\u6362\u4e3a\u540e\u7aef\u53ef\u6267\u884c\u7684 JSON \u6e05\u6d17\u89c4\u5219\u3002",
                "\u4f60\u4e0d\u80fd\u76f4\u63a5\u4fee\u6539 Excel\u3002",
                "\u4f60\u4e0d\u80fd\u76f4\u63a5\u751f\u6210 Excel\u3002",
                "\u4f60\u4e0d\u80fd\u8f93\u51fa\u4ee3\u7801\u6216\u516c\u5f0f\u3002",
                "\u6240\u6709 column \u5fc5\u987b\u6765\u81ea headers\u3002",
                "\u64cd\u4f5c\u53ea\u80fd\u4f7f\u7528 normalize_number, normalize_date, normalize_text, normalize_phone, uppercase, lowercase, remove_spaces, replace\u3002",
                "invalidValuePolicy \u53ea\u80fd\u662f keep, keep_and_mark, clear\uff0c\u9ed8\u8ba4 keep_and_mark\u3002",
                "\u5982\u679c\u9700\u8981\u6807\u51c6\u5316\u65e5\u671f\uff0coutputFormat \u53ea\u80fd\u662f yyyy-MM-dd\u3002",
                "\u4e0d\u8981\u89e3\u91ca\u3002\u4e0d\u8981\u8f93\u51fa Markdown\u3002\u4e0d\u8981\u8f93\u51fa\u4ee3\u7801\u5757\u3002\u53ea\u8f93\u51fa JSON\u3002");
    }

    private String userPrompt(AiCleanParseRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headers", request.headers());
        payload.put(
                "previewData",
                request.previewData() == null
                        ? List.of()
                        : request.previewData().stream().limit(20).toList());
        payload.put("qualityIssues", request.issues());
        payload.put("userRequirement", request.userRequirement());
        payload.put(
                "outputSchema",
                Map.of(
                        "mode",
                        "clean",
                        "removeEmptyRows",
                        true,
                        "deduplicate",
                        Map.of("enabled", false, "columns", List.of()),
                        "trimText",
                        true,
                        "normalizeFullWidth",
                        true,
                        "columnRules",
                        List.of(Map.of("column", "", "operation", "", "options", Map.of())),
                        "valueMappings",
                        List.of(Map.of("column", "", "mappings", Map.of())),
                        "invalidValuePolicy",
                        "keep_and_mark"));
        return objectMapper.writeValueAsString(payload);
    }

    private CleanRules parseRulesJson(String content) throws IOException {
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

    private CleanRules fromRaw(Map<String, Object> raw) {
        return new CleanRules(
                stringValue(raw.get("mode")).isBlank() ? "clean" : stringValue(raw.get("mode")),
                booleanValue(raw.get("removeEmptyRows")),
                deduplicate(raw.get("deduplicate")),
                booleanValue(raw.get("trimText")),
                booleanValue(raw.get("normalizeFullWidth")),
                columnRules(raw.get("columnRules")),
                valueMappings(raw.get("valueMappings")),
                stringValue(raw.get("invalidValuePolicy")));
    }

    private DeduplicateRule deduplicate(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new DeduplicateRule(false, List.of());
        }
        return new DeduplicateRule(
                booleanValue(map.get("enabled")), stringList(map.get("columns")));
    }

    private List<ColumnRule> columnRules(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<ColumnRule> rules = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rules.add(
                        new ColumnRule(
                                stringValue(map.get("column")),
                                stringValue(map.get("operation")),
                                objectMap(map.get("options"))));
            }
        }
        return rules;
    }

    private List<ValueMapping> valueMappings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<ValueMapping> mappings = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                mappings.add(
                        new ValueMapping(
                                stringValue(map.get("column")),
                                stringStringMap(map.get("mappings"))));
            }
        }
        return mappings;
    }

    private List<String> warnings(CleanRules rules) {
        List<String> warnings = new ArrayList<>();
        if ("keep_and_mark".equals(rules.invalidValuePolicy())) {
            warnings.add(
                    "\u65e0\u6cd5\u6807\u51c6\u5316\u7684\u503c\u5c06\u4fdd\u7559\u5e76\u5199\u5165\u5f02\u5e38 Sheet\u3002");
        }
        return warnings;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(stringValue(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private Map<String, String> stringStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(stringValue(entry.getKey()), stringValue(entry.getValue()));
        }
        return result;
    }

    public record AiCleanParseRequest(
            String fileToken,
            String sheetName,
            List<String> headers,
            CleanIssues issues,
            List<List<String>> previewData,
            String userRequirement) {}

    public record AiCleanParseResult(
            boolean success, String message, CleanRules rules, List<String> warnings) {
        public static AiCleanParseResult success(
                String message, CleanRules rules, List<String> warnings) {
            return new AiCleanParseResult(true, message, rules, warnings);
        }

        public static AiCleanParseResult failure(
                String message, CleanRules rules, List<String> warnings) {
            return new AiCleanParseResult(false, message, rules, warnings);
        }
    }
}

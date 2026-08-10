package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ExcelAiConfigurationService {

    private final boolean defaultEnabled;
    private final String defaultProvider;
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;
    private final long defaultTimeoutSeconds;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile RuntimeConfiguration runtimeConfiguration;

    public ExcelAiConfigurationService(
            @Value("${excel.ai.enabled:false}") boolean enabled,
            @Value("${excel.ai.provider:openai-compatible}") String provider,
            @Value("${excel.ai.base-url:}") String baseUrl,
            @Value("${excel.ai.api-key:}") String apiKey,
            @Value("${excel.ai.model:gpt-4o-mini}") String model,
            @Value("${excel.ai.timeout-seconds:30}") long timeoutSeconds,
            ObjectMapper objectMapper) {
        this.defaultEnabled = enabled;
        this.defaultProvider = normalize(provider);
        this.defaultBaseUrl = normalizeUrl(baseUrl);
        this.defaultApiKey = normalize(apiKey);
        this.defaultModel = normalize(model);
        this.defaultTimeoutSeconds = Math.max(1, timeoutSeconds);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @PostConstruct
    void logConfiguration() {
        AiConfiguration configuration = current();
        log.info("AI Excel service enabled: {}", configuration.available());
        log.info("AI base URL configured: {}", !configuration.baseUrl().isBlank());
        log.info(
                "AI model: {}",
                configuration.model().isBlank() ? "<empty>" : configuration.model());
    }

    public AiConfiguration current() {
        RuntimeConfiguration runtime = runtimeConfiguration;
        boolean enabled =
                environmentBoolean("EXCEL_AI_ENABLED")
                        .orElse(runtime == null ? defaultEnabled : runtime.enabled());
        String provider =
                effectiveString(
                        "EXCEL_AI_PROVIDER",
                        runtime == null ? null : runtime.provider(),
                        defaultProvider);
        String baseUrl =
                normalizeUrl(
                        effectiveString(
                                "EXCEL_AI_BASE_URL",
                                runtime == null ? null : runtime.baseUrl(),
                                defaultBaseUrl));
        String apiKey =
                effectiveString(
                        "EXCEL_AI_API_KEY",
                        runtime == null ? null : runtime.apiKey(),
                        defaultApiKey);
        String model =
                effectiveString(
                        "EXCEL_AI_MODEL", runtime == null ? null : runtime.model(), defaultModel);
        long timeoutSeconds =
                environmentLong("EXCEL_AI_TIMEOUT_SECONDS")
                        .orElse(runtime == null ? defaultTimeoutSeconds : runtime.timeoutSeconds());
        return new AiConfiguration(
                enabled, provider, baseUrl, apiKey, model, Math.max(1, timeoutSeconds));
    }

    public PublicAiConfiguration publicConfiguration() {
        AiConfiguration current = current();
        return new PublicAiConfiguration(
                current.available(),
                current.enabled(),
                current.baseUrl(),
                !current.apiKey().isBlank(),
                current.model(),
                current.timeoutSeconds(),
                environmentConfigured());
    }

    public PublicAiConfiguration update(UpdateAiConfiguration request) {
        if (request == null) {
            throw new IllegalArgumentException("AI configuration is required.");
        }
        String baseUrl = normalizeUrl(request.baseUrl());
        String model = normalize(request.model());
        String apiKey = normalize(request.apiKey());
        AiConfiguration existing = current();
        if (apiKey.isBlank() && request.keepExistingApiKey()) {
            apiKey = existing.apiKey();
        }
        if (request.enabled() && (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank())) {
            throw new IllegalArgumentException(
                    "AI service URL, API Key, and model are required when AI is enabled.");
        }
        runtimeConfiguration =
                new RuntimeConfiguration(
                        request.enabled(),
                        "openai-compatible",
                        baseUrl,
                        apiKey,
                        model,
                        Math.max(1, request.timeoutSeconds()));
        log.info(
                "Runtime AI Excel configuration updated. enabled={}, baseUrlConfigured={}, model={}",
                current().available(),
                !current().baseUrl().isBlank(),
                current().model().isBlank() ? "<empty>" : current().model());
        return publicConfiguration();
    }

    public ConnectionTestResult testConnection() {
        AiConfiguration configuration = current();
        if (!configuration.available()) {
            return new ConnectionTestResult(false, "AI service is not fully configured.");
        }
        try {
            Map<String, Object> requestBody =
                    new java.util.LinkedHashMap<>(
                            Map.of(
                                    "model",
                                    configuration.model(),
                                    "messages",
                                    List.of(
                                            Map.of(
                                                    "role",
                                                    "user",
                                                    "content",
                                                    "Reply with OK only.")),
                                    "temperature",
                                    0,
                                    "max_tokens",
                                    32));
            ExcelAiRuleParseService.applyProviderCompatibility(requestBody, configuration);
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            ExcelAiRuleParseService.buildChatCompletionsUrl(
                                                    configuration.baseUrl())))
                            .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                            .header("Authorization", "Bearer " + configuration.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ConnectionTestResult(
                        false, "AI service returned HTTP " + response.statusCode() + ".");
            }
            JsonNode content =
                    objectMapper
                            .readTree(response.body())
                            .path("choices")
                            .path(0)
                            .path("message")
                            .path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                JsonNode finishReason =
                        objectMapper
                                .readTree(response.body())
                                .path("choices")
                                .path(0)
                                .path("finish_reason");
                return new ConnectionTestResult(
                        false,
                        "AI service returned an empty response"
                                + (finishReason.isMissingNode()
                                        ? "."
                                        : " (finish_reason=" + finishReason.asText() + ")."));
            }
            return new ConnectionTestResult(true, "AI service connection succeeded.");
        } catch (IOException e) {
            log.warn("AI connection test failed: {}", e.getMessage());
            return new ConnectionTestResult(
                    false, "AI connection failed. Check the service URL, API Key, and model.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionTestResult(false, "AI connection test was interrupted.");
        } catch (RuntimeException e) {
            log.warn("AI connection test failed: {}", e.getMessage());
            return new ConnectionTestResult(
                    false, "AI connection failed. Check the service URL, API Key, and model.");
        }
    }

    private boolean environmentConfigured() {
        return hasEnvironmentValue("EXCEL_AI_BASE_URL")
                || hasEnvironmentValue("EXCEL_AI_API_KEY")
                || hasEnvironmentValue("EXCEL_AI_MODEL");
    }

    private static String effectiveString(
            String environmentName, String runtimeValue, String defaultValue) {
        String environmentValue = normalize(System.getenv(environmentName));
        if (!environmentValue.isBlank()) {
            return environmentValue;
        }
        String normalizedRuntime = normalize(runtimeValue);
        return normalizedRuntime.isBlank() ? normalize(defaultValue) : normalizedRuntime;
    }

    private static java.util.Optional<Boolean> environmentBoolean(String name) {
        String value = normalize(System.getenv(name));
        return value.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(Boolean.parseBoolean(value));
    }

    private static java.util.Optional<Long> environmentLong(String name) {
        String value = normalize(System.getenv(name));
        if (value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static boolean hasEnvironmentValue(String name) {
        return !normalize(System.getenv(name)).isBlank();
    }

    private static String normalizeUrl(String value) {
        return normalize(value).replaceAll("/+$", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record AiConfiguration(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            long timeoutSeconds) {
        public boolean available() {
            return enabled
                    && !baseUrl.isBlank()
                    && !apiKey.isBlank()
                    && !model.isBlank()
                    && "openai-compatible".equalsIgnoreCase(provider);
        }
    }

    public record PublicAiConfiguration(
            boolean available,
            boolean enabled,
            String baseUrl,
            boolean apiKeyConfigured,
            String model,
            long timeoutSeconds,
            boolean environmentConfigured) {}

    public record UpdateAiConfiguration(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String model,
            long timeoutSeconds,
            boolean keepExistingApiKey) {}

    public record ConnectionTestResult(boolean success, String message) {}

    private record RuntimeConfiguration(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            long timeoutSeconds) {}
}

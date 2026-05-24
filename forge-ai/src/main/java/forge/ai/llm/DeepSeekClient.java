package forge.ai.llm;

import org.tinylog.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public final class DeepSeekClient {
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final int DEFAULT_TIMEOUT_MS = 60000;
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final HttpClient httpClient;
    private final URI chatCompletionsUri;
    private final String apiKey;
    private final String model;
    private final String thinking;
    private final String reasoningEffort;
    private final int timeoutMs;
    private final int maxTokens;

    private DeepSeekClient(String apiKey, String baseUrl, String model, String thinking,
            String reasoningEffort, int timeoutMs, int maxTokens) {
        this.apiKey = apiKey;
        this.chatCompletionsUri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
        this.model = model;
        this.thinking = thinking;
        this.reasoningEffort = reasoningEffort;
        this.timeoutMs = timeoutMs;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public static Optional<DeepSeekClient> fromEnvironment() {
        String apiKey = firstPresent("DEEPSEEK_API_KEY");
        if (isBlank(apiKey)) {
            return Optional.empty();
        }

        String baseUrl = firstNonBlank(firstPresent("ULTRON_DEEPSEEK_BASE_URL"),
                firstPresent("DEEPSEEK_BASE_URL"), DEFAULT_BASE_URL);
        String model = firstNonBlank(firstPresent("ULTRON_DEEPSEEK_MODEL"), DEFAULT_MODEL);
        String thinking = firstNonBlank(firstPresent("ULTRON_DEEPSEEK_THINKING"), "enabled");
        String reasoningEffort = firstNonBlank(firstPresent("ULTRON_DEEPSEEK_REASONING_EFFORT"), "high");
        int timeoutMs = parsePositiveInt(firstPresent("ULTRON_DEEPSEEK_TIMEOUT_MS"), DEFAULT_TIMEOUT_MS);
        int maxTokens = parsePositiveInt(firstPresent("ULTRON_DEEPSEEK_MAX_TOKENS"), DEFAULT_MAX_TOKENS);

        return Optional.of(new DeepSeekClient(apiKey, baseUrl, model, thinking, reasoningEffort, timeoutMs, maxTokens));
    }

    public static Optional<DeepSeekClient> fromEnvironmentWithPrefix(String prefix, String defaultModel,
            String defaultThinking, String defaultReasoningEffort, int defaultTimeoutMs, int defaultMaxTokens) {
        String apiKey = firstPresent("DEEPSEEK_API_KEY");
        if (isBlank(apiKey)) {
            return Optional.empty();
        }

        String normalizedPrefix = isBlank(prefix) ? "ULTRON_DEEPSEEK" : prefix.trim();
        String baseUrl = firstNonBlank(firstPresent(normalizedPrefix + "_BASE_URL"),
                firstPresent("ULTRON_DEEPSEEK_BASE_URL"), firstPresent("DEEPSEEK_BASE_URL"), DEFAULT_BASE_URL);
        String model = firstNonBlank(firstPresent(normalizedPrefix + "_MODEL"), defaultModel);
        String thinking = firstNonBlank(firstPresent(normalizedPrefix + "_THINKING"), defaultThinking);
        String reasoningEffort = firstNonBlank(firstPresent(normalizedPrefix + "_REASONING_EFFORT"),
                defaultReasoningEffort);
        int timeoutMs = parsePositiveInt(firstPresent(normalizedPrefix + "_TIMEOUT_MS"), defaultTimeoutMs);
        int maxTokens = parsePositiveInt(firstPresent(normalizedPrefix + "_MAX_TOKENS"), defaultMaxTokens);

        return Optional.of(new DeepSeekClient(apiKey, baseUrl, model, thinking, reasoningEffort, timeoutMs, maxTokens));
    }

    public CompletionResult completeJson(String systemPrompt, String userPrompt, int maxWaitMs) {
        return complete(systemPrompt, userPrompt, maxWaitMs, true);
    }

    public CompletionResult completeText(String systemPrompt, String userPrompt, int maxWaitMs) {
        return complete(systemPrompt, userPrompt, maxWaitMs, false, false);
    }

    public CompletionResult completeOptionalText(String systemPrompt, String userPrompt, int maxWaitMs) {
        return complete(systemPrompt, userPrompt, maxWaitMs, false, true);
    }

    private CompletionResult complete(String systemPrompt, String userPrompt, int maxWaitMs, boolean jsonResponse) {
        return complete(systemPrompt, userPrompt, maxWaitMs, jsonResponse, false);
    }

    private CompletionResult complete(String systemPrompt, String userPrompt, int maxWaitMs, boolean jsonResponse,
            boolean allowBlankContent) {
        String body = buildRequestBody(systemPrompt, userPrompt, jsonResponse);
        int requestTimeoutMs = Math.min(timeoutMs, Math.max(1000, maxWaitMs));
        HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri)
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                Logger.debug("Ultron DeepSeek request failed with HTTP status {}", status);
                return CompletionResult.failure(status, response.body(), "HTTP status " + status,
                        response.headers().firstValue("x-ds-trace-id").orElse(""),
                        JsonSupport.extractUsageSummary(response.body()),
                        JsonSupport.extractFirstChoiceFinishReason(response.body()),
                        null,
                        null);
            }

            String content = JsonSupport.extractFirstMessageContent(response.body());
            String reasoningContent = JsonSupport.extractFirstMessageReasoningContent(response.body());
            String usageSummary = JsonSupport.extractUsageSummary(response.body());
            String finishReason = JsonSupport.extractFirstChoiceFinishReason(response.body());
            String providerTraceId = response.headers().firstValue("x-ds-trace-id").orElse("");
            if (isBlank(content) && !allowBlankContent) {
                return CompletionResult.failure(status, response.body(), "Missing assistant message content",
                        providerTraceId, usageSummary, finishReason, content, reasoningContent);
            }
            return CompletionResult.success(status, response.body(), content, reasoningContent,
                    providerTraceId, usageSummary, finishReason);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Logger.debug(ex, "Ultron DeepSeek request failed");
            return CompletionResult.failure(0, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    String describeForTrace() {
        return "model=" + model
                + ", endpoint=" + chatCompletionsUri
                + ", thinking=" + thinking
                + ", reasoningEffort=" + reasoningEffort
                + ", timeoutMs=" + timeoutMs
                + ", maxTokens=" + maxTokens;
    }

    private String buildRequestBody(String systemPrompt, String userPrompt, boolean jsonResponse) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        appendField(sb, "model", JsonSupport.quote(model));
        sb.append(",\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":").append(JsonSupport.quote(systemPrompt)).append('}');
        sb.append(',');
        sb.append("{\"role\":\"user\",\"content\":").append(JsonSupport.quote(userPrompt)).append('}');
        sb.append(']');
        if (jsonResponse) {
            sb.append(",\"response_format\":{\"type\":\"json_object\"}");
        }
        sb.append(",\"stream\":false");
        sb.append(",\"max_tokens\":").append(maxTokens);
        sb.append(",\"user_id\":\"forge-ultron\"");
        sb.append(",\"thinking\":{\"type\":").append(JsonSupport.quote(thinking)).append('}');
        if ("enabled".equalsIgnoreCase(thinking)) {
            sb.append(",\"reasoning_effort\":").append(JsonSupport.quote(reasoningEffort));
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append(JsonSupport.quote(name)).append(':').append(value);
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? DEFAULT_BASE_URL : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String firstPresent(String envName) {
        String property = System.getProperty(envName);
        if (!isBlank(property)) {
            return property;
        }
        return System.getenv(envName);
    }

    private static String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first.trim();
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        return fallback;
    }

    private static String firstNonBlank(String first, String second, String third, String fallback) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        if (!isBlank(third)) {
            return third.trim();
        }
        return fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public record CompletionResult(boolean success, int statusCode, String rawResponse, String content,
            String reasoningContent, String error, String providerTraceId, String usageSummary, String finishReason) {
        static CompletionResult success(int statusCode, String rawResponse, String content, String reasoningContent,
                String providerTraceId, String usageSummary, String finishReason) {
            return new CompletionResult(true, statusCode, rawResponse, content, reasoningContent, null,
                    providerTraceId, usageSummary, finishReason);
        }

        static CompletionResult failure(int statusCode, String rawResponse, String error) {
            return failure(statusCode, rawResponse, error, "", "", "", null, null);
        }

        static CompletionResult failure(int statusCode, String rawResponse, String error,
                String providerTraceId, String usageSummary) {
            return failure(statusCode, rawResponse, error, providerTraceId, usageSummary, "", null, null);
        }

        static CompletionResult failure(int statusCode, String rawResponse, String error,
                String providerTraceId, String usageSummary, String finishReason, String content,
                String reasoningContent) {
            return new CompletionResult(false, statusCode, rawResponse, content, reasoningContent, error,
                    providerTraceId, usageSummary, finishReason);
        }
    }
}

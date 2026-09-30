package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One HTTP send for one already authorized, persisted attempt. No application retries. */
public final class DeepSeekChatClient {
    public enum Status { VALID, INVALID_JSON, DEFINITE_FAILURE, BLOCKED_CONFIGURATION, UNKNOWN }
    public enum CostState { UNKNOWN }
    public record Usage(Long promptTokens, Long completionTokens, Long totalTokens,
                        Long cacheHitTokens) { }
    public static final class Outcome {
        private final Status status;
        private final Integer httpStatus;
        private final String providerRequestId;
        private final String contentJson;
        private final Usage usage;
        private final Integer retryAfterSeconds;

        private Outcome(Status status, Integer httpStatus, String providerRequestId,
                        String contentJson, Usage usage, Integer retryAfterSeconds) {
            this.status = status;
            this.httpStatus = httpStatus;
            this.providerRequestId = providerRequestId;
            this.contentJson = contentJson;
            this.usage = usage;
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public Status status() { return status; }
        public Integer httpStatus() { return httpStatus; }
        public String providerRequestId() { return providerRequestId; }
        public String contentJson() { return contentJson; }
        public Usage usage() { return usage; }
        public Integer retryAfterSeconds() { return retryAfterSeconds; }
        public CostState costState() { return CostState.UNKNOWN; }
        /** Never render returned content, usage detail or a credential in diagnostic text. */
        @Override public String toString() { return "DeepSeekOutcome[status=" + status + "]"; }
    }

    private static final URI OFFICIAL = URI.create("https://api.deepseek.com/chat/completions");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PARAMETERS = Set.of("max_tokens", "temperature",
            "top_p", "thinking", "stop");
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final HttpClient http;
    private final URI endpoint;
    private final Supplier<String> keyAtSend;

    public DeepSeekChatClient(Supplier<String> keyAtSend) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15)).build(), OFFICIAL, keyAtSend);
    }
    /** The endpoint and transport are injected for a loopback stub test. */
    DeepSeekChatClient(HttpClient http, URI endpoint, Supplier<String> keyAtSend) {
        if (http == null || endpoint == null || keyAtSend == null
                || !("https".equals(endpoint.getScheme()) && "api.deepseek.com".equals(endpoint.getHost())
                    || "http".equals(endpoint.getScheme()) && "localhost".equals(endpoint.getHost()))
                || !"/chat/completions".equals(endpoint.getPath())
                || endpoint.getRawQuery() != null || endpoint.getRawUserInfo() != null)
            throw new IllegalArgumentException("provider endpoint invalid");
        this.http = http;
        this.endpoint = endpoint;
        this.keyAtSend = keyAtSend;
    }

    /** The caller has already reserved the attempt and passed the send-once authorization. */
    public Outcome sendOnce(String frozenRequestJson) {
        byte[] body = providerBody(frozenRequestJson);
        String key;
        try { key = keyAtSend.get(); }
        catch (RuntimeException unavailable) {
            throw new IllegalStateException("provider credential unavailable");
        }
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new IllegalStateException("provider credential unavailable");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] received = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                String requestIdHeader = response.headers().firstValue("x-request-id").orElse(null);
                long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
                if (declared >= 0 && received.length < declared)
                    return new Outcome(Status.UNKNOWN, response.statusCode(),
                            requestIdHeader, null, null, null);
                if (received.length > MAX_RESPONSE_BYTES)
                    return new Outcome(Status.INVALID_JSON, response.statusCode(),
                            requestIdHeader, null, null, null);
                return interpret(response.statusCode(), response.headers().firstValue("retry-after")
                        .orElse(null), requestIdHeader, received);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome(Status.UNKNOWN, null, null, null, null, null);
        } catch (IOException transportUnknown) {
            return new Outcome(Status.UNKNOWN, null, null, null, null, null);
        }
    }

    private static byte[] providerBody(String frozen) {
        try {
            JsonNode request = CanonicalJson.parse(frozen);
            if (request == null || !request.isObject()
                    || !"deepseek".equals(request.path("provider").asText())
                    || !"deepseek-flash".equals(request.path("model").asText())
                    || !request.path("messages").isArray() || request.path("messages").isEmpty()
                    || !request.path("parameters").isObject()
                    || !"json_object".equals(request.path("response_format").path("type").asText())
                    || !request.path("input_hash").asText().matches("[0-9a-f]{64}")
                    || request.path("prompt_version").asText().isBlank()
                    || request.path("schema_version").asText().isBlank()
                    || containsSecretField(request)) throw new IllegalArgumentException();
            ObjectNode wire = JSON.createObjectNode();
            wire.put("model", "deepseek-flash");
            wire.set("messages", request.get("messages"));
            wire.set("response_format", request.get("response_format"));
            wire.put("stream", false);
            for (var field : request.get("parameters").properties()) {
                if (!PARAMETERS.contains(field.getKey())) throw new IllegalArgumentException();
                wire.set(field.getKey(), field.getValue());
            }
            return JSON.writeValueAsBytes(wire);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("frozen provider request invalid");
        }
    }
    private static boolean containsSecretField(JsonNode node) {
        if (node.isObject()) for (var field : node.properties()) {
            if (Set.of("api_key", "authorization", "password", "secret")
                    .contains(field.getKey().toLowerCase()) || containsSecretField(field.getValue()))
                return true;
        }
        if (node.isArray()) for (JsonNode item : node) if (containsSecretField(item)) return true;
        return false;
    }
    private static Outcome interpret(int status, String retryAfter, String requestIdHeader,
                                     byte[] body) {
        Integer delay = retryAfter(retryAfter);
        if (status == 401 || status == 402 || status == 403)
            return new Outcome(Status.BLOCKED_CONFIGURATION, status, requestIdHeader,
                    null, null, delay);
        if (status != 200)
            return new Outcome(Status.DEFINITE_FAILURE, status, requestIdHeader,
                    null, null, delay);
        try {
            JsonNode response = JSON.readTree(new String(body, StandardCharsets.UTF_8));
            String id = response.path("id").asText(null);
            JsonNode choices = response.path("choices");
            Usage usage = usage(response.path("usage"));
            if (id == null || id.isBlank()
                    || !"deepseek-flash".equals(response.path("model").asText())
                    || !choices.isArray() || choices.size() != 1)
                return new Outcome(Status.INVALID_JSON, status, requestIdHeader, null, usage, null);
            JsonNode choice = choices.get(0);
            String content = choice.path("message").path("content").asText(null);
            if (!"stop".equals(choice.path("finish_reason").asText())
                    || content == null || content.isBlank())
                return new Outcome(Status.INVALID_JSON, status, id, null, usage, null);
            JsonNode parsed = JSON.readTree(content);
            if (parsed == null || !parsed.isObject())
                return new Outcome(Status.INVALID_JSON, status, id, null, usage, null);
            return new Outcome(Status.VALID, status, id, content, usage, null);
        } catch (RuntimeException invalid) {
            return new Outcome(Status.INVALID_JSON, status, requestIdHeader, null, null, null);
        }
    }
    private static Usage usage(JsonNode node) {
        if (!node.isObject()) return null;
        Long prompt = count(node, "prompt_tokens");
        Long completion = count(node, "completion_tokens");
        Long total = count(node, "total_tokens");
        if (prompt == null || completion == null || total == null) return null;
        return new Usage(prompt, completion, total, count(node, "prompt_cache_hit_tokens"));
    }
    private static Long count(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isIntegralNumber() && value.longValue() >= 0 ? value.longValue() : null;
    }
    private static Integer retryAfter(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,4}")) return null;
        int seconds = Integer.parseInt(raw);
        return seconds <= 3600 ? seconds : null;
    }
}

package kioberflaeche.ai;

import kioberflaeche.model.ChatMessage;
import kioberflaeche.util.SimpleJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class N8nChatWebhookClient implements AiClient {
    private final HttpClient client;
    private final String endpoint;
    private final String authorization;
    private final Duration timeout;

    public N8nChatWebhookClient(String endpoint, String authorization, int timeoutSeconds) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = endpoint == null ? "" : endpoint;
        this.authorization = authorization == null ? "" : authorization;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public String endpoint() {
        return endpoint;
    }

    @Override
    public CompletableFuture<String> ask(String sessionId, List<ChatMessage> history, String userMessage) {
        if (endpoint.isBlank()) {
            return CompletableFuture.failedFuture(new HttpAiClient.AiRequestException(
                    "n8n-Chat-Webhook-URL fehlt. Bitte Chat URL aus dem n8n Chat Trigger in config/local.properties setzen."
            ));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildPayload(sessionId, history, userMessage), StandardCharsets.UTF_8));

        if (!authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new HttpAiClient.AiRequestException("n8n-Chat-Anfrage fehlgeschlagen: HTTP "
                                + response.statusCode() + " - " + response.body());
                    }
                    return extractAnswer(response.body())
                            .orElseThrow(() -> new HttpAiClient.AiRequestException("n8n-Antwort konnte nicht gelesen werden: " + response.body()));
                })
                .exceptionally(error -> {
                    Throwable cause = unwrap(error);
                    if (cause instanceof java.net.http.HttpTimeoutException) {
                        throw new HttpAiClient.AiRequestException("n8n-Chat-Anfrage an " + endpoint + " nach "
                                + timeout.toSeconds() + " Sekunden abgebrochen.");
                    }
                    throw new CompletionException(cause);
                });
    }

    private String buildPayload(String sessionId, List<ChatMessage> history, String userMessage) {
        StringBuilder payload = new StringBuilder();
        payload.append('{');
        payload.append("\"action\":\"sendMessage\",");
        payload.append("\"sessionId\":\"").append(escape(sessionId)).append("\",");
        payload.append("\"chatInput\":\"").append(escape(userMessage)).append("\",");
        payload.append("\"message\":\"").append(escape(userMessage)).append("\",");
        payload.append("\"history\":[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                payload.append(',');
            }
            ChatMessage message = history.get(i);
            payload.append('{')
                    .append("\"role\":\"").append(message.sender().apiRole()).append("\",")
                    .append("\"content\":\"").append(escape(message.text())).append("\"")
                    .append('}');
        }
        payload.append(']');
        payload.append('}');
        return payload.toString();
    }

    private Optional<String> extractAnswer(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }

        try {
            Object parsed = SimpleJson.parse(trimmed);
            return extractFromJson(parsed);
        } catch (RuntimeException ignored) {
            return Optional.of(trimmed);
        }
    }

    private Optional<String> extractFromJson(Object parsed) {
        if (parsed instanceof String value) {
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        }
        if (parsed instanceof List<?> list && !list.isEmpty()) {
            return extractFromJson(list.get(0));
        }
        Map<String, Object> object = SimpleJson.asObject(parsed);
        for (String key : List.of("output", "text", "response", "answer", "content", "message")) {
            Object value = object.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return Optional.of(text);
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                Optional<String> nested = extractFromJson(value);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String escape(String text) {
        StringBuilder escaped = new StringBuilder();
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

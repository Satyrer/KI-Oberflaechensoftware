package kioberflaeche.ai;

import kioberflaeche.model.ChatMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class HttpAiClient implements AiClient {
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public HttpAiClient(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, 300);
    }

    public HttpAiClient(String endpoint, String apiKey, String model, int timeoutSeconds) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public CompletableFuture<String> ask(String sessionId, List<ChatMessage> history, String userMessage) {
        String payload = buildPayload(history, userMessage);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new AiRequestException("KI-Anfrage fehlgeschlagen: HTTP "
                                + response.statusCode() + " - " + response.body());
                    }
                    return extractAnswer(response.body())
                            .orElseThrow(() -> new AiRequestException("KI-Antwort konnte nicht gelesen werden: " + response.body()));
                })
                .exceptionally(error -> {
                    Throwable cause = unwrap(error);
                    if (cause instanceof java.net.http.HttpTimeoutException) {
                        throw new AiRequestException("KI-Anfrage nach " + timeout.toSeconds()
                                + " Sekunden abgebrochen. Die Generierung hat vermutlich gehaengt oder zu lange gedauert.");
                    }
                    throw new CompletionException(cause);
                });
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String buildPayload(List<ChatMessage> history, String userMessage) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        if (!model.isBlank()) {
            json.append("\"model\":\"").append(escape(model)).append("\",");
        }
        json.append("\"stream\":false,");
        json.append("\"message\":\"").append(escape(userMessage)).append("\",");
        json.append("\"messages\":[");
        for (int i = 0; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{')
                    .append("\"role\":\"").append(message.sender().apiRole()).append("\",")
                    .append("\"content\":\"").append(escape(message.text())).append("\"")
                    .append('}');
        }
        if (!history.isEmpty()) {
            json.append(',');
        }
        json.append("{\"role\":\"user\",\"content\":\"").append(escape(userMessage)).append("\"}");
        json.append("]}");
        return json.toString();
    }

    private Optional<String> extractAnswer(String body) {
        return firstJsonStringValue(body, "response")
                .or(() -> firstJsonStringValue(body, "answer"))
                .or(() -> firstJsonStringValue(body, "content"))
                .or(() -> firstJsonStringValue(body, "message"))
                .or(() -> firstOpenAiChoiceContent(body))
                .or(() -> body.isBlank() ? Optional.empty() : Optional.of(body.trim()));
    }

    private Optional<String> firstOpenAiChoiceContent(String body) {
        int choicesIndex = body.indexOf("\"choices\"");
        if (choicesIndex < 0) {
            return Optional.empty();
        }
        int contentIndex = body.indexOf("\"content\"", choicesIndex);
        if (contentIndex < 0) {
            return Optional.empty();
        }
        return stringValueAfterColon(body, contentIndex + "\"content\"".length());
    }

    private Optional<String> firstJsonStringValue(String body, String key) {
        int keyIndex = body.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return Optional.empty();
        }
        return stringValueAfterColon(body, keyIndex + key.length() + 2);
    }

    private Optional<String> stringValueAfterColon(String body, int startIndex) {
        int colonIndex = body.indexOf(':', startIndex);
        if (colonIndex < 0) {
            return Optional.empty();
        }
        int quoteIndex = body.indexOf('"', colonIndex + 1);
        if (quoteIndex < 0) {
            return Optional.empty();
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = quoteIndex + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                value.append(unescape(c));
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return Optional.of(value.toString());
            } else {
                value.append(c);
            }
        }
        return Optional.empty();
    }

    private char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '"' -> '"';
            case '\\' -> '\\';
            default -> c;
        };
    }

    private String escape(String text) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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

    public static class AiRequestException extends RuntimeException {
        public AiRequestException(String message) {
            super(message);
        }

        public AiRequestException(IOException cause) {
            super(cause);
        }
    }
}

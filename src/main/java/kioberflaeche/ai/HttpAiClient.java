package kioberflaeche.ai;

import kioberflaeche.model.ChatMessage;
import kioberflaeche.util.SimpleJson;

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
        this(endpoint, apiKey, model, (int) RequestBudgets.CHAT_SECONDS);
    }

    public HttpAiClient(String endpoint, String apiKey, String model, int timeoutSeconds) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.timeout = RequestBudgets.chat(timeoutSeconds);
    }

    @Override
    public CompletableFuture<String> ask(String sessionId, List<ChatMessage> history, String userMessage) {
        return askWithModel(sessionId,history,userMessage,"auto","auto").thenApply(Reply::text);
    }

    @Override
    public CompletableFuture<Reply> askWithModel(String sessionId, List<ChatMessage> history, String userMessage, String modelSelection, String task) {
        String payload = buildPayload(AiClient.recentHistory(history), userMessage, modelSelection, task);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .version(HttpClient.Version.HTTP_1_1)
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
                    try { ModelRouting.verify(response.body(),modelSelection); } catch(IOException error) { throw new AiRequestException(error); }
                    String text=extractAnswer(response.body()).orElseThrow(() -> new AiRequestException("KI-Antwort konnte nicht gelesen werden."));
                    return new Reply(text, ModelRouting.summary(response.body()));
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

    private String buildPayload(List<ChatMessage> history, String userMessage, String modelSelection, String task) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"modelSelection\":\"").append(escape(ModelRouting.normalize(modelSelection))).append("\",");
        json.append("\"task\":\"").append(escape(ModelRouting.normalize(task))).append("\",");
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
        try { return extractJson(SimpleJson.parse(body)); }
        catch(RuntimeException error) { return body.isBlank()?Optional.empty():Optional.of(body.strip()); }
    }
    private Optional<String> extractJson(Object value) {
        if(value instanceof String text) return text.isBlank()?Optional.empty():Optional.of(text);
        if(value instanceof List<?> list) return list.isEmpty()?Optional.empty():extractJson(list.get(0));
        if(!(value instanceof java.util.Map<?,?>)) return Optional.empty();
        var root=SimpleJson.asObject(value);
        for(String key:List.of("response","answer","message","content","choices","output","text")) {
            var found=extractJson(root.get(key)); if(found.isPresent()) return found;
        }
        return Optional.empty();
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
                default -> { if(c<32) escaped.append(String.format("\\u%04x",(int)c)); else escaped.append(c); }
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

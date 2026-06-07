package kioberflaeche.admin;

import kioberflaeche.model.ChatMessage;
import kioberflaeche.storage.ChatSession;
import kioberflaeche.storage.ChatStore;
import kioberflaeche.util.SimpleJson;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class N8nWebChatClient {
    private static final int CHAT_LIST_LIMIT = 18;

    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String baseUrl;
    private final String email;
    private final String password;
    private final Duration requestTimeout;
    private boolean loggedIn;

    public N8nWebChatClient(String baseUrl, String email, String password, int timeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl);
        this.email = email == null ? "" : email;
        this.password = password == null ? "" : password;
        this.requestTimeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !email.isBlank() && !password.isBlank();
    }

    public List<WebChatSummary> listChats() throws IOException, InterruptedException {
        ensureLogin();
        String body = get("/rest/chat/conversations?limit=" + CHAT_LIST_LIMIT + "&type=production");
        Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(body));
        List<WebChatSummary> summaries = new ArrayList<>();
        for (Object item : SimpleJson.asArray(root.get("sessions"))) {
            Map<String, Object> session = SimpleJson.asObject(item);
            summaries.add(new WebChatSummary(
                    SimpleJson.asString(session.get("id")),
                    SimpleJson.asString(session.get("title")),
                    firstPresent(session, "lastMessageAt", "updatedAt", "createdAt"),
                    SimpleJson.asString(session.get("type")),
                    SimpleJson.asString(session.get("agentName"))
            ));
        }
        return summaries;
    }

    public ImportedWebChat importChat(WebChatSummary summary) throws IOException, InterruptedException {
        ensureLogin();
        String body = get("/rest/chat/conversations/" + encodePath(summary.id()));
        Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(body));
        Map<String, Object> session = SimpleJson.asObject(root.get("session"));
        Map<String, Object> conversation = SimpleJson.asObject(root.get("conversation"));
        Map<String, Object> messages = SimpleJson.asObject(conversation.get("messages"));

        String title = firstPresent(session, "title");
        if (title.isBlank()) {
            title = summary.title();
        }
        if (title == null || title.isBlank()) {
            title = "n8n WebUI Chat";
        }

        ChatSession imported = new ChatSession(UUID.randomUUID().toString(), "n8n: " + title);
        List<ChatStore.StoredFile> files = new ArrayList<>();
        messages.values().stream()
                .map(SimpleJson::asObject)
                .sorted(Comparator.comparing(this::messageTime))
                .forEach(message -> {
                    addImportedMessage(imported, message);
                    files.addAll(markdownFiles(summary.id(), message));
                });
        return new ImportedWebChat(imported, files);
    }

    public void deleteChat(String sessionId) throws IOException, InterruptedException {
        ensureLogin();
        HttpRequest request = HttpRequest.newBuilder(uri("/rest/chat/conversations/" + encodePath(sessionId)))
                .timeout(requestTimeout)
                .DELETE()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-WebUI-Chat konnte nicht geloescht werden: HTTP " + response.statusCode());
        }
    }

    private void ensureLogin() throws IOException, InterruptedException {
        if (loggedIn) {
            return;
        }
        if (!isConfigured()) {
            throw new IOException("n8n-WebUI-Zugangsdaten fehlen in config/local.properties");
        }

        String payload = "{\"emailOrLdapLoginId\":\"" + escapeJson(email) + "\",\"password\":\"" + escapeJson(password) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(uri("/rest/login"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-WebUI-Login fehlgeschlagen: HTTP " + response.statusCode());
        }
        loggedIn = true;
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-WebUI nicht erreichbar: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private byte[] getBytes(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-WebUI-Anhang nicht erreichbar: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void addImportedMessage(ChatSession imported, Map<String, Object> message) {
        String text = messageText(message);
        if (text.isBlank()) {
            return;
        }

        imported.addLoaded(new ChatMessage(sender(message), text, messageTime(message)));
    }

    private ChatMessage.Sender sender(Map<String, Object> message) {
        String type = SimpleJson.asString(message.get("type"));
        if ("human".equalsIgnoreCase(type) || "user".equalsIgnoreCase(type)) {
            return ChatMessage.Sender.USER;
        }
        if ("ai".equalsIgnoreCase(type) || "assistant".equalsIgnoreCase(type)) {
            return ChatMessage.Sender.ASSISTANT;
        }
        return ChatMessage.Sender.SYSTEM;
    }

    private String messageText(Map<String, Object> message) {
        Object content = message.get("content");
        if (content instanceof String value) {
            return value;
        }

        List<String> parts = new ArrayList<>();
        for (Object item : SimpleJson.asArray(content)) {
            Map<String, Object> block = SimpleJson.asObject(item);
            String type = SimpleJson.asString(block.get("type"));
            String value = firstPresent(block, "content", "text");
            if (value.isBlank()) {
                continue;
            }
            if ("text".equalsIgnoreCase(type) || type.isBlank()) {
                parts.add(value);
            }
        }
        return String.join(System.lineSeparator(), parts);
    }

    private List<ChatStore.StoredFile> markdownFiles(String sessionId, Map<String, Object> message) {
        String messageId = SimpleJson.asString(message.get("id"));
        if (messageId.isBlank()) {
            return List.of();
        }

        List<ChatStore.StoredFile> files = new ArrayList<>();
        for (Attachment attachment : attachments(message)) {
            if (!attachment.isMarkdown()) {
                continue;
            }
            try {
                byte[] content = attachment.inlineContent();
                if (content.length == 0) {
                    content = getBytes("/rest/chat/conversations/"
                            + encodePath(sessionId)
                            + "/messages/"
                            + encodePath(messageId)
                            + "/attachments/"
                            + encodePath(attachment.lookupKey()));
                }
                files.add(new ChatStore.StoredFile(attachment.fileName(), content));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                files.add(new ChatStore.StoredFile(
                        attachment.fileName() + ".import-fehler.txt",
                        ("Anhang konnte nicht importiert werden: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)
                ));
            }
        }
        return files;
    }

    private List<Attachment> attachments(Map<String, Object> message) {
        Object raw = message.get("attachments");
        if (raw == null) {
            return List.of();
        }

        List<Attachment> attachments = new ArrayList<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> entry : SimpleJson.asObject(raw).entrySet()) {
                attachments.add(attachment(entry.getKey(), SimpleJson.asObject(entry.getValue())));
            }
        } else {
            int index = 0;
            for (Object item : SimpleJson.asArray(raw)) {
                attachments.add(attachment(String.valueOf(index), SimpleJson.asObject(item)));
                index++;
            }
        }
        return attachments;
    }

    private Attachment attachment(String key, Map<String, Object> data) {
        String fileName = firstPresent(data, "fileName", "filename", "name");
        if (fileName.isBlank()) {
            fileName = key.endsWith(".md") ? key : "anhang-" + key + ".md";
        }
        String mimeType = firstPresent(data, "mimeType", "mime", "type");
        String lookupKey = firstPresent(data, "id", "attachmentId", "fileId", "fileName", "filename", "name");
        if (lookupKey.isBlank()) {
            lookupKey = key;
        }
        String inlineData = firstPresent(data, "data", "content", "text");
        return new Attachment(lookupKey, fileName, mimeType, inlineData);
    }

    private LocalDateTime messageTime(Map<String, Object> message) {
        String raw = firstPresent(message, "createdAt", "updatedAt", "timestamp");
        if (raw.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(raw);
            } catch (RuntimeException ignoredAgain) {
                return LocalDateTime.now();
            }
        }
    }

    private String firstPresent(Map<String, Object> object, String... keys) {
        for (String key : keys) {
            String value = SimpleJson.asString(object.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String stripTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public record ImportedWebChat(ChatSession session, List<ChatStore.StoredFile> files) {
    }

    private record Attachment(String lookupKey, String fileName, String mimeType, String inlineData) {
        private boolean isMarkdown() {
            String lowerName = fileName == null ? "" : fileName.toLowerCase();
            String lowerMime = mimeType == null ? "" : mimeType.toLowerCase();
            return lowerName.endsWith(".md")
                    || lowerName.endsWith(".markdown")
                    || lowerMime.contains("markdown")
                    || lowerMime.contains("text/plain");
        }

        private byte[] inlineContent() {
            if (inlineData == null || inlineData.isBlank()) {
                return new byte[0];
            }
            try {
                return Base64.getDecoder().decode(inlineData);
            } catch (IllegalArgumentException ignored) {
                return inlineData.getBytes(StandardCharsets.UTF_8);
            }
        }
    }
}

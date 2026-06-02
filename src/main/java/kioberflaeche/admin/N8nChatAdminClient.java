package kioberflaeche.admin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class N8nChatAdminClient {
    private static final Pattern ROW_PATTERN = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL_PATTERN = Pattern.compile("<td.*?>(.*?)</td>", Pattern.DOTALL);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String baseUrl;
    private final String token;

    public N8nChatAdminClient(String baseUrl, String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.token = token == null ? "" : token;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !token.isBlank();
    }

    public List<ChatExecution> listChats() throws IOException, InterruptedException {
        String body = get(adminUri(""));
        List<ChatExecution> executions = new ArrayList<>();
        Matcher rowMatcher = ROW_PATTERN.matcher(body);
        while (rowMatcher.find()) {
            List<String> cells = cells(rowMatcher.group(1));
            if (cells.size() < 5 || "ID".equalsIgnoreCase(cells.get(0))) {
                continue;
            }
            executions.add(new ChatExecution(cells.get(0), cells.get(1), cells.get(2), cells.get(3), cells.get(4)));
        }
        return executions;
    }

    public void exportChat(String executionId) throws IOException, InterruptedException {
        get(adminUri("action=export&id=" + encode(executionId)));
    }

    public void deleteChat(String executionId) throws IOException, InterruptedException {
        String form = "id=" + encode(executionId) + "&action=delete&confirm=DELETE";
        HttpRequest request = HttpRequest.newBuilder(adminUri(""))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-Chat konnte nicht geloescht werden: HTTP " + response.statusCode());
        }
    }

    private String get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-Chatverwaltung nicht erreichbar: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private URI adminUri(String query) {
        String separator = query == null || query.isBlank() ? "" : "&";
        return URI.create(baseUrl + "/?token=" + encode(token) + separator + (query == null ? "" : query));
    }

    private List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        Matcher cellMatcher = CELL_PATTERN.matcher(row);
        while (cellMatcher.find()) {
            cells.add(cleanHtml(cellMatcher.group(1)));
        }
        return cells;
    }

    private String cleanHtml(String value) {
        return value
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

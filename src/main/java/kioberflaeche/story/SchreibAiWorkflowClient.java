package kioberflaeche.story;

import kioberflaeche.util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SchreibAiWorkflowClient {
    public record StoryDocument(String path, String content) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String baseUrl;
    private final Duration requestTimeout;

    public SchreibAiWorkflowClient(String baseUrl, int timeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl);
        this.requestTimeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public List<StoryProject> listProjects() throws IOException, InterruptedException {
        String response = storyAction("{\"action\":\"projects\"}");
        Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(response));
        List<StoryProject> projects = new ArrayList<>();
        for (Object item : SimpleJson.asArray(root.get("projects"))) {
            Map<String, Object> project = SimpleJson.asObject(item);
            projects.add(new StoryProject(
                    SimpleJson.asString(project.get("id")),
                    SimpleJson.asString(project.get("name")),
                    Boolean.TRUE.equals(project.get("template"))
            ));
        }
        return projects;
    }

    public List<StoryFile> listFiles(String projectId) throws IOException, InterruptedException {
        String response = storyAction("{\"action\":\"list\",\"projectId\":\"" + escapeJson(projectId) + "\"}");
        Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(response));
        List<StoryFile> files = new ArrayList<>();
        for (Object item : SimpleJson.asArray(root.get("files"))) {
            Map<String, Object> file = SimpleJson.asObject(item);
            files.add(new StoryFile(
                    SimpleJson.asString(file.get("path")),
                    SimpleJson.asString(file.get("layer")),
                    SimpleJson.asString(file.get("name")),
                    asLong(file.get("size")),
                    SimpleJson.asString(file.get("modified"))
            ));
        }
        return files;
    }

    public String readFile(String projectId, String path) throws IOException, InterruptedException {
        String response = storyAction("{\"action\":\"read\",\"projectId\":\"" + escapeJson(projectId) + "\",\"path\":\"" + escapeJson(path) + "\"}");
        Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(response));
        return SimpleJson.asString(root.get("content"));
    }

    public String writeFile(String projectId, String path, String content) throws IOException, InterruptedException {
        return storyAction("{\"action\":\"write\",\"projectId\":\"" + escapeJson(projectId) + "\",\"path\":\"" + escapeJson(path) + "\",\"content\":\"" + escapeJson(content) + "\"}");
    }

    public String bootstrapStory(
            String projectId,
            String title,
            String genre,
            String premise,
            String protagonist,
            String setting,
            String conflict,
            String tone
    ) throws IOException, InterruptedException {
        String payload = "{"
                + "\"action\":\"bootstrap\","
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"genre\":\"" + escapeJson(genre) + "\","
                + "\"premise\":\"" + escapeJson(premise) + "\","
                + "\"protagonist\":\"" + escapeJson(protagonist) + "\","
                + "\"setting\":\"" + escapeJson(setting) + "\","
                + "\"conflict\":\"" + escapeJson(conflict) + "\","
                + "\"tone\":\"" + escapeJson(tone) + "\""
                + "}";
        return storyAction(payload);
    }

    public String synthesizeProject(String projectId, String outputLanguage, List<StoryDocument> documents) throws IOException, InterruptedException {
        String payload = "{"
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"outputLanguage\":\"" + escapeJson(outputLanguage) + "\","
                + "\"documents\":" + documentsJson(documents)
                + "}";
        return post("/webhook/schreib-ai/synthesize", payload);
    }

    public String analyzeDocument(String projectId, String outputLanguage, String change, String chapter, String timelineDate, List<StoryDocument> documents) throws IOException, InterruptedException {
        String payload = "{"
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"outputLanguage\":\"" + escapeJson(outputLanguage) + "\","
                + "\"change_type\":\"document_or_story_change\","
                + "\"change\":\"" + escapeJson(change) + "\","
                + "\"chapter\":\"" + escapeJson(chapter) + "\","
                + "\"timeline_date\":\"" + escapeJson(timelineDate) + "\","
                + "\"documents\":" + documentsJson(documents)
                + "}";
        return post("/webhook/schreib-ai/analyze", payload);
    }

    public String continueStory(String projectId, String outputLanguage, String target, String prompt, String steps, List<StoryDocument> documents) throws IOException, InterruptedException {
        String payload = "{"
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"outputLanguage\":\"" + escapeJson(outputLanguage) + "\","
                + "\"target\":\"" + escapeJson(target) + "\","
                + "\"prompt\":\"" + escapeJson(prompt) + "\","
                + "\"steps\":\"" + escapeJson(steps) + "\","
                + "\"documents\":" + documentsJson(documents)
                + "}";
        return post("/webhook/schreib-ai/continue", payload);
    }

    public String finalizeChapter(
            String projectId,
            String outputLanguage,
            String sourcePath,
            String sourceContent,
            String instruction,
            boolean reviewExistingChapter,
            List<StoryDocument> documents
    ) throws IOException, InterruptedException {
        String payload = "{"
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"outputLanguage\":\"" + escapeJson(outputLanguage) + "\","
                + "\"sourcePath\":\"" + escapeJson(sourcePath) + "\","
                + "\"sourceContent\":\"" + escapeJson(sourceContent) + "\","
                + "\"instruction\":\"" + escapeJson(instruction) + "\","
                + "\"mode\":\"" + (reviewExistingChapter ? "review" : "promote") + "\","
                + "\"documents\":" + documentsJson(documents)
                + "}";
        return post("/webhook/schreib-ai/finalize", payload);
    }

    public String syncQdrantMemory(String projectId, List<StoryDocument> documents) throws IOException, InterruptedException {
        String payload = "{"
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"documents\":" + documentsJson(documents)
                + "}";
        return post("/webhook/schreib-ai/qdrant-sync", payload);
    }

    private String storyAction(String payload) throws IOException, InterruptedException {
        return post("/webhook/schreib-ai/story", payload);
    }

    private String post(String path, String payload) throws IOException, InterruptedException {
        if (!isConfigured()) {
            throw new IOException("Schreib-AI Workflow-URL fehlt in der Konfiguration");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Schreib-AI Workflow nicht erreichbar (" + baseUrl + path + "): HTTP "
                    + response.statusCode() + "\n" + response.body());
        }
        return response.body();
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(SimpleJson.asString(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String stripTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String escapeJson(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String documentsJson(List<StoryDocument> documents) {
        StringBuilder json = new StringBuilder("[");
        List<StoryDocument> safeDocuments = documents == null ? List.of() : documents;
        for (int i = 0; i < safeDocuments.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            StoryDocument document = safeDocuments.get(i);
            json.append('{')
                    .append("\"path\":\"").append(escapeJson(document.path())).append("\",")
                    .append("\"content\":\"").append(escapeJson(document.content())).append("\"")
                    .append('}');
        }
        json.append(']');
        return json.toString();
    }
}

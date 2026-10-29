package kioberflaeche.story;

import kioberflaeche.util.SimpleJson;
import kioberflaeche.ai.ModelRouting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

public class SchreibAiWorkflowClient {
    public record StoryDocument(String path, String content) {
    }

    public record ProposedChange(String action, String path, String content) {}
    public record Result(String raw, String summary, List<ProposedChange> changes, String warning) {}

    public static final class WorkflowFailure extends IOException {
        private final String response;
        public WorkflowFailure(String message, String response) {
            super(message);
            this.response = response;
        }
        public String response() { return response; }
    }

    public Result buildContext(String projectId, String language, String instruction,
                               List<StoryDocument> sources, List<StoryDocument> contextDocuments) throws IOException, InterruptedException {
        return buildContext(projectId,language,instruction,sources,contextDocuments,"auto");
    }

    public Result buildContext(String projectId, String language, String instruction,
                               List<StoryDocument> sources, List<StoryDocument> contextDocuments, String modelSelection) throws IOException, InterruptedException {
        String payload = "{\"projectId\":\"" + escapeJson(projectId) + "\",\"outputLanguage\":\"" + escapeJson(language)
                + "\",\"instruction\":\"" + escapeJson(instruction) + "\",\"documents\":" + documentsJson(sources)
                + ",\"contextDocuments\":" + documentsJson(contextDocuments) + ",\"modelSelection\":\""+escapeJson(ModelRouting.normalize(modelSelection))+"\"}";
        return verifiedResult(post("/webhook/schreib-ai/synthesize", payload), projectId, modelSelection);
    }

    public Result writeScene(String projectId, String language, String instruction, String target,
                             List<StoryDocument> sources, List<StoryDocument> contextDocuments) throws IOException, InterruptedException {
        return writeScene(projectId,language,instruction,target,sources,contextDocuments,"auto");
    }

    public Result writeScene(String projectId, String language, String instruction, String target,
                             List<StoryDocument> sources, List<StoryDocument> contextDocuments, String modelSelection) throws IOException, InterruptedException {
        String payload = "{\"projectId\":\"" + escapeJson(projectId) + "\",\"outputLanguage\":\"" + escapeJson(language)
                + "\",\"prompt\":\"" + escapeJson(instruction) + "\",\"target\":\"" + escapeJson(target)
                + "\",\"documents\":" + documentsJson(sources) + ",\"contextDocuments\":" + documentsJson(contextDocuments) + ",\"modelSelection\":\""+escapeJson(ModelRouting.normalize(modelSelection))+"\"}";
        return verifiedResult(post("/webhook/schreib-ai/continue", payload), projectId, modelSelection);
    }

    private static Result verifiedResult(String raw,String projectId,String selection) throws WorkflowFailure {
        Result result=parseResult(raw,projectId);
        try { ModelRouting.verify(raw,selection); } catch(IOException error) { throw new WorkflowFailure(error.getMessage(),raw); }
        return result;
    }

    public static Result parseResult(String body, String expectedProject) throws WorkflowFailure {
        Map<String, Object> root;
        try {
            Object parsed = SimpleJson.parse(body);
            if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("not object");
            root = SimpleJson.asObject(parsed);
        } catch (RuntimeException error) {
            throw new WorkflowFailure("Der Workflow hat kein lesbares Story-Ergebnis geliefert.", body);
        }
        String status = SimpleJson.asString(root.get("status"));
        if (Boolean.FALSE.equals(root.get("ok")) || root.get("error") != null
                || status.equals("error") || status.startsWith("blocked")) {
            throw new WorkflowFailure(failureMessage(root), body);
        }
        String returnedProject = SimpleJson.asString(root.get("projectId"));
        if (!returnedProject.isBlank() && !returnedProject.equals(expectedProject)) {
            throw new WorkflowFailure("Die Workflow-Antwort gehoert zu einem anderen Projekt. Es wurde nichts uebernommen.", body);
        }
        if (!(root.get("file_changes") instanceof List<?> items)) {
            throw new WorkflowFailure("Der Workflow hat keine pruefbare Liste von Dateivorschlaegen geliefert.", body);
        }
        List<ProposedChange> changes = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (Object item : items) {
            Map<String, Object> change = SimpleJson.asObject(item);
            if (!(change.get("path") instanceof String path) || path.isBlank()
                    || !(change.get("content") instanceof String content)) {
                throw new WorkflowFailure("Ein Dateivorschlag hat keinen gueltigen Pfad oder Text. Die Antwort bleibt als Zwischenstand erhalten.", body);
            }
            String normalized = path.replace('\\', '/');
            String key;
            try { key = java.nio.file.Path.of(normalized).normalize().toString().toLowerCase(Locale.ROOT); }
            catch (RuntimeException error) { throw new WorkflowFailure("Ein vorgeschlagener Dateipfad ist ungueltig.", body); }
            if (!targets.add(key)) throw new WorkflowFailure("Die Antwort enthaelt mehrere Vorschlaege fuer dieselbe Datei; bitte den Auftrag genauer formulieren.", body);
            String action = SimpleJson.asString(change.get("action"));
            if (action.isBlank()) action = "upsert";
            if (!Set.of("upsert", "write", "review", "create", "update", "replace").contains(action)) {
                throw new WorkflowFailure("Nicht unterstuetzte Dateiaenderung: " + action + ". Es wurde nichts uebernommen.", body);
            }
            changes.add(new ProposedChange(action, normalized, content));
        }
        String summary = SimpleJson.asString(root.get("summary"));
        if (summary.isBlank()) summary = changes.isEmpty() ? "Keine Dateiaenderungen vorgeschlagen." : changes.size() + " Dateivorschlaege zur Pruefung bereit.";
        String warning = root.get("warning") instanceof String text ? text.strip() : "";
        if (warning.isBlank() && (Boolean.TRUE.equals(root.get("degraded")) || Boolean.TRUE.equals(root.get("fallback")))) {
            warning = "Der Workflow meldet ein eingeschraenktes Ergebnis. Details und Abdeckung bitte pruefen.";
        }
        if (warning.codePointCount(0,warning.length()) > 700) warning=warning.substring(0,warning.offsetByCodePoints(0,697))+"...";
        return new Result(body, summary, List.copyOf(changes), warning);
    }

    private static String failureMessage(Map<String, Object> root) {
        String message = SimpleJson.asString(root.get("message"));
        if (message.isBlank()) message = SimpleJson.asString(SimpleJson.asObject(root.get("error")).get("message"));
        if (message.isBlank()) message = "Der Workflow konnte den Auftrag nicht vollstaendig ausfuehren. Es wurde nichts uebernommen.";
        return message.length() > 700 ? message.substring(0, 700) + "..." : message;
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
                .timeout(path.endsWith("/synthesize") || path.endsWith("/continue")
                        ? requestTimeout.compareTo(Duration.ofHours(12).plusMinutes(5)) < 0 ? Duration.ofHours(12).plusMinutes(5) : requestTimeout
                        : requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "Schreib-AI Auftrag fehlgeschlagen: HTTP " + response.statusCode();
            try { message += " – " + failureMessage(SimpleJson.asObject(SimpleJson.parse(response.body()))); }
            catch (RuntimeException ignored) { /* The raw response stays in the task evidence, not in the status. */ }
            throw new WorkflowFailure(message, response.body());
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
        String encoded=StoryReviewStore.json(value==null?"":value);
        return encoded.substring(1,encoded.length()-1);
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

package kioberflaeche.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Project credentials remain in memory; all requests use fixed routes on one configured origin. */
public final class AgentClient {
    public static final int MAX_RESPONSE = 262144;
    public static final int MAX_DIAGRAM_RESPONSE = 12 * 1024 * 1024;
    public static final int MAX_PNG = 8 * 1024 * 1024;
    private static final String ID = "[0-9a-f]{32}";
    private static final String HASH = "[0-9a-f]{64}";
    private static final String DIAGRAM_ID = "[a-z][a-z0-9_-]{0,47}";
    private static final Set<String> STATUSES = Set.of("held", "queued", "running", "awaiting_test", "awaiting_review",
            "cancel_requested", "recovery_required", "completed", "cancelled", "failed", "exhausted");
    private final URI base;
    private final String token;
    private final HttpClient http;

    public record Project(String projectId, String profile, List<String> models, boolean uml, boolean apply, String qualityProfile) {
        @Override public String toString() { return projectId; }
    }
    public record DocumentReference(String contextId, long revision, String sha256) {}
    public record RequiredDiagram(String id, String type) {}
    public record StartRequest(String projectId, String task, String requestKey, String modelSelection,
            List<DocumentReference> documentReferences, boolean umlEnabled, List<RequiredDiagram> requiredDiagrams,
            int modelSeconds, int actions, int rounds) {}
    public record Job(String jobId, String projectId, String status, String phase, String errorCode,
            String modelSelection, long modelSeconds, long usedMilliseconds, long remainingMilliseconds,
            long reservedMilliseconds, int actionBudget, int actionsUsed, boolean reviewAvailable,
            boolean cancelRequested, Quality quality, Diagnostics diagnostics, ActionUsage actionUsage) {
        public boolean active() { return Set.of("queued", "running", "awaiting_test", "cancel_requested").contains(status); }
    }
    public record StageEvidence(String id, String status, Map<String,String> hashes, Map<String,Long> counts) {}
    public record Diagnostics(List<StageEvidence> stages) {}
    public record UmlUsage(String diagramId, String diagramType, int attemptsUsed, int attemptBudget) {}
    public record ActionUsage(String policy, int normalBudget, int normalUsed, int totalBudget, int totalUsed,
                              int umlBudget, List<UmlUsage> diagrams) {}
    public record QueueEntry(String entryId, String jobId, String status, String jobStatus,
                             String taskPreview, String modelSelection, long createdAt) {
        public boolean waiting() { return status.equals("waiting"); }
        @Override public String toString() {
            return (waiting() ? "Wartend" : "Freigegeben · " + jobStatus) + " · " + taskPreview;
        }
    }
    public record TaskQueue(String projectId, boolean paused, int waitingCount,
                            List<QueueEntry> items, List<QueueEntry> activeItems, String lastError, String blockedEntryId) {}
    public record Quality(String profile, String status, String acceptance, List<String> failedChecks,
            String runnerStatus, boolean behaviorTestsExecuted, String reportSha256) {}
    public record Check(String name, String status, int exitCode) {}
    public record DiagramInfo(String id, String type, int width, int height, String pngSha256) {
        @Override public String toString() { return id + " · " + (type.equals("object") ? "Objektdiagramm" : "Klassendiagramm"); }
    }
    public record Review(Job job, String reviewId, String diffSha256, String diff, List<Check> checks,
            List<String> operations, List<String> warnings, List<DiagramInfo> diagrams,
            boolean adoptionAvailable, String confirmation, Quality quality) {}
    public record Diagram(DiagramInfo info, byte[] png) {
        public Diagram { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
    public record AdoptionResult(Job job, String status, String reviewId, String transactionId) {}

    public static final class Failure extends IOException {
        private final int status;
        private final String code;
        public Failure(int status, String code) {
            super(message(status, code));
            this.status = status; this.code = code;
        }
        public int status() { return status; }
        public String code() { return code; }
    }

    public AgentClient(String baseUrl, String projectToken) throws IOException {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", ""));
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath().contains("%") || uri.getRawPath().contains("..")) throw new IllegalArgumentException();
            base = uri;
        } catch (RuntimeException e) { throw new IOException("Die Agentenadresse ist ungültig."); }
        token = validToken(projectToken);
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build();
    }

    public List<Project> projects() throws IOException, InterruptedException {
        Map<String,Object> root = request("GET", "/projects", null, token, MAX_RESPONSE, 200);
        List<Project> projects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : array(root.get("projects"), 100)) {
            var p = object(item);
            String id = matching(p.get("projectId"), "[a-z][a-z0-9-]{0,39}");
            if (!seen.add(id)) throw protocol();
            var capabilities = p.get("capabilities") == null ? Map.<String,Object>of() : object(p.get("capabilities"));
            List<String> models = new ArrayList<>();
            for (Object model : array(p.get("models"), 100)) models.add(text(model, 100));
            projects.add(new Project(id, text(p.get("profile"), 80), List.copyOf(models),
                    Boolean.TRUE.equals(capabilities.get("uml")), Boolean.TRUE.equals(capabilities.get("apply")),
                    p.get("qualityProfile") == null ? "" : text(p.get("qualityProfile"), 80)));
        }
        return List.copyOf(projects);
    }

    public Job start(StartRequest value) throws IOException, InterruptedException {
        var body = startBody(value);
        Job job = job(request("POST", "/jobs", body, token, MAX_RESPONSE, 202).get("job"));
        if (!job.projectId().equals(value.projectId())) throw protocol();
        return job;
    }
    public Job status(String jobId) throws IOException, InterruptedException {
        return boundJob(request("GET", jobPath(jobId), null, token, MAX_RESPONSE, 200), jobId);
    }
    public TaskQueue queue(String projectId) throws IOException, InterruptedException {
        return boundQueue(request("GET", "/queue", null, token, MAX_RESPONSE, 200), projectId);
    }
    public TaskQueue enqueue(StartRequest value) throws IOException, InterruptedException {
        return boundQueue(request("POST", "/queue/add", startBody(value), token, MAX_RESPONSE, 202), value.projectId());
    }
    public TaskQueue removeQueued(String projectId, String entryId) throws IOException, InterruptedException {
        return boundQueue(request("POST", "/queue/" + matching(entryId, ID) + "/remove", Map.of(), token, MAX_RESPONSE, 200), projectId);
    }
    public TaskQueue queueCommand(String projectId, String operation, String requestKey) throws IOException, InterruptedException {
        if (!Set.of("clear", "pause", "resume", "start-next").contains(operation)) throw protocol();
        var body = Map.<String,Object>of("requestKey", matching(requestKey, "[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}"));
        return boundQueue(request("POST", "/queue/" + operation, body, token, MAX_RESPONSE, 200), projectId);
    }
    private static TaskQueue boundQueue(Map<String,Object> root, String projectId) throws IOException {
        matching(projectId, "[a-z][a-z0-9-]{0,39}");
        var q = object(root.get("queue"));
        if (!projectId.equals(q.get("projectId"))) throw protocol();
        List<QueueEntry> waiting = new ArrayList<>(), active = new ArrayList<>();
        Set<String> seenEntries = new HashSet<>(), seenJobs = new HashSet<>();
        for (String field : List.of("items", "activeItems")) {
            for (Object raw : array(q.get(field), 100)) {
                var item = object(raw);
                String status = text(item.get("status"), 30);
                if (!projectId.equals(item.get("projectId"))) throw protocol();
                boolean isWaiting = field.equals("items");
                if (!status.equals(isWaiting ? "waiting" : "released")) throw protocol();
                String jobStatus = item.get("jobStatus") == null ? "" : text(item.get("jobStatus"), 40);
                if (isWaiting ? !jobStatus.equals("held") : !Set.of("queued", "running", "awaiting_test", "cancel_requested", "recovery_required").contains(jobStatus)) throw protocol();
                var entry = new QueueEntry(matching(item.get("entryId"), ID), matching(item.get("jobId"), ID), status,
                        jobStatus, text(item.get("taskPreview"), 500), text(item.get("modelSelection"), 100), epochSeconds(item.get("createdAt")));
                if (!seenEntries.add(entry.entryId()) || !seenJobs.add(entry.jobId())) throw protocol();
                (isWaiting ? waiting : active).add(entry);
            }
        }
        int count = integer(q.get("waitingCount"), 0, 100);
        if (count != waiting.size()) throw protocol();
        String lastError = q.get("lastError") == null ? "" : matching(q.get("lastError"), "[a-z][a-z0-9_]{0,99}");
        String blocked = q.get("blockedEntryId") == null ? "" : matching(q.get("blockedEntryId"), ID);
        if (!blocked.isEmpty() && waiting.stream().noneMatch(e -> e.entryId().equals(blocked))) throw protocol();
        return new TaskQueue(projectId, bool(q.get("paused")), count, List.copyOf(waiting), List.copyOf(active), lastError, blocked);
    }
    private static long epochSeconds(Object value) throws IOException {
        // The service exposes Unix seconds with millisecond fractions; display rounds down only the date.
        if (!(value instanceof Number n)) throw protocol();
        double seconds = n.doubleValue();
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 100000000000L) throw protocol();
        return (long)seconds;
    }
    public Job cancel(String jobId) throws IOException, InterruptedException {
        return boundJob(request("POST", jobPath(jobId) + "/cancel", Map.of(), token, MAX_RESPONSE, 202), jobId);
    }
    public Job resume(String jobId) throws IOException, InterruptedException {
        return boundJob(request("POST", jobPath(jobId) + "/resume", Map.of(), token, MAX_RESPONSE, 202), jobId);
    }
    public Review review(String jobId) throws IOException, InterruptedException {
        var root = request("GET", jobPath(jobId) + "/review", null, token, MAX_RESPONSE, 200);
        Job job = boundJob(root, jobId);
        var r = object(root.get("review"));
        boolean applied = job.status().equals("completed");
        if ((!job.status().equals("awaiting_review") && !applied) || !job.projectId().equals(text(r.get("projectId"), 80))
                || bool(r.get("approvalGranted")) != applied) throw protocol();
        String reviewId = matching(r.get("reviewId"), HASH);
        String diff = text(r.get("diff"), MAX_RESPONSE);
        String hash = matching(r.get("diffSha256"), HASH);
        if (!sha256(diff.getBytes(StandardCharsets.UTF_8)).equals(hash)) throw protocol();
        List<Check> checks = new ArrayList<>();
        for (Object item : array(r.get("checks"), 100)) {
            var check = object(item);
            String name = check.get("id") instanceof String ? text(check.get("id"), 160)
                    : check.get("name") instanceof String ? text(check.get("name"), 160) : "Prüfung";
            checks.add(new Check(name, text(check.get("status"), 30), integer(check.get("exit_code"), -255, 255)));
        }
        List<String> operations = new ArrayList<>();
        for (Object item : array(r.get("operations"), 100)) {
            var operation = object(item);
            String path = text(operation.get("path"), 512);
            if (path.startsWith("/") || path.contains("\\") || path.contains("..") || path.contains(":")) throw protocol();
            String action = operation.get("kind") instanceof String ? text(operation.get("kind"), 40)
                    : operation.get("action") instanceof String ? text(operation.get("action"), 40) : "Änderung";
            operations.add(action + ": " + path);
        }
        List<String> warnings = new ArrayList<>();
        for (Object item : array(r.getOrDefault("warnings", List.of()), 100)) warnings.add(text(item, 4000));
        List<DiagramInfo> diagrams = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : array(r.getOrDefault("diagrams", List.of()), 8)) {
            DiagramInfo info = diagramInfo(item);
            if (!ids.add(info.id())) throw protocol();
            diagrams.add(info);
        }
        var adoption = r.get("adoption") == null ? Map.<String,Object>of() : object(r.get("adoption"));
        boolean available = Boolean.TRUE.equals(adoption.get("available"));
        if (applied && available) throw protocol();
        String confirmation = available ? text(adoption.get("confirmation"), 200) : "";
        if (available && !confirmation.contains(reviewId)) throw protocol();
        Quality quality = r.get("quality") == null ? job.quality() : quality(r.get("quality"));
        if (!Objects.equals(quality, job.quality())) throw protocol();
        if (available && quality != null && !quality.status().equals("passed")) throw protocol();
        return new Review(job, reviewId, hash, diff, List.copyOf(checks), List.copyOf(operations),
                List.copyOf(warnings), List.copyOf(diagrams), available, confirmation, quality);
    }

    public Diagram diagram(String jobId, DiagramInfo expected) throws IOException, InterruptedException {
        var root = request("GET", jobPath(jobId) + "/diagrams/" + matching(expected.id(), DIAGRAM_ID),
                null, token, MAX_DIAGRAM_RESPONSE, 200);
        var data = object(root.get("diagram"));
        DiagramInfo info = diagramInfo(data);
        if (!info.equals(expected)) throw protocol();
        byte[] png;
        try { png = Base64.getDecoder().decode(text(data.get("pngBase64"), 12 * 1024 * 1024)); }
        catch (IllegalArgumentException e) { throw protocol(); }
        if (png.length > MAX_PNG || png.length < 33 || !sha256(png).equals(expected.pngSha256())) throw protocol();
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (!Arrays.equals(Arrays.copyOf(png, 8), signature)
                || ByteBuffer.wrap(png,8,4).getInt() != 13
                || !Arrays.equals(Arrays.copyOfRange(png,12,16), new byte[]{73,72,68,82})
                || ByteBuffer.wrap(png,16,4).getInt() != info.width()
                || ByteBuffer.wrap(png,20,4).getInt() != info.height()) throw protocol();
        return new Diagram(info, png);
    }

    public AdoptionResult apply(Review review, String operatorToken, String operator, String confirmation)
            throws IOException, InterruptedException {
        if (!review.adoptionAvailable() || !review.confirmation().equals(confirmation)) throw new IOException("Die Freigabe muss zum angezeigten Review passen.");
        String actor = operator == null ? "" : operator.strip();
        if (actor.isEmpty() || actor.length() > 80 || actor.chars().anyMatch(c -> c < 32)) throw new IOException("Bitte einen gültigen Prüfernamen angeben.");
        var body = Map.of("reviewId", review.reviewId(), "diffSha256", review.diffSha256(),
                "confirmation", confirmation, "operator", actor);
        var root = request("POST", jobPath(review.job().jobId()) + "/apply", body,
                validToken(operatorToken), MAX_RESPONSE, 200);
        Job job = boundJob(root, review.job().jobId());
        if (!job.projectId().equals(review.job().projectId()) || !job.status().equals("completed") || job.cancelRequested()) throw protocol();
        var adoption = object(root.get("adoption"));
        String returnedReview = matching(adoption.get("reviewId"), HASH);
        String status = text(adoption.get("status"), 30);
        if (!returnedReview.equals(review.reviewId()) || !status.equals("applied")) throw protocol();
        return new AdoptionResult(job, status, returnedReview, matching(adoption.get("transactionId"), ID));
    }

    public static Map<String,Object> startBody(StartRequest request) throws IOException {
        if (request == null) throw new IOException("Auftrag fehlt.");
        matching(request.projectId(), "[a-z][a-z0-9-]{0,39}");
        matching(request.requestKey(), "[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}");
        String task = request.task() == null ? "" : request.task();
        if (task.isBlank() || task.length() > 16000) throw new IOException("Bitte eine Aufgabe mit höchstens 16.000 Zeichen angeben.");
        String model = matching(request.modelSelection(), "[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");
        if (request.modelSeconds() < 1 || request.modelSeconds() > 7200 || request.actions() < 1 || request.actions() > 15
                || request.rounds() < 0 || request.rounds() > 2) throw new IOException("Das Auftragsbudget ist ungültig.");
        if (request.documentReferences() == null || request.documentReferences().size() > 8
                || request.requiredDiagrams() == null || request.requiredDiagrams().size() > 4) throw protocol();
        List<Object> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DocumentReference reference : request.documentReferences()) {
            String id = matching(reference.contextId(), ID);
            if (reference.revision() < 1 || !seen.add(id)) throw new IOException("Dokumentreferenzen müssen eindeutig und vollständig sein.");
            refs.add(Map.of("context_id", id, "revision", reference.revision(), "sha256", matching(reference.sha256(), HASH)));
        }
        List<Object> diagrams = new ArrayList<>();
        seen.clear();
        for (RequiredDiagram diagram : request.requiredDiagrams()) {
            String id = matching(diagram.id(), DIAGRAM_ID);
            if (!seen.add(id) || !Set.of("class","object").contains(diagram.type())) throw new IOException("Diagrammkennungen und Typen prüfen.");
            diagrams.add(Map.of("id", id, "type", diagram.type()));
        }
        if (!request.umlEnabled() && !diagrams.isEmpty()) throw new IOException("Für Pflichtdiagramme die UML-Funktion einschalten.");
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("projectId", request.projectId()); body.put("task", task); body.put("requestKey", request.requestKey());
        body.put("modelSelection", model); body.put("documentReferences", refs);
        body.put("umlEnabled", request.umlEnabled()); body.put("requiredDiagrams", diagrams);
        body.put("modelSeconds", request.modelSeconds()); body.put("actions", request.actions()); body.put("rounds", request.rounds());
        return body;
    }

    private Map<String,Object> request(String method, String suffix, Object body, String authorization, int limit, int expected)
            throws IOException, InterruptedException {
        byte[] payload = body == null ? null : AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
        if (payload != null && payload.length > 65536) throw new IOException("Der Auftrag ist für diese Schnittstelle zu groß.");
        URI uri = URI.create(base.toString() + "/webhook/schreib-ai/agent" + suffix);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", "Bearer " + authorization).header("Accept", "application/json");
        if (payload == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        HttpResponse<byte[]> response;
        // Bound the complete body as well as connection/headers; this is not a job deadline.
        var exchange = http.sendAsync(builder.build(), info -> new LimitedBody(limit));
        try { response = exchange.get(25, TimeUnit.SECONDS); }
        catch (InterruptedException e) { exchange.cancel(true); throw e; }
        catch (ExecutionException | TimeoutException e) {
            exchange.cancel(true);
            throw new IOException("Agentendienst nicht erreichbar oder Antwort unvollständig. Der Auftrag kann weiterlaufen; Status erneut prüfen.");
        }
        Map<String,Object> root;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            root = object(AgentJson.parse(decoded));
        } catch (RuntimeException | java.nio.charset.CharacterCodingException e) { throw protocol(); }
        if (response.statusCode() != expected || !Boolean.TRUE.equals(root.get("ok"))) {
            String code = "request_failed";
            if (root.get("error") instanceof Map<?,?> error && error.get("code") instanceof String value
                    && value.matches("[a-z][a-z0-9_]{0,99}")) code = value;
            throw new Failure(response.statusCode(), code);
        }
        return root;
    }

    private static Job boundJob(Map<String,Object> root, String expectedId) throws IOException {
        Job value = job(root.get("job"));
        if (!value.jobId().equals(expectedId)) throw protocol();
        return value;
    }
    private static String jobPath(String jobId) throws IOException { return "/jobs/" + matching(jobId, ID); }
    private static Job job(Object value) throws IOException {
        var j = object(value);
        String status = text(j.get("status"), 40);
        if (!STATUSES.contains(status)) throw protocol();
        String error = j.get("errorCode") == null ? "" : matching(j.get("errorCode"), "[a-z][a-z0-9_]{0,99}");
        return new Job(matching(j.get("jobId"), ID), matching(j.get("projectId"), "[a-z][a-z0-9-]{0,39}"),
                status, j.get("phase") == null ? "" : text(j.get("phase"), 80), error, text(j.get("modelSelection"), 100),
                number(j.get("modelSeconds"), 1,7200), number(j.get("modelMillisecondsUsed"),0,7200000),
                number(j.get("modelMillisecondsRemaining"),0,7200000), number(j.get("modelMillisecondsReserved"),0,7200000),
                integer(j.get("actionBudget"),1,15), integer(j.get("actionsUsed"),0,27),
                bool(j.get("reviewAvailable")), bool(j.get("cancelRequested")),
                j.get("quality") == null ? null : quality(j.get("quality")),
                j.get("diagnostics") == null ? null : diagnostics(j.get("diagnostics")), actionUsage(j));
    }
    private static ActionUsage actionUsage(Map<String,Object> j) throws IOException {
        if (!j.containsKey("budgetPolicy")) return null;
        String policy = text(j.get("budgetPolicy"), 40);
        if (!Set.of("normal-plus-uml-v1", "legacy").contains(policy)) throw protocol();
        int normalBudget = integer(j.get("normalActionBudget"), 1, 15), normalUsed = integer(j.get("normalActionsUsed"), 0, normalBudget);
        int totalBudget = integer(j.get("totalActionBudget"), normalBudget, normalBudget + 12), totalUsed = integer(j.get("totalActionsUsed"), 0, totalBudget);
        if (totalUsed != integer(j.get("actionsUsed"), 0, 27) || normalBudget != integer(j.get("actionBudget"), 1, 15)) throw protocol();
        int umlBudget = integer(j.get("umlAttemptBudget"), 0, 3);
        List<UmlUsage> diagrams = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Object raw : array(j.get("umlAttempts"), 4)) {
            var item = object(raw); String id = matching(item.get("diagramId"), DIAGRAM_ID);
            String type = text(item.get("diagramType"), 10);
            int limit = integer(item.get("attemptBudget"), 0, 3);
            if (!ids.add(id) || !Set.of("class", "object").contains(type) || limit != umlBudget) throw protocol();
            diagrams.add(new UmlUsage(id, type, integer(item.get("attemptsUsed"), 0, limit), limit));
        }
        if (policy.equals("normal-plus-uml-v1") && (umlBudget != 3 || totalUsed != normalUsed + diagrams.stream().mapToInt(UmlUsage::attemptsUsed).sum())) throw protocol();
        return new ActionUsage(policy, normalBudget, normalUsed, totalBudget, totalUsed, umlBudget, List.copyOf(diagrams));
    }
    private static Diagnostics diagnostics(Object value) throws IOException {
        var report = object(value);
        if (!"agent-stage-overview-v1".equals(report.get("schema_version"))
                || bool(report.get("approval_granted")) || bool(report.get("model_claims_used_as_evidence"))) throw protocol();
        var stages = object(report.get("stages"));
        List<StageEvidence> result = new ArrayList<>();
        List<String> ids = List.of("sources", "action_schema", "rendering", "proposal", "compilation",
                "behavior_tests", "task_acceptance", "adoption");
        Set<String> statuses = Set.of("not_executed", "not_required", "partial", "passed", "failed");
        List<String> hashKeys = List.of("sha256", "report_sha256", "schema_sha256", "patch_sha256",
                "request_sha256", "runner_result_sha256", "supervisor_sha256", "proposal_sha256", "transaction_sha256");
        List<String> countKeys = List.of("required_count", "read_count", "context_bound_count", "request_count",
                "failure_count", "artifact_count", "attempt_count", "file_count", "rejection_count");
        for (String id : ids) {
            var stage = object(stages.get(id));
            String status = text(stage.get("status"), 30);
            if (!statuses.contains(status)) throw protocol();
            Map<String,String> hashes = new LinkedHashMap<>();
            for (String key : hashKeys) if (stage.containsKey(key)) hashes.put(key, matching(stage.get(key), HASH));
            int diagramIndex = 0;
            for (Object item : array(stage.getOrDefault("hashes", List.of()), 20)) {
                var diagram = object(item);
                ++diagramIndex;
                for (String key : List.of("spec_sha256", "source_sha256", "png_sha256")) {
                    hashes.put("diagram_" + diagramIndex + "_" + key, matching(diagram.get(key), HASH));
                }
            }
            Map<String,Long> counts = new LinkedHashMap<>();
            for (String key : countKeys) if (stage.containsKey(key)) counts.put(key, number(stage.get(key), 0, 1000000));
            // Unknown text fields are deliberately not retained or displayed.
            result.add(new StageEvidence(id, status, Collections.unmodifiableMap(hashes), Collections.unmodifiableMap(counts)));
        }
        return new Diagnostics(List.copyOf(result));
    }
    private static Quality quality(Object value) throws IOException {
        var q = object(value);
        String status = text(q.get("status"), 30), acceptance = text(q.get("acceptance"), 50);
        if (!Set.of("passed", "failed").contains(status)
                || !acceptance.equals(status.equals("passed") ? "bounded_profile_passed" : "not_accepted")) throw protocol();
        List<String> failed = new ArrayList<>();
        for (Object item : array(q.get("failedChecks"), 100)) failed.add(text(item, 160));
        if (status.equals("passed") && !failed.isEmpty()) throw protocol();
        return new Quality(text(q.get("profile"), 80), status, acceptance, List.copyOf(failed),
                text(q.get("runnerStatus"), 40), bool(q.get("behaviorTestsExecuted")), matching(q.get("reportSha256"), HASH));
    }
    private static DiagramInfo diagramInfo(Object value) throws IOException {
        var d = object(value);
        String type = text(d.get("type"),10);
        if (!Set.of("object","class").contains(type)) throw protocol();
        int width = integer(d.get("width"),1,8192), height = integer(d.get("height"),1,8192);
        if ((long)width * height > 16000000) throw protocol();
        return new DiagramInfo(matching(d.get("id"),DIAGRAM_ID),type,width,height,matching(d.get("pngSha256"),HASH));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?,?> map)) throw protocol();
        return (Map<String,Object>)map;
    }
    private static List<?> array(Object value, int maximum) throws IOException {
        if (!(value instanceof List<?> list) || list.size() > maximum) throw protocol();
        return list;
    }
    private static String text(Object value, int maximum) throws IOException {
        if (!(value instanceof String s) || s.length() > maximum || s.indexOf('\0') >= 0) throw protocol();
        return s;
    }
    private static String matching(Object value, String regex) throws IOException {
        String s = text(value, Math.max(regex.length(), 160));
        if (!s.matches(regex)) throw protocol();
        return s;
    }
    private static boolean bool(Object value) throws IOException {
        if (!(value instanceof Boolean)) throw protocol();
        return (Boolean)value;
    }
    private static long number(Object value, long low, long high) throws IOException {
        try {
            long n = value instanceof java.math.BigDecimal b ? b.longValueExact() : ((Number)value).longValue();
            if (n < low || n > high) throw protocol();
            return n;
        } catch (RuntimeException e) { throw protocol(); }
    }
    private static int integer(Object value, int low, int high) throws IOException { return (int)number(value,low,high); }
    private static IOException protocol() { return new IOException("Die Agentenantwort ist unvollständig oder gehört nicht zum angeforderten Prüfstand."); }
    private static String validToken(String value) throws IOException {
        if (value == null || !value.matches("[!-~]{32,1024}")) throw new IOException("Bitte ein gültiges Zugangstoken ohne „Bearer “ eingeben.");
        return value;
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(); }
    }
    public static String message(int status, String code) {
        if (status == 401 || status == 403) return "Die Zugangsdaten erlauben diese Aktion nicht.";
        if (status == 404) return "Projekt, Auftrag oder Funktion ist mit diesem Zugang nicht verfügbar.";
        if (code.startsWith("context_")) return "Die Dokumentfreigabe ist nicht mehr aktuell oder wurde widerrufen. Freigabe prüfen; Abbruch bleibt möglich.";
        if (code.contains("recovery_not_required")) return "Dieser Auftrag benötigt keine Wiederaufnahme.";
        if (code.contains("idempotency")) return "Der Wiederholungsschlüssel gehört zu einem anderen Auftrag. Erst den bisherigen Status prüfen.";
        if (status == 409) return "Der Auftrag oder Prüfstand hat sich geändert. Status und Review erneut laden.";
        if (status == 413) return "Der Auftrag oder das Ergebnis überschreitet die erlaubte Größe.";
        return "Die Aktion konnte nicht abgeschlossen werden (HTTP " + status + "). Status prüfen, bevor ein neuer Auftrag angelegt wird.";
    }
    @Override public String toString() { return "AgentClient[credentials=***]"; }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int limit;
        Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                int size = value.remaining();
                if (size > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Agent response limit")); return;
                }
                byte[] chunk = new byte[size]; value.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

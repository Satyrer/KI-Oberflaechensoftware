import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import kioberflaeche.agent.AgentClient;
import kioberflaeche.agent.AgentJson;
import kioberflaeche.ai.ModelCatalogClient;
import kioberflaeche.controller.AgentPaneController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Explicit operator-run pilot: real FXML/buttons, real network, no fixture, no adoption. */
public final class AgentLivePilot {
    interface Work { void run() throws Exception; }
    record Configuration(String baseUrl, String projectId, String modelSelection, Path tokenFile,
                         String task, String references, String diagrams,
                         int modelSeconds, int actions, int rounds) {}
    private static FXMLLoader loader;
    private static Parent root;
    private static AgentPaneController controller;
    private static Path audit;
    private static String mode;

    static void fx(Work work) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { work.run(); return null; });
        Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }
    static void waitFor(BooleanSupplier condition, int seconds, String code) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicBoolean done = new AtomicBoolean();
            fx(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(100);
        }
        throw new PilotFailure(code);
    }
    static final class PilotFailure extends Exception {
        PilotFailure(String code) { super(code); }
    }
    @SuppressWarnings("unchecked") static <T> T node(String id) {
        Object value = loader.getNamespace().get(id);
        if (value == null) throw new IllegalStateException("missing_ui_field");
        return (T)value;
    }
    static void click(String id) throws PilotFailure {
        Button button = node(id);
        if (button.isDisabled()) throw new PilotFailure("disabled_" + id);
        button.fire();
    }
    // Test inspection only: requests and results always pass through the actual controller.
    static <T> T state(String name, Class<T> type) {
        try {
            Field field = AgentPaneController.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(controller));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("ui_state_unavailable"); }
    }
    static AgentClient.Job job() { return state("currentJob", AgentClient.Job.class); }
    static AgentClient.Review review() { return state("currentReview", AgentClient.Review.class); }
    static boolean idle() { return state("reads", Integer.class) == 0 && !state("commandBusy", Boolean.class); }
    static String text(Map<?,?> value, String key, int max) throws PilotFailure {
        Object result = value.get(key);
        if (!(result instanceof String s) || s.isBlank() || s.length() > max) throw new PilotFailure("invalid_" + key);
        return s;
    }
    static int number(Map<?,?> value, String key, int min, int max) throws PilotFailure {
        try {
            if (!(value.get(key) instanceof java.math.BigDecimal n)) throw new ArithmeticException();
            int result = n.intValueExact();
            if (result < min || result > max) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException error) { throw new PilotFailure("invalid_" + key); }
    }
    static String boundedRead(Path path, int limit) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new PilotFailure("input_file_limit");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    static Configuration configure(Path path) throws Exception {
        Object decoded = AgentJson.parse(boundedRead(path, 32_768));
        if (!(decoded instanceof Map<?,?> data)) throw new PilotFailure("configuration_shape");
        Set<String> allowed = Set.of("baseUrl", "projectId", "modelSelection", "tokenFile", "taskFile",
                "documentReferences", "requiredDiagrams", "modelSeconds", "actions", "rounds");
        if (!allowed.equals(data.keySet())) throw new PilotFailure("configuration_keys");
        String base = text(data, "baseUrl", 1000), project = text(data, "projectId", 40), model = text(data, "modelSelection", 100);
        if (!project.matches("[a-z][a-z0-9-]{0,39}")) throw new PilotFailure("invalid_projectId");
        Path tokenFile = Path.of(text(data, "tokenFile", 2000)).toAbsolutePath().normalize();
        Path taskFile = Path.of(text(data, "taskFile", 2000)).toAbsolutePath().normalize();
        for (Path part : tokenFile) if (part.toString().equalsIgnoreCase("Shared")) throw new PilotFailure("token_must_be_private");
        if (tokenFile.startsWith(audit)) throw new PilotFailure("token_must_be_outside_evidence");
        String task = boundedRead(taskFile, 16_384);
        StringBuilder references = new StringBuilder(), diagrams = new StringBuilder();
        if (!(data.get("documentReferences") instanceof List<?> refs) || refs.isEmpty() || refs.size() > 8)
            throw new PilotFailure("document_references_required");
        for (Object item : refs) {
            if (!(item instanceof Map<?,?> ref) || !ref.keySet().equals(Set.of("context_id", "revision", "sha256")))
                throw new PilotFailure("document_reference_shape");
            String id = text(ref, "context_id", 100), hash = text(ref, "sha256", 64);
            if (!id.matches("[0-9a-f]{32}") || !hash.matches("[0-9a-f]{64}")) throw new PilotFailure("document_reference_value");
            references.append(id).append(' ').append(number(ref, "revision", 1, Integer.MAX_VALUE)).append(' ').append(hash).append('\n');
        }
        if (!(data.get("requiredDiagrams") instanceof List<?> requirements) || requirements.isEmpty() || requirements.size() > 4)
            throw new PilotFailure("diagram_requirements_required");
        for (Object item : requirements) {
            if (!(item instanceof Map<?,?> req) || !req.keySet().equals(Set.of("id", "type"))) throw new PilotFailure("diagram_requirement_shape");
            String id = text(req, "id", 48), type = text(req, "type", 10);
            if (!id.matches("[a-z][a-z0-9_-]{0,47}") || !Set.of("class", "object").contains(type)) throw new PilotFailure("diagram_requirement_value");
            diagrams.append(id).append(' ').append(type).append('\n');
        }
        return new Configuration(base, project, model, tokenFile, task, references.toString(), diagrams.toString(),
                number(data, "modelSeconds", 1, 7200), number(data, "actions", 1, 10), number(data, "rounds", 0, 2));
    }
    static void write(String filename, Object data) throws Exception {
        Files.writeString(audit.resolve(filename), AgentJson.encode(data) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }
    static boolean emptyDirectory(Path path) throws Exception {
        if (!Files.isDirectory(path)) return false;
        try (var entries = Files.list(path)) { return entries.findAny().isEmpty(); }
    }
    static Map<String,Object> jobEvidence(AgentClient.Job j) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("jobId", j.jobId()); data.put("projectId", j.projectId()); data.put("status", j.status()); data.put("phase", j.phase());
        data.put("errorCode", j.errorCode()); data.put("modelSelection", j.modelSelection()); data.put("modelSeconds", j.modelSeconds());
        data.put("usedMilliseconds", j.usedMilliseconds()); data.put("remainingMilliseconds", j.remainingMilliseconds());
        data.put("reservedMilliseconds", j.reservedMilliseconds()); data.put("actionBudget", j.actionBudget());
        data.put("actionsUsed", j.actionsUsed()); data.put("reviewAvailable", j.reviewAvailable()); data.put("cancelRequested", j.cancelRequested());
        if (j.quality() != null) data.put("quality", qualityEvidence(j.quality()));
        return data;
    }
    static Map<String,Object> qualityEvidence(AgentClient.Quality quality) {
        return Map.of("profile", quality.profile(), "status", quality.status(), "acceptance", quality.acceptance(),
                "failedChecks", quality.failedChecks(), "runnerStatus", quality.runnerStatus(),
                "behaviorTestsExecuted", quality.behaviorTestsExecuted(), "reportSha256", quality.reportSha256());
    }
    static void snapshot(String filename) throws Exception {
        if (!((PasswordField)node("projectToken")).getText().isEmpty() || !((PasswordField)node("operatorToken")).getText().isEmpty())
            throw new PilotFailure("credential_field_not_empty");
        root.resize(1040, 900); root.applyCss(); root.layout();
        saveImage(root.snapshot(null, new WritableImage(1040, 900)), filename);
    }
    static void saveImage(Image pixels, String filename) throws Exception {
        int width = (int)pixels.getWidth(), height = (int)pixels.getHeight();
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 16_000_000)
            throw new PilotFailure("image_size");
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) output.setRGB(x, y, pixels.getPixelReader().getArgb(x, y));
        if (Files.exists(audit.resolve(filename))) throw new PilotFailure("evidence_already_exists");
        ImageIO.write(output, "png", audit.resolve(filename).toFile());
    }
    static void connect(Configuration config) throws Exception {
        String token = boundedRead(config.tokenFile(), 512).strip();
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new PilotFailure("invalid_private_token");
        fx(() -> { ((PasswordField)node("projectToken")).setText(token); click("connectButton"); });
        waitFor(() -> !((ComboBox<?>)node("agentProjectCombo")).getItems().isEmpty(), 60, "connection_not_ready");
        fx(() -> {
            ComboBox<AgentClient.Project> projects = node("agentProjectCombo");
            AgentClient.Project project = projects.getItems().stream().filter(p -> p.projectId().equals(config.projectId())).findFirst()
                    .orElseThrow(() -> new PilotFailure("project_not_authorized"));
            if (!project.uml()) throw new PilotFailure("project_uml_unavailable");
            projects.getSelectionModel().select(project);
        });
    }
    static void start(Configuration config) throws Exception {
        waitFor(() -> ((ComboBox<ModelCatalogClient.Option>)node("agentModel")).getItems().stream()
                .anyMatch(o -> o.id().equals(config.modelSelection()) && o.available() && o.supports("coding")), 60, "selected_model_unavailable");
        fx(() -> {
            ComboBox<ModelCatalogClient.Option> models = node("agentModel");
            models.getSelectionModel().select(models.getItems().stream().filter(o -> o.id().equals(config.modelSelection())).findFirst().orElseThrow());
            ((TextArea)node("taskText")).setText(config.task());
            ((TextArea)node("documentReferences")).setText(config.references());
            ((CheckBox)node("umlEnabled")).setSelected(true);
            ((TextArea)node("requiredDiagrams")).setText(config.diagrams());
            ((Spinner<Integer>)node("modelSeconds")).getValueFactory().setValue(config.modelSeconds());
            ((Spinner<Integer>)node("actionBudget")).getValueFactory().setValue(config.actions());
            ((Spinner<Integer>)node("roundBudget")).getValueFactory().setValue(config.rounds());
            snapshot("01-request.png");
            click("startButton");
            AgentClient.StartRequest pending = state("pendingStart", AgentClient.StartRequest.class);
            if (pending == null) throw new PilotFailure("start_request_not_prepared");
            // Persist the exact nonsecret request/key so an uncertain response can be investigated without a replacement job.
            write("submitted-request.json", AgentClient.startBody(pending));
        });
        waitFor(() -> job() != null || idle(), 60, "start_result_unknown");
        fx(() -> {
            if (job() == null) throw new PilotFailure("start_not_confirmed_do_not_restart");
            if (!job().projectId().equals(config.projectId()) || !job().modelSelection().equals(config.modelSelection())
                    || job().modelSeconds() != config.modelSeconds() || job().actionBudget() != config.actions())
                throw new PilotFailure("accepted_request_mismatch");
        });
    }
    static void inspect(String id) throws Exception {
        if (!id.matches("[0-9a-f]{32}")) throw new PilotFailure("invalid_job_id");
        fx(() -> { ((TextField)node("reopenId")).setText(id); click("reopenButton"); });
        waitFor(() -> job() != null || idle(), 60, "reopen_result_unknown");
        fx(() -> { if (job() == null || !job().jobId().equals(id)) throw new PilotFailure("job_not_opened"); });
    }
    static void captureReview() throws Exception {
        AtomicBoolean available = new AtomicBoolean();
        fx(() -> available.set(job().reviewAvailable()));
        if (!available.get()) return;
        waitFor(AgentLivePilot::idle, 40, "status_read_in_progress");
        fx(() -> click("reviewButton"));
        waitFor(() -> review() != null || idle(), 60, "review_result_unknown");
        AtomicReference<AgentClient.Review> evidence = new AtomicReference<>();
        fx(() -> {
            if (review() == null) throw new PilotFailure("review_not_loaded");
            evidence.set(review());
            ((TabPane)node("agentSections")).getSelectionModel().select(2);
            ((TabPane)node("reviewSections")).getSelectionModel().select(0); snapshot("03-review.png");
            ((TabPane)node("reviewSections")).getSelectionModel().select(1); snapshot("04-checks.png");
        });
        AgentClient.Review r = evidence.get();
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("reviewId", r.reviewId()); data.put("diffSha256", r.diffSha256()); data.put("job", jobEvidence(r.job()));
        data.put("checks", r.checks().stream().map(c -> Map.of("name", c.name(), "status", c.status(), "exitCode", c.exitCode())).toList());
        data.put("operations", r.operations()); data.put("warnings", r.warnings());
        data.put("diagramIds", r.diagrams().stream().map(AgentClient.DiagramInfo::id).toList());
        data.put("adoptionAvailable", r.adoptionAvailable()); data.put("adoptionRequested", false);
        if (r.quality() != null) data.put("quality", qualityEvidence(r.quality()));
        write("review.json", data);
        Files.writeString(audit.resolve("actual.diff"), r.diff(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        for (AgentClient.DiagramInfo diagram : r.diagrams()) {
            fx(() -> {
                ((ComboBox<AgentClient.DiagramInfo>)node("diagramCombo")).getSelectionModel().select(diagram);
                ((TabPane)node("reviewSections")).getSelectionModel().select(3); click("diagramButton");
            });
            waitFor(() -> ((ImageView)node("diagramImage")).getImage() != null || idle(), 60, "diagram_result_unknown");
            fx(() -> {
                Image pixels = ((ImageView)node("diagramImage")).getImage();
                if (pixels == null) throw new PilotFailure("diagram_not_displayed");
                snapshot("diagram-" + diagram.id() + "-ui.png");
                saveImage(pixels, "diagram-" + diagram.id() + "-displayed.png");
            });
        }
    }
    public static void main(String[] args) {
        boolean started = false;
        int code = 0;
        try {
            if (args.length < 3 || args.length > 4 || !Set.of("start", "inspect", "validate").contains(args[0]))
                throw new PilotFailure("usage");
            mode = args[0]; audit = Path.of(args[2]).toAbsolutePath().normalize();
            if (!emptyDirectory(audit)) throw new PilotFailure("empty_evidence_directory_required");
            Configuration config = configure(Path.of(args[1]));
            if (mode.equals("validate")) { write("validation.json", Map.of("configurationValid", true, "networkRequests", 0)); return; }
            if (mode.equals("inspect") && args.length != 4 || mode.equals("start") && args.length != 3) throw new PilotFailure("usage");
            CountDownLatch startup = new CountDownLatch(1); Platform.startup(startup::countDown); startup.await(); started = true;
            fx(() -> {
                loader = new FXMLLoader(AgentLivePilot.class.getResource("/fxml/agent_tab.fxml")); root = loader.load();
                Scene scene = new Scene(root, 1040, 900);
                scene.getStylesheets().add(AgentLivePilot.class.getResource("/css/chat.css").toExternalForm());
                controller = loader.getController(); controller.configure(config.baseUrl());
            });
            connect(config);
            if (mode.equals("start")) start(config); else inspect(args[3]);
            fx(() -> {
                ((TabPane)node("agentSections")).getSelectionModel().select(1); snapshot("02-status.png");
                write("job.json", jobEvidence(job()));
            });
            if (mode.equals("inspect")) captureReview();
            fx(() -> write("ui-evidence.json", Map.of("mode", mode, "capturedAt", Instant.now().toString(),
                    "realFXML", true, "realController", true, "realNetwork", true, "fixture", false,
                    "manualHumanClick", false, "adoptionRequested", false,
                    "displayedStatus", ((Label)node("agentStatus")).getText(), "job", jobEvidence(job()))));
            Path clientJar = Path.of(AgentPaneController.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(clientJar) && Files.size(clientJar) <= 16 * 1024 * 1024)
                write("client-build.json", Map.of("sha256", AgentClient.sha256(Files.readAllBytes(clientJar))));
            System.out.println("PASS: live UI " + mode + " evidence saved; no adoption requested.");
        } catch (Throwable error) {
            code = 1;
            Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
            String failure = cause instanceof PilotFailure ? cause.getMessage() : cause.getClass().getSimpleName();
            // Never print exception messages, stack traces, configuration, headers or credentials.
            try { if (audit != null && Files.isDirectory(audit)) write("failure.json", Map.of("code", failure,
                    "mode", mode == null ? "unknown" : mode, "adoptionRequested", false, "capturedAt", Instant.now().toString())); }
            catch (Exception ignored) { }
            System.err.println("FAIL: " + failure + "; do not automatically submit a new job.");
        } finally {
            if (started) {
                try { fx(() -> { if (controller != null) controller.disconnect(); }); } catch (Exception ignored) { }
                Platform.exit();
            }
        }
        System.exit(code);
    }
}

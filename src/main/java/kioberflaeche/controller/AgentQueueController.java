package kioberflaeche.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;
import kioberflaeche.agent.AgentClient;
import kioberflaeche.agent.AgentClient.*;
import kioberflaeche.queue.AgentActivity;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Queue state has its own session generation; status/review navigation cannot revive stale responses. */
public final class AgentQueueController {
    @FXML private Label queueSummary, queueMessage, queueError;
    @FXML private ListView<QueueEntry> queueItems;
    @FXML private TextArea queueDetails;
    @FXML private Button queueRefreshButton, queueRemoveButton, queueClearButton, queuePauseButton,
            queueResumeButton, queueNextButton, queueOpenButton, queueRetryButton;
    private final PauseTransition refreshDelay = new PauseTransition(Duration.seconds(10));
    private AgentClient client;
    private String projectId;
    private TaskQueue snapshot;
    private long generation;
    private boolean busy;
    private int failures;
    private Pending pending;
    private Runnable changed = () -> {};
    private Consumer<String> openJob = id -> {};
    private boolean canOpen;
    private Set<String> monitoredJobs = Set.of();
    private record Pending(String name, Operation operation) {}
    private interface Operation { TaskQueue run() throws Exception; }

    @FXML private void initialize() {
        refreshDelay.setOnFinished(event -> refresh());
        queueItems.setCellFactory(view -> new ListCell<>() {
            { setWrapText(true); prefWidthProperty().bind(view.widthProperty().subtract(24)); setMaxWidth(Double.MAX_VALUE); }
            @Override protected void updateItem(QueueEntry item, boolean empty) {
                super.updateItem(item, empty); setText(empty || item == null ? null : item.toString());
            }
        });
        queueItems.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> { showDetails(b); controls(); });
        controls();
    }
    public void configure(Runnable changed, Consumer<String> openJob) { this.changed = changed; this.openJob = openJob; }
    public void connect(AgentClient connection, String project) {
        AgentActivity.unavailable(monitoredJobs, "Agentenverbindung gewechselt; Serverauftrag kann weiterlaufen");
        monitoredJobs = Set.of();
        ++generation; refreshDelay.stop(); client = connection; projectId = project;
        snapshot = null; pending = null; busy = false; failures = 0;
        queueItems.getItems().clear(); queueDetails.clear();
        queueSummary.setText(connection == null || project == null ? "Warteliste nach dem Verbinden verfügbar." : "Warteliste wird geladen …");
        queueMessage.setText(""); queueError.setText(""); controls();
        if (client != null && projectId != null) refresh();
    }
    public boolean draftLocked() { return pending != null; }
    public boolean canAdd() { return client != null && projectId != null && !busy && pending == null; }
    public void allowOpen(boolean value) { canOpen = value; controls(); }
    public void add(StartRequest request) {
        if (!canAdd() || !request.projectId().equals(projectId)) return;
        AgentClient connection = client;
        submit("Hinzufügen", () -> connection.enqueue(request));
    }
    @FXML private void refresh() {
        if (client == null || projectId == null || busy) return;
        AgentClient connection = client; String project = projectId;
        execute(() -> connection.queue(project), false);
    }
    @FXML private void remove() {
        QueueEntry entry = queueItems.getSelectionModel().getSelectedItem();
        if (!canAdd() || entry == null || !entry.waiting()) return;
        AgentClient connection = client; String project = projectId;
        submit("Entfernen", () -> connection.removeQueued(project, entry.entryId()));
    }
    @FXML private void clear() { change("clear", "Wartende leeren"); }
    @FXML private void pause() { change("pause", "Pausieren"); }
    @FXML private void resume() { change("resume", "Fortsetzen"); }
    @FXML private void startNext() { change("start-next", "Eine Aufgabe starten"); }
    private void change(String operation, String name) {
        if (!canAdd() || snapshot == null) return;
        AgentClient connection = client; String project = projectId, key = UUID.randomUUID().toString();
        submit(name, () -> connection.queueCommand(project, operation, key));
    }
    private void submit(String name, Operation operation) {
        pending = new Pending(name, operation); execute(operation, true);
    }
    @FXML private void retry() {
        if (pending != null && !busy) execute(pending.operation(), true);
    }
    @FXML private void open() {
        QueueEntry entry = queueItems.getSelectionModel().getSelectedItem();
        if (canOpen && entry != null && !entry.waiting()) openJob.accept(entry.jobId());
    }
    private void execute(Operation operation, boolean mutation) {
        busy = true; refreshDelay.stop(); controls(); changed.run();
        long expected = generation;
        Thread.startVirtualThread(() -> {
            try {
                TaskQueue result = operation.run();
                Platform.runLater(() -> {
                    if (expected != generation) return;
                    busy = false; failures = 0;
                    if (mutation) { queueMessage.setText(pending.name() + " bestätigt. Frühere Aufträge und Belege bleiben erhalten."); pending = null; }
                    snapshot = result; showQueue(); controls(); changed.run(); schedule();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (expected != generation) return;
                    busy = false; ++failures;
                    AgentActivity.unavailable(monitoredJobs, "Serverstatus nicht aktuell; Agenten-Warteliste erneut laden");
                    // A known client rejection has no uncertain network outcome. Ambiguous failures retain the exact operation/key.
                    if (mutation && error instanceof AgentClient.Failure f && f.status() >= 400 && f.status() < 500) pending = null;
                    queueMessage.setText((error instanceof IOException ? error.getMessage() : "Warteliste konnte nicht geladen werden.")
                            + (pending == null ? "" : " „Aktion erneut anfragen“ verwendet dieselbe Kennung und Wirkung.")
                            + (failures >= 3 ? " Automatische Abfrage pausiert; manuell aktualisieren." : ""));
                    controls(); changed.run(); schedule();
                    if (mutation && error instanceof AgentClient.Failure f && f.status() == 409) refresh();
                });
            }
        });
    }
    private void showQueue() {
        Set<String> present = AgentActivity.queue(snapshot);
        Set<String> missing = new HashSet<>(monitoredJobs); missing.removeAll(present);
        AgentActivity.unavailable(missing, "Nicht mehr in der Warteliste; Endstatus im Agenten-Tab laden");
        monitoredJobs = present;
        QueueEntry selected = queueItems.getSelectionModel().getSelectedItem();
        List<QueueEntry> all = new ArrayList<>(snapshot.activeItems()); all.addAll(snapshot.items());
        queueItems.getItems().setAll(all);
        if (selected != null) all.stream().filter(e -> e.entryId().equals(selected.entryId())).findFirst()
                .ifPresent(e -> queueItems.getSelectionModel().select(e));
        queueSummary.setText("Projekt " + snapshot.projectId() + " · " + (snapshot.paused() ? "pausiert" : "automatisch fortlaufend")
                + " · " + snapshot.waitingCount() + " wartend · " + snapshot.activeItems().size() + " freigegeben");
        queueError.setText(snapshot.lastError().isEmpty() ? "" : "Freigabe angehalten: " + snapshot.lastError()
                + (snapshot.blockedEntryId().isEmpty() ? "" : "\nBetroffener Wartelisteneintrag: " + snapshot.blockedEntryId())
                + "\nAufgabe und Projektstand prüfen. Fortsetzen versucht die Freigabe erneut; Entfernen betrifft nur den wartenden Eintrag.");
    }
    private void showDetails(QueueEntry entry) {
        if (entry == null) { queueDetails.clear(); return; }
        String date = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(entry.createdAt()));
        queueDetails.setText(entry.taskPreview() + "\n\nAuftrag: " + entry.jobId() + "\nWartelisteneintrag: " + entry.entryId()
                + "\nModellwahl: " + entry.modelSelection() + "\nHinzugefügt: " + date
                + "\nStatus: " + (entry.waiting() ? "wartet auf Freigabe" : entry.jobStatus()));
    }
    private void schedule() {
        refreshDelay.stop();
        if (client != null && failures < 3 && pending == null && snapshot != null
                && (snapshot.waitingCount() > 0 || !snapshot.activeItems().isEmpty())) refreshDelay.playFromStart();
    }
    private void controls() {
        boolean available = client != null && projectId != null, ready = available && !busy && pending == null && snapshot != null;
        QueueEntry entry = queueItems.getSelectionModel().getSelectedItem();
        queueRefreshButton.setDisable(!available || busy);
        queueRemoveButton.setDisable(!ready || entry == null || !entry.waiting());
        queueClearButton.setDisable(!ready || snapshot.waitingCount() == 0);
        queuePauseButton.setDisable(!ready || snapshot.paused());
        queueResumeButton.setDisable(!ready || !snapshot.paused());
        queueNextButton.setDisable(!ready || !snapshot.paused() || snapshot.waitingCount() == 0 || !snapshot.activeItems().isEmpty());
        queueOpenButton.setDisable(!available || busy || !canOpen || entry == null || entry.waiting());
        queueRetryButton.setDisable(!available || busy || pending == null);
    }
}

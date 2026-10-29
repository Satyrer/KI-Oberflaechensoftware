package kioberflaeche.controller;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import kioberflaeche.ai.EphemeralTextClient;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import kioberflaeche.queue.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

public final class WorkQueueController {
    public record DisplayRow(String id, long number, String area, String title, String status, String phase,
                             String progress, double fraction, String position, String age, String details, String tone) {}
    private record Source(MonitorSnapshot snapshot, boolean unavailable) {}
    @FXML private VBox queueRoot;
    @FXML private TabPane sources;
    @FXML private TableView<DisplayRow> localTable, serverTable;
    @FXML private Label localSummary, serverSummary, serverHealth, sourceDirectoryLabel;
    @FXML private Button refreshServerButton;
    @FXML private CheckBox showFinished, showServerFinished;
    @FXML private TextArea selectionDetails;
    private final StringProperty summary = new SimpleStringProperty("Queue wird vorbereitet");
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
    private final Map<Path, Source> snapshots = new LinkedHashMap<>();
    private final EphemeralTextClient ephemeralClient;
    private List<EphemeralTextClient.Run> ephemeralRuns=List.of();
    private boolean ephemeralKnown, ephemeralUnavailable;
    private Instant ephemeralRead;
    private Path directory = Path.of(System.getProperty("ki.queueMonitorDirectory", "C:/KI Arbeitsbereich/Eigene AI/Shared"));
    private String health = "";
    private long generation;
    private boolean reading;
    private int ticks;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    public WorkQueueController() { this(EphemeralTextClient.configured()); }
    public WorkQueueController(EphemeralTextClient client) { ephemeralClient=client; }

    @FXML private void initialize() {
        table(localTable, false); table(serverTable, true);
        showFinished.setSelected(false); showFinished.selectedProperty().addListener((o,a,b) -> render());
        showServerFinished.setSelected(false); showServerFinished.selectedProperty().addListener((o,a,b) -> render());
        sources.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> selectionDetails.clear());
        clock.setCycleCount(Animation.INDEFINITE);
        queueRoot.sceneProperty().addListener((o,a,scene) -> {
            if (scene != null) {
                scene.windowProperty().addListener((w,oldWindow,window) -> {
                    if (window != null) window.showingProperty().addListener((s,oldShown,shown) -> {
                        if (shown) clock.play(); else clock.stop();
                    });
                });
            }
        });
        render();
    }
    public void startMonitoring() { clock.play(); refreshServer(); }
    public ReadOnlyStringProperty summaryProperty() { return summary; }
    private void tick() { render(); if (++ticks % 5 == 0) refreshServer(); }
    private void table(TableView<DisplayRow> table, boolean server) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table, "Nr.", row->Long.toString(row.number()), 50);
        column(table, "Status", DisplayRow::status, 130);
        column(table, "Bereich", DisplayRow::area, 100);
        column(table, "Auftrag", DisplayRow::title, 240);
        column(table, "Phase", DisplayRow::phase, 210);
        if (server) {
            TableColumn<DisplayRow,DisplayRow> progress = new TableColumn<>("Fortschritt");
            progress.setCellValueFactory(item -> new ReadOnlyObjectWrapper<>(item.getValue()));
            progress.setPrefWidth(105); progress.setMinWidth(90);
            progress.setCellFactory(c -> new TableCell<>() {
                private final Label label = new Label();
                private final ProgressBar bar = new ProgressBar();
                private final VBox box = new VBox(3, label, bar);
                { bar.setMaxWidth(Double.MAX_VALUE); }
                @Override protected void updateItem(DisplayRow row, boolean empty) {
                    super.updateItem(row, empty);
                    setGraphic(empty || row == null || row.fraction() < 0 ? null : box);
                    if (!empty && row != null) { label.setText(row.progress()); bar.setProgress(row.fraction()); }
                }
            });
            table.getColumns().add(progress);
            column(table, "Position", DisplayRow::position, 75);
        } else column(table, "Dauer", DisplayRow::age, 80);
        table.setPlaceholder(new Label(server ? "Noch kein Serverstand verfuegbar" : "Keine offenen Auftraege"));
        table.setRowFactory(view -> new TableRow<>() {
            @Override protected void updateItem(DisplayRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("queue-running", "queue-waiting", "queue-review", "queue-failed", "queue-stale", "queue-done");
                if (!empty && item != null) getStyleClass().add("queue-" + item.tone());
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> {
            selectionDetails.setText(b == null ? "" : b.details());
        });
    }
    private void column(TableView<DisplayRow> table, String title, Function<DisplayRow,String> value, int width) {
        TableColumn<DisplayRow,String> column = new TableColumn<>(title);
        column.setCellValueFactory(item -> new ReadOnlyStringWrapper(value.apply(item.getValue())));
        column.setPrefWidth(width); column.setMinWidth(title.equals("Nr.") ? 45 : title.equals("Status") ? 125 : title.equals("Auftrag") ? 155 : 75);
        column.setCellFactory(c -> new TableCell<>() {
            { setWrapText(true); if (title.equals("Status")) getStyleClass().add("queue-status-cell"); }
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty); setText(empty ? null : text);
                setTooltip(empty || text == null || text.isBlank() ? null : new Tooltip(text));
            }
        });
        table.getColumns().add(column);
    }
    @FXML private void clearFinished() { ActivityRegistry.shared().clearFinished(); render(); }
    @FXML private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Queue-Statusordner");
        if (Files.isDirectory(directory)) chooser.setInitialDirectory(directory.toFile());
        var selected = chooser.showDialog(queueRoot.getScene().getWindow());
        if (selected != null) {
            directory = selected.toPath(); ++generation; reading = false; snapshots.clear(); health = "";
            refreshServer();
        }
    }
    @FXML private void refreshServer() {
        if (reading) return;
        reading = true; refreshServerButton.setDisable(true);
        long expected = generation; Path folder = directory;
        boolean pollEphemeral=ephemeralClient.isConfigured() && isQueueVisible();
        Thread.startVirtualThread(() -> {
            Map<Path, Source> loaded = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            try {
                if (!Files.isDirectory(folder)) throw new IOException("Statusordner ist nicht erreichbar");
                try (var files = Files.find(folder, 2, (path, attributes) ->
                        attributes.isRegularFile() && path.getFileName().toString().equals("monitor-snapshot.json"))) {
                    List<Path> paths = files.limit(65).sorted().toList();
                    if (paths.size() > 64) throw new IOException("Zu viele Statusquellen; einen engeren Ordner waehlen");
                    for (Path path : paths) {
                        try { loaded.put(path, new Source(MonitorSnapshot.read(path), false)); }
                        catch (IOException error) { errors.add(path.getParent().getFileName() + ": Status nicht lesbar"); }
                    }
                }
            } catch (IOException | RuntimeException error) { errors.add("Statusordner konnte nicht gelesen werden"); }
            List<EphemeralTextClient.Run> runs=null;
            if(pollEphemeral) {
                try { runs=ephemeralClient.readRuns(); }
                catch(Exception error) { if(error instanceof InterruptedException)Thread.currentThread().interrupt(); }
            }
            List<EphemeralTextClient.Run> loadedRuns=runs;
            Platform.runLater(() -> {
                if (expected != generation) return;
                snapshots.replaceAll((path, source) -> new Source(source.snapshot(), true));
                snapshots.putAll(loaded); health = String.join("; ", errors);
                if(pollEphemeral) {
                    ephemeralUnavailable=loadedRuns==null;
                    if(loadedRuns!=null) { ephemeralRuns=loadedRuns;ephemeralKnown=true;ephemeralRead=Instant.now(); }
                }
                reading = false; refreshServerButton.setDisable(false); render();
            });
        });
    }
    private boolean isQueueVisible() {
        if(queueRoot.getScene()==null || queueRoot.getScene().getWindow()==null
                || !queueRoot.getScene().getWindow().isShowing()) return false;
        for(javafx.scene.Node node=queueRoot;node!=null;node=node.getParent()) if(!node.isVisible())return false;
        return true;
    }
    private void render() {
        var entries = ActivityRegistry.shared().snapshot();
        long running = entries.stream().filter(e -> e.state() == ActivityRegistry.State.RUNNING).count();
        long waiting = entries.stream().filter(e -> e.state() == ActivityRegistry.State.WAITING).count();
        long review = entries.stream().filter(e -> e.state() == ActivityRegistry.State.REVIEW).count();
        long unknown = entries.stream().filter(e -> e.state() == ActivityRegistry.State.UNKNOWN).count();
        long failed = entries.stream().filter(e -> e.state() == ActivityRegistry.State.FAILED).count();
        String local = running + " laufend · " + waiting + " wartend · " + review + " zu pruefen";
        if (unknown > 0) local += " · " + unknown + " unklar";
        if (failed > 0) local += " · Verlauf: " + failed + " fehlgeschlagen";
        localSummary.setText(local);
        List<DisplayRow> localRows = entries.stream().filter(e -> showFinished.isSelected() || !e.state().terminal())
                .map(e -> new DisplayRow(e.id(), JobNumbers.forId("local:"+e.id()), e.area(), e.title(),
                e.state().toString(), e.phase(), "", -1, "", elapsed(e.created(), e.finished()),
                e.title() + "\n" + e.area() + " · " + e.state() + "\n" + e.phase() + "\nBeginn: " + DATE.format(e.created())
                        + "\nLetzte Meldung: " + DATE.format(e.updated()) + "\nID: " + e.id(),
                tone(e.state()))).toList();
        replace(localTable, localRows);
        List<DisplayRow> serverRows = new ArrayList<>();
        int staleSources = 0, activeLeaves = 0, waitingLeaves = 0, reviewLeaves = 0, failedLeaves = 0,
                finishedLeaves = 0, recoveryLeaves = 0;
        Instant newest = null, now = Instant.now();
        Set<String> rowKeys = new HashSet<>();
        for (Source source : snapshots.values()) {
            var snapshot = source.snapshot();
            boolean hasOpenWork = snapshot.rows().stream().anyMatch(row -> !stable(row.status()));
            boolean stale = source.unavailable() || hasOpenWork && snapshot.stale(now);
            if (stale) staleSources++;
            if (newest == null || snapshot.generatedAt().isAfter(newest)) newest = snapshot.generatedAt();
            Set<String> parents = new HashSet<>();
            snapshot.rows().forEach(row -> { if (!row.parentId().isBlank()) parents.add(row.parentId()); });
            for (var row : snapshot.rows()) {
                String key = snapshot.sourceId() + ":" + row.id();
                if (!rowKeys.add(key)) continue;
                long number=JobNumbers.forId("server:"+key);
                if (!stale && !parents.contains(row.id())) {
                    if (Set.of("running", "cancel_requested", "awaiting_test").contains(row.status())) activeLeaves++;
                    if (Set.of("queued", "held").contains(row.status())) waitingLeaves++;
                    if (row.status().equals("awaiting_review")) reviewLeaves++;
                    if (row.status().equals("recovery_required")) recoveryLeaves++;
                    if (Set.of("failed", "exhausted").contains(row.status())) failedLeaves++;
                    if (stable(row.status())) finishedLeaves++;
                }
                // Settled work remains inspectable, but never occupies the active queue view.
                if (!showServerFinished.isSelected() && stable(row.status())) continue;
                var state = state(row.status());
                String progress = row.total() == null || row.completed() == null ? "" : row.completed() + "/" + row.total();
                if (row.failedSteps() > 0) progress += " (" + row.failedSteps() + " Fehler)";
                String detail = row.title() + "\nID: " + row.id() + "\nQuelle: " + snapshot.sourceId() + " (" + snapshot.scope() + ")"
                        + "\nStand: " + DATE.format(snapshot.generatedAt()) + (stale ? " - NICHT AKTUELL" : "")
                        + "\nStatus: " + row.status() + " · " + row.phase()
                        + "\nProjekt/Serie: " + row.project() + " " + row.series()
                        + (row.parentId().isBlank() ? "" : "\nUebergeordneter Auftrag: " + row.parentId())
                        + (progress.isBlank() ? "" : "\nFortschritt: " + progress + " " + row.unit() + " · Fehler: " + row.failedSteps())
                        + (row.position() == null || stable(row.status()) ? "" : "\nPosition " + row.position() + " innerhalb " + row.queueScope());
                serverRows.add(new DisplayRow(key, number, area(row.area()), (parents.contains(row.id()) ? "Serie: " : "") + row.title(),
                        stale && !stable(row.status()) ? "Stand veraltet" : state.toString(), phase(row.phase()), progress,
                        row.total() == null || row.completed() == null || row.total() <= 0 ? -1 : (double) row.completed() / row.total(),
                        row.position() == null || stable(row.status()) ? "" : "#" + row.position(), "", detail, stale ? "stale" : tone(state)));
            }
        }
        boolean staleEphemeral=ephemeralUnavailable || ephemeralRead!=null
                && java.time.Duration.between(ephemeralRead,now).toSeconds()>30;
        if(staleEphemeral && ephemeralKnown)staleSources++;
        for(var run:ephemeralRuns) {
            long number=JobNumbers.forId("ephemeral:"+run.id());
            var state=state(run.status());boolean terminal=stable(run.status());
            if(!staleEphemeral) {
                if(run.status().equals("running"))activeLeaves++;
                if(run.status().equals("queued"))waitingLeaves++;
                if(terminal)finishedLeaves++;
                if(run.status().equals("failed"))failedLeaves++;
            }
            if(!showServerFinished.isSelected() && terminal)continue;
            String title="Auftrag "+number;
            String details=title+"\nKontext: Dolphinlauf\nStatus: "+(staleEphemeral?"Stand veraltet":state);
            serverRows.add(new DisplayRow("ephemeral:"+run.id(),number,"Dolphinlauf",title,
                    staleEphemeral && !terminal?"Stand veraltet":state.toString(),phase(run.status()),"",-1,"","",details,
                    staleEphemeral?"stale":tone(state)));
        }
        replace(serverTable, serverRows);
        boolean hasServer=snapshots.size()>0 || ephemeralKnown;
        serverTable.setPlaceholder(new Label(!hasServer ? "Noch kein Serverstand verfuegbar"
                : showServerFinished.isSelected() ? "Keine gemeldeten Auftraege"
                : "Keine offenen gemeldeten Auftraege. Ergebnisse unter Verlauf anzeigen."));
        String server = !hasServer ? "Kein Serverstand" : activeLeaves + " laufend · " + waitingLeaves + " wartend";
        if (recoveryLeaves > 0) server += " · " + recoveryLeaves + " Wiederanlauf offen";
        if (finishedLeaves > 0) server += " · Verlauf: " + finishedLeaves + " beendet"
                + (reviewLeaves > 0 ? " · " + reviewLeaves + " zu pruefen" : "")
                + (failedLeaves > 0 ? " · " + failedLeaves + " fehlgeschlagen" : "");
        if (staleSources > 0) server += " · " + staleSources + " Quelle(n) nicht aktuell";
        serverSummary.setText(server);
        serverHealth.setText((newest == null ? "Noch kein Status-Snapshot vorhanden."
                : "Letzter Snapshot: " + DATE.format(newest) + " · " + snapshots.size() + " Quelle(n)")
                + (health.isBlank() ? "" : "\n" + health)
                + (ephemeralUnavailable?"\nDolphin-Status derzeit nicht verfuegbar.":ephemeralKnown
                    ?"\nDolphinlauf: ausschliesslich Status im Arbeitsspeicher; Serverneustart leert diesen Verlauf.":"")
                + "\nServeranzeige umfasst nur gemeldete Quellen. Positionen gelten jeweils innerhalb ihrer Queue.");
        sourceDirectoryLabel.setText(directory.toString());
        summary.set("Anwendung: " + local + " | Server: " + server);
    }
    private void replace(TableView<DisplayRow> table, List<DisplayRow> rows) {
        var selected = table.getSelectionModel().getSelectedItem();
        if (table.getItems().equals(rows)) return;
        table.getItems().setAll(rows);
        if (selected != null) rows.stream().filter(row -> row.id().equals(selected.id())).findFirst()
                .ifPresent(row -> table.getSelectionModel().select(row));
    }
    private static String elapsed(Instant start, Instant end) {
        long seconds = Math.max(0, java.time.Duration.between(start, end == null ? Instant.now() : end).toSeconds());
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }
    private static ActivityRegistry.State state(String value) {
        return switch (value) {
            case "queued", "held" -> ActivityRegistry.State.WAITING;
            case "running", "cancel_requested", "awaiting_test" -> ActivityRegistry.State.RUNNING;
            case "awaiting_review", "recovery_required" -> ActivityRegistry.State.REVIEW;
            case "completed" -> ActivityRegistry.State.DONE;
            case "failed", "exhausted" -> ActivityRegistry.State.FAILED;
            case "cancelled" -> ActivityRegistry.State.CANCELLED;
            default -> ActivityRegistry.State.UNKNOWN;
        };
    }
    private static boolean stable(String status) {
        return Set.of("completed", "failed", "cancelled", "awaiting_review", "exhausted").contains(status);
    }
    private static String area(String value) {
        return switch (value) {
            case "figures", "vision" -> "Bildanalyse"; case "gateway" -> "Modell";
            case "coding", "agent" -> "Agent"; case "transcription" -> "Transkription";
            default -> value;
        };
    }
    private static String phase(String value) {
        return switch (value) {
            case "waiting_for_predecessors" -> "Wartet auf laufende Auftraege";
            case "waiting_gateway" -> "Wartet auf Modell-Queue";
            case "running" -> "Verarbeitung";
            case "queued" -> "Wartet auf Ausfuehrung";
            case "completed_unreviewed", "completed" -> "Verarbeitung beendet";
            case "completed_with_errors" -> "Mit Fehlern beendet";
            case "failed" -> "Fehlgeschlagen";
            case "blocked" -> "Bearbeitung angehalten";
            default -> value;
        };
    }
    private static String tone(ActivityRegistry.State state) {
        return switch (state) {
            case RUNNING -> "running"; case WAITING -> "waiting"; case REVIEW -> "review";
            case FAILED -> "failed"; case UNKNOWN -> "stale"; default -> "done";
        };
    }
}

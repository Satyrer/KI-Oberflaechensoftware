package kioberflaeche.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import kioberflaeche.agent.AgentClient;
import kioberflaeche.agent.AgentClient.*;
import kioberflaeche.ai.ModelCatalogClient;
import kioberflaeche.ui.ModelPicker;
import kioberflaeche.queue.AgentActivity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Every network operation leaves the FX thread. Session epochs discard late responses. */
public final class AgentPaneController {
    @FXML private VBox agentRoot, requestForm, adoptionBox;
    @FXML private Label connectionLabel, agentStatus, modelHint, budgetLabel, reviewIdentity, confirmationHint, diagramStatus,
            qualityStatus, qualityProfileHint;
    @FXML private PasswordField projectToken, operatorToken;
    @FXML private TextField reopenId, operatorName, confirmationInput, displayedJobId;
    @FXML private ComboBox<Project> agentProjectCombo;
    @FXML private ComboBox<ModelCatalogClient.Option> agentModel;
    @FXML private ComboBox<DiagramInfo> diagramCombo;
    @FXML private TextArea taskText, documentReferences, requiredDiagrams, diffText, checkText, operationText,
            stageOverview, stageEvidence;
    @FXML private CheckBox umlEnabled, reviewedCheck;
    @FXML private Spinner<Integer> modelSeconds, actionBudget, roundBudget;
    @FXML private Button connectButton, disconnectButton, refreshModels, startButton, newButton, reopenButton,
            refreshStatusButton, cancelButton, resumeButton, reviewButton, diagramButton, applyButton, queueAddButton;
    @FXML private AgentQueueController queuePaneController;
    @FXML private TabPane agentSections;
    @FXML private ImageView diagramImage;
    @FXML private ProgressBar budgetProgress;
    private final PauseTransition nextPoll = new PauseTransition(Duration.seconds(2));
    private ModelPicker modelPicker;
    private String baseUrl;
    private AgentClient client;
    private Job currentJob;
    private Review currentReview;
    private StartRequest pendingStart;
    private long epoch;
    private int reads;
    private boolean commandBusy, connecting;
    private int pollFailures;

    @FXML private void initialize() {
        modelSeconds.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 7200, 1800, 60));
        actionBudget.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 15, 15));
        roundBudget.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 2, 2));
        modelPicker = new ModelPicker(agentModel, modelHint, refreshModels, () -> "coding");
        modelPicker.usePreference("agent.model");
        nextPoll.setOnFinished(event -> refreshStatus());
        queuePaneController.configure(this::updateControls, id -> {
            reopenId.setText(id); reopen(); agentSections.getSelectionModel().select(1);
        });
        agentProjectCombo.valueProperty().addListener((o, a, b) -> {
            if (b == null || !b.uml()) umlEnabled.setSelected(false);
            qualityProfileHint.setText(b == null ? "Das Aufgabenprüfprofil erscheint nach der Projektauswahl."
                    : b.qualityProfile().isEmpty() ? "Für dieses Projekt ist kein zusätzliches Aufgabenprüfprofil eingerichtet."
                    : "Aufgabenprüfprofil: " + b.qualityProfile() + ". Erforderliche Bestandteile und Platzhalter werden gesondert geprüft. Ein erfolgreicher Build allein erfüllt die Aufgabe nicht.");
            queuePaneController.connect(client, b == null ? null : b.projectId());
            updateControls();
        });
        umlEnabled.selectedProperty().addListener((o, a, b) -> updateControls());
        reviewedCheck.selectedProperty().addListener((o, a, b) -> updateControls());
        confirmationInput.textProperty().addListener((o, a, b) -> updateControls());
        operatorToken.textProperty().addListener((o, a, b) -> updateControls());
        operatorName.textProperty().addListener((o, a, b) -> updateControls());
        diagramCombo.valueProperty().addListener((o, a, b) -> {
            diagramImage.setImage(null); diagramStatus.setText("Diagramm auswählen und laden."); updateControls();
        });
        agentRoot.sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene == null) { disconnect(); return; }
            scene.windowProperty().addListener((w, oldWindow, window) -> {
                if (window != null) window.showingProperty().addListener((s, wasShown, shown) -> {
                    if (!shown) disconnect();
                });
            });
        });
        updateControls();
    }

    public void configure(String baseUrl) {
        this.baseUrl = baseUrl;
        // The UI has no credential file source, and never prints the configured URL.
        connectionLabel.setText("Verbindung über die eingerichtete SchreibAI-Adresse");
        updateControls();
    }

    @FXML private void connect() {
        if (connecting || client != null) return;
        final AgentClient proposed;
        try { proposed = new AgentClient(baseUrl, projectToken.getText()); }
        catch (IOException error) { showError(error); return; }
        projectToken.clear();
        connecting = true;
        agentStatus.setText("Prüfe Zugang und freigegebene Projekte …");
        long generation = ++epoch;
        updateControls();
        Thread.startVirtualThread(() -> {
            try {
                List<Project> projects = proposed.projects();
                Platform.runLater(() -> {
                    if (generation != epoch) return;
                    connecting = false; client = proposed;
                    agentProjectCombo.getItems().setAll(projects);
                    agentProjectCombo.getSelectionModel().selectFirst();
                    agentStatus.setText(projects.isEmpty() ? "Für diesen Zugang sind keine Projekte freigegeben." : "Verbunden. Aufgabe beschreiben oder vorhandenen Auftrag öffnen.");
                    modelPicker.configure(ModelCatalogClient.workflow(baseUrl));
                    updateControls();
                });
            } catch (Exception error) {
                Platform.runLater(() -> { if (generation == epoch) { connecting = false; showError(error); updateControls(); } });
            }
        });
    }

    @FXML public void disconnect() {
        AgentActivity.unavailable(currentJob, "Verbindung getrennt; Serverauftrag kann weiterlaufen");
        ++epoch; nextPoll.stop(); client = null; connecting = false; commandBusy = false; reads = 0;
        projectToken.clear(); operatorToken.clear(); currentJob = null; pendingStart = null;
        agentProjectCombo.getItems().clear(); displayedJobId.clear(); clearReview(); pollFailures = 0;
        qualityStatus.setText("Aufgabenqualität: noch keine Prüfung belegt.");
        showDiagnostics(null);
        budgetLabel.setText("Budgetdaten erscheinen nach dem Start."); budgetProgress.setProgress(0);
        agentStatus.setText("Getrennt. Ein laufender Auftrag auf dem Server wird dadurch nicht abgebrochen.");
        updateControls();
    }

    @FXML private void queueAdd() {
        if (!queuePaneController.canAdd() || commandBusy || pendingStart != null) return;
        try { queuePaneController.add(draft()); }
        catch (IOException | IllegalArgumentException error) { showError(error); }
    }

    @FXML private void start() {
        if (client == null || commandBusy || currentJob != null || queuePaneController.draftLocked()) return;
        try {
            if (pendingStart == null) pendingStart = draft();
        } catch (IOException | IllegalArgumentException error) { showError(error); return; }
        final StartRequest submitted = pendingStart;
        final AgentClient connection = client;
        agentStatus.setText("Auftrag wird angelegt. Bei Verbindungsfehlern verwendet „Start erneut anfragen“ dieselbe Kennung.");
        command(() -> connection.start(submitted), this::acceptJob, true);
    }

    private StartRequest draft() throws IOException {
        Project project = agentProjectCombo.getValue();
        if (project == null) throw new IOException("Bitte ein freigegebenes Projekt auswählen.");
        List<DocumentReference> references = new ArrayList<>();
        for (String line : lines(documentReferences.getText())) {
            String[] fields = line.split("\\s+");
            if (fields.length != 3) throw new IOException("Jede Dokumentfreigabe braucht Kontext-ID, Revision und SHA-256, durch Leerzeichen getrennt.");
            long revision;
            try { revision = Long.parseLong(fields[1]); } catch (NumberFormatException e) { throw new IOException("Die Dokumentrevision muss eine ganze Zahl sein."); }
            references.add(new DocumentReference(fields[0], revision, fields[2]));
        }
        List<RequiredDiagram> diagrams = new ArrayList<>();
        for (String line : umlEnabled.isSelected() ? lines(requiredDiagrams.getText()) : List.<String>of()) {
            String[] fields = line.split("\\s+");
            if (fields.length != 2) throw new IOException("Je Pflichtdiagramm eine Kennung und den Typ class oder object angeben.");
            diagrams.add(new RequiredDiagram(fields[0], fields[1]));
        }
        boolean uml = umlEnabled.isSelected();
        if (uml && !project.uml()) throw new IOException("Dieses Projekt hat keine UML-Freigabe.");
        StartRequest request = new StartRequest(project.projectId(), taskText.getText(), UUID.randomUUID().toString(),
                modelPicker.selection(), List.copyOf(references), uml, List.copyOf(diagrams),
                modelSeconds.getValue(), actionBudget.getValue(), roundBudget.getValue());
        AgentClient.startBody(request);
        return request;
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    @FXML private void newTask() {
        if (commandBusy || currentJob != null && currentJob.active()) return;
        if (pendingStart != null && currentJob == null) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Die letzte Startantwort fehlt. Der Auftrag könnte bereits laufen. Erst „Start erneut anfragen“ stellt seine Kennung wieder her.\n\nTrotzdem einen neuen Auftrag vorbereiten?",
                    ButtonType.CANCEL, ButtonType.OK);
            alert.setTitle("Ungeklärten Start verlassen");
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        ++epoch; nextPoll.stop(); reads = 0; currentJob = null; pendingStart = null;
        displayedJobId.clear(); clearReview(); pollFailures = 0;
        qualityStatus.setText("Aufgabenqualität: noch keine Prüfung belegt.");
        showDiagnostics(null);
        budgetLabel.setText("Budgetdaten erscheinen nach dem Start."); budgetProgress.setProgress(0);
        agentStatus.setText("Neuer Auftrag. Die Eingaben können jetzt bearbeitet werden."); updateControls();
    }

    @FXML private void reopen() {
        if (client == null || commandBusy || currentJob != null && currentJob.active()) return;
        String id = reopenId.getText().strip();
        if (!id.matches("[0-9a-f]{32}")) { agentStatus.setText("Bitte eine vollständige Auftrags-ID mit 32 Zeichen eingeben."); return; }
        ++epoch; nextPoll.stop(); reads = 0; clearReview(); pendingStart = null; currentJob = null;
        qualityStatus.setText("Aufgabenqualität: noch keine Prüfung für diesen Auftrag geladen.");
        showDiagnostics(null);
        final AgentClient connection = client;
        command(() -> connection.status(id), this::acceptJob, false);
    }

    @FXML private void refreshStatus() {
        if (client == null || currentJob == null || reads != 0 || commandBusy) { schedulePoll(); return; }
        final String id = currentJob.jobId();
        final AgentClient connection = client;
        read(() -> connection.status(id), job -> { pollFailures = 0; acceptJob(job); }, true);
    }

    @FXML private void cancel() {
        if (currentJob == null || !currentJob.active() || adoptionPending(currentJob) || commandBusy) return;
        final String id = currentJob.jobId();
        final AgentClient connection = client;
        ++epoch; nextPoll.stop(); reads = 0; clearReview();
        command(() -> connection.cancel(id), this::acceptJob, false);
    }

    @FXML private void resume() {
        if (currentJob == null || !currentJob.status().equals("recovery_required") || adoptionPending(currentJob) || commandBusy) return;
        final String id = currentJob.jobId();
        final AgentClient connection = client;
        ++epoch; nextPoll.stop(); reads = 0; clearReview();
        command(() -> connection.resume(id), this::acceptJob, false);
    }

    @FXML private void loadReview() {
        if (currentJob == null || !currentJob.reviewAvailable() || reads != 0 || commandBusy) return;
        final String id = currentJob.jobId();
        final AgentClient connection = client;
        clearReview();
        read(() -> connection.review(id), review -> {
            if (!matchesProject(review.job())) return;
            currentJob = review.job(); currentReview = review;
            diffText.setText(review.diff());
            checkText.setText(review.checks().stream().map(c -> c.name() + " · " + c.status() + " · Exit " + c.exitCode())
                    .reduce((a,b) -> a + "\n" + b).orElse("Keine Prüfergebnisse.") + qualityDetails(review.quality()));
            qualityStatus.setText(qualityLabel(review.quality()));
            showDiagnostics(review.job().diagnostics());
            operationText.setText(String.join("\n", review.operations()) + (review.warnings().isEmpty() ? "" : "\n\nHinweise:\n" + String.join("\n", review.warnings())));
            reviewIdentity.setText("Review " + review.reviewId() + "\nSHA-256 des Gesamtdiffs: " + review.diffSha256());
            diagramCombo.getItems().setAll(review.diagrams()); diagramCombo.getSelectionModel().selectFirst();
            confirmationHint.setText(review.adoptionAvailable() ? "Für diese Übernahme exakt eingeben:\n" + review.confirmation() : "Die Übernahme ist für dieses Projekt nicht freigegeben.");
            agentStatus.setText("Prüfstand geladen. Gesamtdiff, Tests und Diagramme vor der Übernahme prüfen.");
            updateControls();
        }, false);
    }

    @FXML private void loadDiagram() {
        if (currentReview == null || diagramCombo.getValue() == null || reads != 0) return;
        Review review = currentReview;
        DiagramInfo selected = diagramCombo.getValue();
        final AgentClient connection = client;
        diagramImage.setImage(null); diagramStatus.setText("Prüfe und lade Diagramm …");
        read(() -> connection.diagram(review.job().jobId(), selected), diagram -> {
            if (currentReview != review || !selected.equals(diagramCombo.getValue())) return;
            Image image = new Image(new ByteArrayInputStream(diagram.png()));
            if (image.isError() || image.getWidth() != selected.width() || image.getHeight() != selected.height()) {
                diagramStatus.setText("Die Bilddatei konnte nicht sicher dargestellt werden."); return;
            }
            diagramImage.setImage(image);
            diagramStatus.setText(selected + " · " + selected.width() + " × " + selected.height() + " Pixel · SHA-256 geprüft");
        }, false);
    }

    @FXML private void apply() {
        if (applyButton.isDisabled() || currentReview == null) return;
        Review review = currentReview;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Die geprüften Änderungen jetzt in das Projekt „" + review.job().projectId() + "“ übernehmen?\n\n"
                        + review.operations().size() + " Dateiänderung(en)\nReview: " + review.reviewId()
                        + "\nDiff-SHA-256: " + review.diffSha256(),
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Geprüfte Änderungen übernehmen");
        confirm.setHeaderText("Diese Bestätigung verändert die freigegebenen Projektdateien.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK || currentReview != review) return;
        final String credential = operatorToken.getText(), operator = operatorName.getText(), confirmation = confirmationInput.getText();
        final AgentClient connection = client;
        operatorToken.clear(); reviewedCheck.setSelected(false); confirmationInput.clear();
        command(() -> connection.apply(review, credential, operator, confirmation), result -> {
            acceptJob(result.job()); clearReview();
            agentStatus.setText("Änderungen übernommen. Transaktion: " + result.transactionId());
        }, false);
    }

    private void acceptJob(Job job) {
        if (!matchesProject(job)) return;
        AgentActivity.job(job);
        if (currentJob != null && (!currentJob.jobId().equals(job.jobId()) || !currentJob.status().equals(job.status()))) clearReview();
        currentJob = job; pendingStart = null; displayedJobId.setText(job.jobId()); reopenId.setText(job.jobId());
        long settledMilliseconds = Math.max(0, job.usedMilliseconds() - job.reservedMilliseconds());
        long remainingAfterSettlement = job.remainingMilliseconds() + job.reservedMilliseconds();
        budgetLabel.setText("Abgerechnete Agentenzeit: " + seconds(settledMilliseconds) + " s · "
                + "Restbudget nach dieser Abrechnung: " + seconds(remainingAfterSettlement) + " s · " + actionBudgetText(job)
                + (job.reservedMilliseconds() > 0 ? "\nDavon für den laufenden Aufruf reserviert: "
                + seconds(job.reservedMilliseconds()) + " s. Sein tatsächlicher Verbrauch wird erst nach Abschluss abgerechnet." : ""));
        budgetProgress.setProgress(Math.min(1, (double) settledMilliseconds / (job.modelSeconds() * 1000)));
        agentStatus.setText((adoptionPending(job)
                ? "Eine Übernahme läuft oder wurde unterbrochen. Bei Unterbrechung ist eine manuelle Übernahmeprüfung erforderlich; Abbruch und Wiederaufnahme sind gesperrt."
                : statusLabel(job.status())) + (job.errorCode().isEmpty() ? "" : " · " + job.errorCode()));
        qualityStatus.setText(qualityLabel(job.quality()));
        showDiagnostics(job.diagnostics());
        updateControls(); schedulePoll();
    }

    private void showDiagnostics(Diagnostics diagnostics) {
        if (diagnostics == null) {
            stageOverview.setText("Für diesen Auftrag liegt keine Stufenübersicht vor. Ältere Aufträge enthalten diese Diagnose noch nicht.");
            stageEvidence.setText("Keine Diagnosebelege geladen.");
            return;
        }
        List<String> rows = new ArrayList<>(), evidence = new ArrayList<>();
        for (StageEvidence stage : diagnostics.stages()) {
            String label = stageLabel(stage.id());
            String state = switch (stage.status()) {
                case "passed" -> "im angegebenen Umfang bestanden";
                case "failed" -> "fehlgeschlagen";
                case "partial" -> "teilweise belegt";
                case "not_required" -> "nicht erforderlich";
                default -> "nicht ausgeführt / noch nicht belegt";
            };
            List<String> counts = new ArrayList<>();
            stage.counts().forEach((key, count) -> counts.add(countLabel(key) + ": " + count));
            rows.add(label + ": " + state + (counts.isEmpty() ? "" : " · " + String.join(" · ", counts)));
            stage.hashes().forEach((key, hash) -> evidence.add(label + " · " + key + "\n" + hash));
        }
        stageOverview.setText(String.join("\n", rows));
        stageEvidence.setText(evidence.isEmpty() ? "Für die angezeigten Stufen sind noch keine Hashbelege vorhanden." : String.join("\n\n", evidence));
    }

    private static String stageLabel(String id) {
        return switch (id) {
            case "sources" -> "Quellen und Modellkontext";
            case "action_schema" -> "Werkzeugaufruf und Schema";
            case "rendering" -> "Diagrammerzeugung";
            case "proposal" -> "Änderungsvorschlag";
            case "compilation" -> "Kompilierung";
            case "behavior_tests" -> "Verhaltenstests";
            case "task_acceptance" -> "Aufgabenprüfung im festgelegten Profil";
            case "adoption" -> "Übernahme ins Projekt";
            default -> "Prüfstufe";
        };
    }

    private static String countLabel(String key) {
        return switch (key) {
            case "required_count" -> "benötigt";
            case "read_count" -> "gelesen";
            case "context_bound_count" -> "im Kontext";
            case "request_count" -> "Aufrufe";
            case "failure_count" -> "Fehler";
            case "artifact_count" -> "Diagramme";
            case "attempt_count" -> "Versuche";
            case "file_count" -> "Dateien";
            case "rejection_count" -> "abgewiesene Antworten";
            default -> "Anzahl";
        };
    }

    private static String qualityLabel(Quality quality) {
        if (quality == null) return "Aufgabenqualität: noch keine Prüfung belegt.";
        return quality.status().equals("passed")
                ? "Aufgabenprüfung " + quality.profile() + ": im festgelegten Prüfumfang bestanden. Fachliche Gesamtprüfung bleibt erforderlich."
                : "Aufgabenprüfung " + quality.profile() + ": nicht bestanden. Keine fachliche Abnahme. Fehlgeschlagene Prüfungen: "
                        + String.join(", ", quality.failedChecks());
    }

    private static String qualityDetails(Quality quality) {
        if (quality == null) return "\n\nAufgabenqualität: noch keine gesonderte Prüfung belegt.";
        return "\n\n" + qualityLabel(quality) + "\nRunner: " + quality.runnerStatus()
                + "\nVerhaltenstests ausgeführt: " + (quality.behaviorTestsExecuted() ? "ja" : "nein")
                + "\nBericht-SHA-256: " + quality.reportSha256();
    }

    private boolean matchesProject(Job job) {
        Project project = agentProjectCombo.getValue();
        if (project == null || !project.projectId().equals(job.projectId())) {
            clearReview(); agentStatus.setText("Der Auftrag gehört nicht zum ausgewählten Projekt."); return false;
        }
        return true;
    }

    private static String seconds(long milliseconds) { return String.format(Locale.GERMANY, "%.1f", milliseconds / 1000.0); }
    private static String actionBudgetText(Job job) {
        ActionUsage usage = job.actionUsage();
        if (usage == null || usage.policy().equals("legacy")) return "Aktionen " + job.actionsUsed() + "/" + job.actionBudget() + " (bisheriges Budget)";
        String result = "Normale Modellversuche " + usage.normalUsed() + "/" + usage.normalBudget()
                + " · Gesamt " + usage.totalUsed() + "/" + usage.totalBudget();
        for (UmlUsage diagram : usage.diagrams()) result += "\nUML „" + diagram.diagramId() + "“ (" + diagram.diagramType()
                + "): " + diagram.attemptsUsed() + "/" + diagram.attemptBudget() + " Versuche";
        return result;
    }
    private static boolean adoptionPending(Job job) { return job.phase().startsWith("adoption_") && !job.status().equals("completed"); }
    private static String statusLabel(String status) {
        return switch (status) {
            case "held" -> "Auftrag wartet in der pausierbaren Warteliste auf Freigabe";
            case "queued" -> "Auftrag wartet auf Ausführung";
            case "running" -> "Agent arbeitet. Auch längere Modellaufrufe laufen weiter.";
            case "awaiting_test" -> "Änderungen werden geprüft";
            case "awaiting_review" -> "Bereit zur Prüfung — „Prüfstand laden“ öffnet das Ergebnis";
            case "cancel_requested" -> "Abbruch angefordert — warte auf Bestätigung";
            case "recovery_required" -> "Wiederaufnahme nach Unterbrechung erforderlich";
            case "completed" -> "Auftrag abgeschlossen";
            case "cancelled" -> "Auftrag abgebrochen";
            case "exhausted" -> "Auftragsbudget ausgeschöpft";
            default -> "Auftrag fehlgeschlagen";
        };
    }

    private void clearReview() {
        currentReview = null; reviewedCheck.setSelected(false); confirmationInput.clear(); operatorToken.clear();
        diffText.clear(); checkText.clear(); operationText.clear(); reviewIdentity.setText("Noch kein Prüfstand geladen.");
        diagramCombo.getItems().clear(); diagramImage.setImage(null); diagramStatus.setText("Noch kein Diagramm geladen.");
        confirmationHint.setText("Übernahme erst nach dem Laden und Prüfen eines aktuellen Ergebnisses.");
    }

    private void schedulePoll() {
        nextPoll.stop();
        if (client != null && currentJob != null && currentJob.active() && pollFailures < 3) nextPoll.playFromStart();
    }

    private interface Work<T> { T run() throws Exception; }
    private <T> void command(Work<T> work, Consumer<T> success, boolean retainPending) {
        if (commandBusy || client == null) return;
        commandBusy = true; nextPoll.stop(); updateControls();
        long generation = epoch;
        Thread.startVirtualThread(() -> {
            try {
                T result = work.run();
                Platform.runLater(() -> {
                    if (generation != epoch) return;
                    commandBusy = false; success.accept(result); updateControls(); schedulePoll();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (generation != epoch) return;
                    commandBusy = false; clearReview(); showError(error);
                    if (retainPending) agentStatus.setText(agentStatus.getText() + " „Start erneut anfragen“ nutzt denselben Auftrag.");
                    updateControls(); schedulePoll();
                });
            }
        });
    }

    private <T> void read(Work<T> work, Consumer<T> success, boolean polling) {
        ++reads; updateControls(); long generation = epoch;
        Thread.startVirtualThread(() -> {
            try {
                T result = work.run();
                Platform.runLater(() -> {
                    if (generation != epoch) return;
                    --reads; success.accept(result); updateControls(); schedulePoll();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (generation != epoch) return;
                    --reads; clearReview(); showError(error);
                    if (polling) {
                        AgentActivity.unavailable(currentJob, "Agentenstatus konnte nicht aktualisiert werden");
                        ++pollFailures;
                        if (pollFailures >= 3) agentStatus.setText(agentStatus.getText() + " Automatische Statusabfrage pausiert; „Status laden“ versucht es erneut.");
                    }
                    updateControls(); schedulePoll();
                });
            }
        });
    }

    private void showError(Exception error) {
        // Only the client's curated messages and local validation reach the screen.
        agentStatus.setText(error instanceof IOException ? error.getMessage() : "Die Aktion konnte nicht abgeschlossen werden. Eingaben und Verbindung prüfen.");
    }

    private void updateControls() {
        boolean connected = client != null, draft = connected && pendingStart == null && !commandBusy && !queuePaneController.draftLocked();
        connectButton.setDisable(connected || connecting || baseUrl == null); disconnectButton.setDisable(!connected && !connecting);
        projectToken.setDisable(connected || connecting);
        agentProjectCombo.setDisable(!draft || currentJob != null);
        requestForm.setDisable(!draft); modelPicker.setLocked(!draft);
        Project project = agentProjectCombo.getValue();
        umlEnabled.setDisable(!draft || project == null || !project.uml());
        requiredDiagrams.setDisable(!draft || !umlEnabled.isSelected() || project == null || !project.uml());
        startButton.setDisable(!connected || project == null || commandBusy || currentJob != null || queuePaneController.draftLocked());
        queueAddButton.setDisable(!connected || project == null || commandBusy || pendingStart != null || !queuePaneController.canAdd());
        queuePaneController.allowOpen(connected && !commandBusy && pendingStart == null && (currentJob == null || !currentJob.active()));
        startButton.setText(pendingStart == null ? "Auftrag starten" : "Start erneut anfragen");
        newButton.setDisable(commandBusy || currentJob == null && pendingStart == null || currentJob != null && currentJob.active());
        reopenButton.setDisable(!connected || commandBusy || pendingStart != null || project == null || currentJob != null && currentJob.active());
        reopenId.setDisable(reopenButton.isDisabled());
        refreshStatusButton.setDisable(!connected || currentJob == null || commandBusy || reads > 0);
        cancelButton.setDisable(!connected || currentJob == null || !currentJob.active() || adoptionPending(currentJob) || commandBusy);
        resumeButton.setDisable(!connected || currentJob == null || !currentJob.status().equals("recovery_required") || adoptionPending(currentJob) || commandBusy);
        reviewButton.setDisable(!connected || currentJob == null || !currentJob.reviewAvailable() || commandBusy || reads > 0);
        diagramButton.setDisable(currentReview == null || diagramCombo.getValue() == null || commandBusy || reads > 0);
        boolean adoptable = currentReview != null && currentReview.adoptionAvailable() && project != null && project.apply();
        adoptionBox.setDisable(!adoptable || commandBusy);
        applyButton.setDisable(!adoptable || commandBusy || reads > 0 || !reviewedCheck.isSelected()
                || operatorToken.getText().isBlank() || operatorName.getText().isBlank()
                || !confirmationInput.getText().equals(currentReview == null ? "" : currentReview.confirmation()));
    }
}

package kioberflaeche.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import kioberflaeche.story.*;
import kioberflaeche.ai.*;
import kioberflaeche.ui.ModelPicker;
import kioberflaeche.ui.EphemeralTextDialog;
import kioberflaeche.util.SimpleJson;
import kioberflaeche.queue.ActivityRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** The story pane owns its files, task snapshots and explicit review flow. */
public class StoryPaneController {
    public enum Mode {
        CONTEXT("Kontext aus Quellen aufbauen / aktualisieren"), WRITE("Szene schreiben / fortsetzen");
        private final String label;
        Mode(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }
    private record Draft(String original, String content) {}
    private record Task(Path project, Mode mode, String language, String instruction, String target, String modelSelection, Map<String,String> drafts) {}

    @FXML private VBox projectPane, taskPane, reviewPane, targetRow, savedTaskInputs;
    @FXML private TextField workspaceField, projectNameField, targetPathField;
    @FXML private TextArea briefingArea, sourceEditor, instructionArea, originalArea, proposedArea, detailsArea;
    @FXML private ComboBox<String> projectCombo, languageCombo;
    @FXML private ComboBox<Mode> modeCombo;
    @FXML private ComboBox<StoryReviewStore.SavedTask> savedTaskCombo;
    @FXML private ListView<StoryFile> sourceList;
    @FXML private ListView<StoryReviewStore.Change> proposalList;
    @FXML private Label statusLabel, coverageLabel, sourcePathLabel, proposalPathLabel, resultLabel, sourceDirtyLabel,
            instructionBudgetLabel, targetHintLabel;
    @FXML private Button chooseWorkspaceButton, refreshProjectsButton, createProjectButton, saveSourceButton,
            discardSourceButton, runTaskButton, recoverTaskButton, applyOneButton, applyAllButton;
    @FXML private CheckBox selectedSourceCheck;
    @FXML private CheckBox memoryOnlyCheck;
    @FXML private Label storageModeLabel;
    @FXML private Button openMemoryOnlyButton;

    @FXML private ComboBox<ModelCatalogClient.Option> storyModelCombo;
    @FXML private Label storyModelHint, storyModelReport;
    @FXML private Button refreshStoryModelsButton;
    private ModelPicker modelPicker;
    private SchreibAiWorkflowClient client;
    private Path project;
    private String editorPath;
    private String editorOriginal="";
    private final Map<Path,Map<String,Draft>> draftsByProject=new LinkedHashMap<>();
    private StoryReviewStore.Review review;
    private final Map<Path, ActivityRegistry.Handle> reviewActivities = new LinkedHashMap<>();
    private boolean busy, changingProject, changingEditor, changingProposal;

    @FXML private void initialize() {
        workspaceField.setText("C:\\KI Arbeitsbereich\\SchreibAI\\Arbeitsprojekte");
        modeCombo.getItems().setAll(Mode.values()); modeCombo.setValue(Mode.CONTEXT);
        languageCombo.getItems().setAll("Englisch", "Deutsch"); languageCombo.setValue("Englisch");
        modelPicker=new ModelPicker(storyModelCombo,storyModelHint,refreshStoryModelsButton,()->modeCombo.getValue()==Mode.WRITE?"story_write":"story_context");
        storyModelCombo.valueProperty().addListener((o,a,b)-> {
            boolean dolphin=b!=null && EphemeralTextClient.MODEL_ID.equals(b.id());
            if(a==null || b==null || !a.id().equals(b.id())) memoryOnlyCheck.setSelected(dolphin);
            memoryOnlyCheck.setDisable(!dolphin);updatePrivacyMode();
        });
        memoryOnlyCheck.selectedProperty().addListener((o,a,b)->updatePrivacyMode());
        openMemoryOnlyButton.setOnAction(e->openMemoryOnly());
        modeCombo.valueProperty().addListener((o,a,b)->updateMode());
        instructionArea.textProperty().addListener((o,a,b)->updateInstructionBudget());
        selectedSourceCheck.selectedProperty().addListener((o,a,b)->updateInstructionBudget());
        targetPathField.textProperty().addListener((o,a,b)->updateTargetHint());
        languageCombo.valueProperty().addListener((o,a,b)-> { if(!changingProject && project!=null) saveLanguage(); });
        projectCombo.valueProperty().addListener((o,a,b)-> { if(!changingProject) selectProject(b); });
        sourceList.setCellFactory(list->new ListCell<>() {
            @Override protected void updateItem(StoryFile file, boolean empty) {
                super.updateItem(file,empty); setText(empty||file==null?null:file.layer()+" · "+file.path());
            }
        });
        sourceList.getSelectionModel().selectedItemProperty().addListener((o,a,b)-> { if(b!=null && !busy) loadSource(b); });
        sourceEditor.textProperty().addListener((o,a,b)-> {
            if(!changingEditor && project!=null && editorPath!=null) {
                if(b.equals(editorOriginal)) drafts().remove(editorPath); else drafts().put(editorPath,new Draft(editorOriginal,b));
                updateDirtyLabel();
            }
        });
        proposalList.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showProposal(b));
        proposedArea.textProperty().addListener((o,a,b)-> {
            var selected=proposalList.getSelectionModel().getSelectedItem();
            if(!changingProposal && selected!=null && !selected.applied && selected.writeIntent==null) selected.content=b;
        });
        chooseWorkspaceButton.setOnAction(e->chooseWorkspace());
        refreshProjectsButton.setOnAction(e->refreshProjects(project==null?null:project.getFileName().toString()));
        createProjectButton.setOnAction(e->createProject());
        saveSourceButton.setOnAction(e->saveSource());
        discardSourceButton.setOnAction(e->discardSourceDraft());
        runTaskButton.setOnAction(e->runTask());
        recoverTaskButton.setOnAction(e->recoverTask());
        applyOneButton.setOnAction(e->apply(false)); applyAllButton.setOnAction(e->apply(true));
        memoryOnlyCheck.setDisable(true);updateMode(); updatePrivacyMode(); updateButtons();
    }

    public void configure(SchreibAiWorkflowClient client) {
        this.client=client;
        modelPicker.configure(ModelCatalogClient.workflow(client.baseUrl()));
        refreshProjects(null);
        updateButtons();
    }

    private void updateMode() {
        boolean writing=modeCombo.getValue()==Mode.WRITE;
        if(modelPicker!=null) modelPicker.refilter();
        targetRow.setVisible(writing); targetRow.setManaged(writing);
        selectedSourceCheck.setVisible(writing); selectedSourceCheck.setManaged(writing);
        instructionArea.setPromptText(writing ? "Welche Szene soll entstehen oder wie soll sie fortgesetzt werden?" : "Was soll bei der Auswertung und beim Kontextaufbau besonders beachtet werden?");
        updateInstructionBudget(); updateTargetHint();
    }

    private String taskInstruction() {
        return StoryWorkspace.instruction(instructionArea.getText(),modeCombo.getValue()==Mode.WRITE
                && selectedSourceCheck.isSelected()?editorPath:null);
    }

    private void updateInstructionBudget() {
        int bytes=StoryWorkspace.instructionBytes(taskInstruction());
        instructionBudgetLabel.setText(bytes+" / 1200 UTF-8-Bytes inklusive Dateiverweis. Laengere Inhalte bitte als Quelle oder Briefing ablegen; Sonderzeichen benoetigen mehrere Bytes.");
        instructionBudgetLabel.setStyle(bytes>1200?"-fx-text-fill: #fbbf24;":"");
    }

    private void updateTargetHint() {
        String message="Neue Szene: Der Vorschlag enthaelt die komplette Datei. Es wird nichts automatisch angehaengt.";
        if(project!=null) try {
            String path=StoryWorkspace.sceneTarget(project,targetPathField.getText());
            if(Files.exists(StoryWorkspace.resolve(project,path))) message="Bestehende Datei: Bei Uebernahme ersetzt der Vorschlag den gesamten Inhalt. Bitte Original und Vorschlag vergleichen; es wird nichts automatisch angehaengt.";
        } catch(IOException|RuntimeException error) { message="Ziel: manuscript/name.md, bei Bedarf mit Unterordnern. Bestehende Dateien werden nach Pruefung vollstaendig ersetzt."; }
        targetHintLabel.setText(message);
    }

    private void chooseWorkspace() {
        if(busy || !persistLocalState()) return;
        DirectoryChooser chooser=new DirectoryChooser(); chooser.setTitle("Story-Arbeitsplatz waehlen");
        Path current=Path.of(workspaceField.getText());
        if(Files.isDirectory(current)) chooser.setInitialDirectory(current.toFile());
        var chosen=chooser.showDialog(workspaceField.getScene()==null?null:workspaceField.getScene().getWindow());
        if(chosen!=null) { workspaceField.setText(chosen.getAbsolutePath()); refreshProjects(null); }
    }

    private void refreshProjects(String preferred) {
        if(busy || !persistLocalState()) return;
        try {
            Path workspace=Path.of(workspaceField.getText()).toAbsolutePath().normalize();
            List<String> names=new ArrayList<>();
            if(Files.isDirectory(workspace)) try(var stream=Files.list(workspace)) {
                names=stream.filter(Files::isDirectory).filter(path->!path.getFileName().toString().startsWith("."))
                        .map(path->path.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
            changingProject=true;
            projectCombo.getItems().setAll(names);
            String selected=preferred!=null && names.contains(preferred)?preferred:names.isEmpty()?null:names.get(0);
            projectCombo.setValue(selected); changingProject=false;
            selectProject(selected);
        } catch(IOException|RuntimeException error) { changingProject=false; status("Projektliste konnte nicht geladen werden",error); }
    }

    private void selectProject(String name) {
        if(busy) return;
        Path previous=project;
        if(!persistLocalState()) {
            changingProject=true; projectCombo.setValue(previous==null?null:previous.getFileName().toString()); changingProject=false; return;
        }
        try {
            clearReview(); editorPath=null; editorOriginal=""; changingEditor=true; sourceEditor.clear(); changingEditor=false;
            sourcePathLabel.setText("Keine Datei ausgewaehlt"); sourceList.getItems().clear();
            project=name==null?null:Path.of(workspaceField.getText()).resolve(name).toRealPath();
            if(project!=null) {
                Path workspace=Path.of(workspaceField.getText()).toRealPath();
                if(!project.startsWith(workspace)) throw new IOException("Projektverknuepfung liegt ausserhalb des Arbeitsplatzes.");
                loadDrafts(); loadLanguage(); targetPathField.setText(StoryWorkspace.freshSceneTarget(project)); refreshFiles(); refreshSavedTasks();
                statusLabel.setText("Projekt bereit. Quellen und Kontext werden vollstaendig an den Auftrag uebergeben.");
            } else { savedTaskCombo.getItems().clear(); coverageLabel.setText("Noch kein Projekt"); statusLabel.setText("Ein Projekt auswaehlen oder neu anlegen."); }
        } catch(IOException|RuntimeException error) { project=null; status("Projekt konnte nicht geoeffnet werden",error); }
        updateDirtyLabel(); updateInstructionBudget(); updateTargetHint(); updateButtons();
    }

    private void createProject() {
        if(busy || !persistLocalState()) return;
        try {
            Path created=StoryWorkspace.createProject(Path.of(workspaceField.getText()),projectNameField.getText(),briefingArea.getText());
            projectNameField.clear(); briefingArea.clear(); refreshProjects(created.getFileName().toString());
            statusLabel.setText("Neues Projekt mit eigener Briefing-Datei angelegt. Bestehende Projekte bleiben unveraendert.");
        } catch(IOException|RuntimeException error) { status("Projekt konnte nicht angelegt werden",error); }
    }

    private void refreshFiles() throws IOException {
        String selected=editorPath;
        List<StoryFile> files=StoryWorkspace.files(project); sourceList.getItems().setAll(files);
        long sources=files.stream().filter(f->!StoryWorkspace.automaticCopy(f.path()) && !StoryWorkspace.context(f.path())).count();
        long contexts=files.stream().filter(f->!StoryWorkspace.automaticCopy(f.path()) && StoryWorkspace.context(f.path())).count();
        long automatic=files.size()-sources-contexts;
        coverageLabel.setText(sources+" Quelldatei(en) · "+contexts+" Kontextdatei(en) · "+automatic+" automatische Datei(en) nicht erneut eingelesen");
        StoryFile select=files.stream().filter(f->f.path().equals(selected)).findFirst().orElse(files.isEmpty()?null:files.get(0));
        if(select!=null) { sourceList.getSelectionModel().select(select); if(busy) loadSource(select); }
    }

    private void loadSource(StoryFile file) {
        try {
            String disk=Files.readString(StoryWorkspace.resolve(project,file.path()),StandardCharsets.UTF_8);
            Draft draft=drafts().get(file.path()); editorPath=file.path(); editorOriginal=draft==null?disk:draft.original();
            changingEditor=true; sourceEditor.setText(draft==null?disk:draft.content()); changingEditor=false;
            sourcePathLabel.setText(file.path()); updateDirtyLabel(); updateInstructionBudget(); updateButtons();
        } catch(IOException error) { changingEditor=false; status("Datei konnte nicht geladen werden",error); }
    }

    private void saveSource() {
        if(busy || project==null || editorPath==null) return;
        try {
            Path target=StoryWorkspace.resolve(project,editorPath);
            if(!Files.isRegularFile(target) || !StoryWorkspace.hash(Files.readString(target,StandardCharsets.UTF_8)).equals(StoryWorkspace.hash(editorOriginal))) {
                throw new IOException("Datei wurde zwischenzeitlich geaendert. Der Editorinhalt bleibt als Entwurf erhalten.");
            }
            StoryWorkspace.backup(project,editorPath,"editor-"+UUID.randomUUID());
            StoryWorkspace.writeText(project,editorPath,sourceEditor.getText());
            editorOriginal=sourceEditor.getText(); drafts().remove(editorPath); saveDrafts(); updateDirtyLabel();
            statusLabel.setText("Datei gespeichert; vorheriger Inhalt gezielt gesichert.");
        } catch(IOException error) { status("Datei wurde nicht gespeichert",error); }
    }

    private void discardSourceDraft() {
        if(project==null || editorPath==null || busy) return;
        drafts().remove(editorPath);
        try { saveDrafts(); for(StoryFile file:sourceList.getItems()) if(file.path().equals(editorPath)) { loadSource(file); break; } }
        catch(IOException error) { status("Editor konnte nicht neu geladen werden",error); }
    }

    private void runTask() {
        if(!busy && memoryOnlyCheck.isSelected()) { openMemoryOnly();return; }
        if(busy || project==null || client==null || !client.isConfigured() || !persistLocalState()) return;
        Mode mode=modeCombo.getValue();
        String instruction=taskInstruction();
        String target=targetPathField.getText();
        try {
            if(mode==Mode.WRITE) {
                if(instructionArea.getText().isBlank()) throw new IOException("Bitte beschreiben, welche Szene geschrieben oder fortgesetzt werden soll.");
                target=StoryWorkspace.sceneTarget(project,target);
            }
            StoryWorkspace.validateInstruction(instruction);
        } catch(IOException|RuntimeException error) { status("Auftrag kann noch nicht starten",error); return; }
        Map<String,String> overlays=new LinkedHashMap<>(); drafts().forEach((path,draft)->overlays.put(path,draft.content()));
        String selection;
        try { selection=modelPicker.selection(); } catch(IOException error) { status("Modellauswahl pruefen",error); return; }
        Task task=new Task(project,mode,languageCombo.getValue().equals("Deutsch")?"Deutsch":"English",instruction,target,selection,Map.copyOf(overlays));
        setBusy(true); statusLabel.setText("Lese vollstaendige Quellen und Kontextdateien fuer den Auftrag …");
        var activity = ActivityRegistry.shared().start("Story", task.project().getFileName() + " - " + mode,
                "Quellen und Kontext werden gelesen");
        Thread.startVirtualThread(()-> {
            try {
                StoryWorkspace.Snapshot snapshot=StoryWorkspace.snapshot(task.project(),task.drafts());
                if(snapshot.documents().isEmpty() && snapshot.contextDocuments().isEmpty()) throw new IOException("Das Projekt enthaelt keine verwendbaren Markdown- oder Textdateien.");
                activity.running("KI-Antwort ausstehend: " + snapshot.documents().size() + " Quellen, "
                        + snapshot.contextDocuments().size() + " Kontextdateien");
                Platform.runLater(()->statusLabel.setText("Auftrag laeuft: "+snapshot.documents().size()+" Quellen und "+snapshot.contextDocuments().size()+" Kontextdateien. Umfangreiche Auswertungen koennen dauern."));
                var result=task.mode()==Mode.CONTEXT
                        ? client.buildContext(task.project().getFileName().toString(),task.language(),task.instruction(),snapshot.documents(),snapshot.contextDocuments(),task.modelSelection())
                        : client.writeScene(task.project().getFileName().toString(),task.language(),task.instruction(),task.target(),snapshot.documents(),snapshot.contextDocuments(),task.modelSelection());
                var saved=StoryReviewStore.create(snapshot,task.mode().toString(),task.instruction(),result);
                if(saved.complete()) StoryReviewStore.archiveCompleted(saved);
                activity.state(saved.complete() ? ActivityRegistry.State.DONE : ActivityRegistry.State.REVIEW,
                        saved.complete() ? "Ergebnis gespeichert" : "Vorschlaege im Story-Tab pruefen");
                Platform.runLater(()-> {
                    reviewActivities.put(saved.file, activity);
                    showReview(saved); statusLabel.setText(result.warning().isBlank()?"Auftrag ausgewertet. Vorschlaege vor dem Uebernehmen pruefen.":result.warning());
                    finishTask();
                });
            } catch(Exception error) {
                activity.failed("Auftrag fehlgeschlagen; Details im Story-Tab");
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                StoryReviewStore.Review failed=null;
                String raw=error instanceof SchreibAiWorkflowClient.WorkflowFailure failure?failure.response():"";
                String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                try { failed=StoryReviewStore.failure(task.project(),task.mode().toString(),task.instruction(),raw,message); }
                catch(IOException saveError) { message+=" Zwischenstand konnte nicht gespeichert werden: "+saveError.getMessage(); }
                var saved=failed; String display=message;
                Platform.runLater(()-> { if(saved!=null) showReview(saved); else detailsArea.setText(raw); statusLabel.setText("Auftrag fehlgeschlagen. "+display); finishTask(); });
            }
        });
    }

    private void finishTask() {
        setBusy(false);
        try { refreshSavedTasks(); } catch(IOException error) { status("Zwischenstandliste konnte nicht aktualisiert werden",error); }
    }

    private void openMemoryOnly() {
        if(busy) return;
        // This branch deliberately neither reads project editors nor calls persistLocalState.
        EphemeralTextDialog.open(workspaceField.getScene()==null?null:workspaceField.getScene().getWindow(),EphemeralTextClient.configured());
    }

    private void updatePrivacyMode() {
        boolean memoryOnly=memoryOnlyCheck.isSelected();
        savedTaskInputs.setDisable(memoryOnly);modeCombo.setDisable(memoryOnly);
        storageModeLabel.setText(memoryOnly
                ? "Nur anzeigen: Oeffnet eine leere Eingabe ohne Projektuebernahme. Eingabe und Antwort werden nicht in Projekt, Zwischenstaenden oder Chat-Historie gespeichert."
                : "Mit Speicherung: Dieser Auftrag und seine Antworten werden als Projekt-Zwischenstand gespeichert. Fuer Dolphin ist alternativ die getrennte Anzeige ohne Speicherung verfuegbar.");
        runTaskButton.setText(memoryOnly?"Eingabe ohne Speicherung oeffnen":"Auftrag ausfuehren (mit Speicherung)");
        updateButtons();
    }

    private void showReview(StoryReviewStore.Review value) {
        review=value; resultLabel.setText(value.summary); detailsArea.setText(value.raw);
        storyModelReport.setText(ModelRouting.summary(value.raw));
        proposalList.getItems().setAll(value.pending());
        if(!proposalList.getItems().isEmpty()) proposalList.getSelectionModel().selectFirst(); else showProposal(null);
        updateButtons();
    }

    private void clearReview() { review=null; proposalList.getItems().clear(); showProposal(null); resultLabel.setText("Noch kein Auftragsergebnis"); detailsArea.clear(); storyModelReport.setText(""); }

    private void showProposal(StoryReviewStore.Change change) {
        changingProposal=true;
        proposalPathLabel.setText(change==null?"Kein Vorschlag ausgewaehlt":change.path
                +(change.writeIntent!=null && !change.applied?" – Unterbrochene Uebernahme: zum Abschliessen erneut uebernehmen.":""));
        originalArea.setText(change==null?"":change.original); proposedArea.setText(change==null?"":change.content);
        changingProposal=false; updateButtons();
        // Proposal selection never changes the source editor path or content.
    }

    private void apply(boolean all) {
        if(busy || review==null || review.failed || !persistLocalState()) return;
        var selected=proposalList.getSelectionModel().getSelectedItem();
        if(!all && selected==null) return;
        var active=review;
        List<StoryReviewStore.Change> changes=all?List.copyOf(active.pending()):List.of(selected);
        int accepted=0; List<String> failures=new ArrayList<>();
        setBusy(true);
        for(var change:changes) {
            try { StoryReviewStore.apply(active,change); accepted++; if(active.project.equals(project)) drafts().remove(change.path); }
            catch(IOException error) { failures.add(error.getMessage()); }
        }
        try {
            if(active.complete()) StoryReviewStore.archiveCompleted(active);
            if(active.complete() && reviewActivities.containsKey(active.file)) {
                reviewActivities.get(active.file).done("Alle Vorschlaege uebernommen");
            }
            saveDrafts(); showReview(active); refreshFiles(); refreshSavedTasks();
            if(accepted>0 && modeCombo.getValue()==Mode.WRITE && Files.exists(StoryWorkspace.resolve(project,targetPathField.getText()))) {
                targetPathField.setText(StoryWorkspace.freshSceneTarget(project));
            }
        } catch(IOException error) { failures.add(error.getMessage()); }
        setBusy(false);
        statusLabel.setText(accepted+" Vorschlag/Vorschlaege uebernommen."+(failures.isEmpty()?"": " Nicht abgeschlossen und weiter zur Pruefung verfuegbar: "+String.join(" | ",failures)));
    }

    private void recoverTask() {
        if(busy || project==null || !persistLocalState()) return;
        var selected=savedTaskCombo.getValue(); if(selected==null) return;
        try { showReview(StoryReviewStore.open(project,selected.file())); statusLabel.setText("Genau dieser Zwischenstand wurde geladen. Andere Auftraege bleiben getrennt erhalten."); }
        catch(IOException|RuntimeException error) { status("Zwischenstand konnte nicht geladen werden",error); }
    }

    private void refreshSavedTasks() throws IOException {
        var tasks=StoryReviewStore.list(project); savedTaskCombo.getItems().setAll(tasks);
        if(!tasks.isEmpty()) savedTaskCombo.getSelectionModel().selectFirst();
        updateButtons();
    }

    private Map<String,Draft> drafts() { return draftsByProject.computeIfAbsent(project,unused->new LinkedHashMap<>()); }
    private void updateDirtyLabel() { sourceDirtyLabel.setText(project!=null && !drafts().isEmpty()?"Ungespeicherte Editorentwuerfe werden als Auftragkontext mitgegeben; Quelldateien bleiben bis zum Speichern unveraendert.":"Datei-Editor: Aenderungen werden erst mit Speichern in die Quelle geschrieben."); }

    private void saveDrafts() throws IOException {
        if(project==null) return;
        Map<String,Object> rows=new LinkedHashMap<>();
        drafts().forEach((path,draft)->rows.put(path,Map.of("original",draft.original(),"content",draft.content())));
        Path file=StoryWorkspace.resolve(project,".schreib-ai-temp/editor-drafts.json");
        if(rows.isEmpty() && !Files.exists(file)) return;
        Files.createDirectories(file.getParent()); StoryWorkspace.atomicWrite(file,StoryReviewStore.json(rows));
    }

    private void loadDrafts() throws IOException {
        if(draftsByProject.containsKey(project)) return;
        Map<String,Draft> loaded=new LinkedHashMap<>();
        Path file=StoryWorkspace.resolve(project,".schreib-ai-temp/editor-drafts.json");
        if(Files.isRegularFile(file)) {
            try {
                var rows=SimpleJson.asObject(SimpleJson.parse(Files.readString(file,StandardCharsets.UTF_8)));
                for(var entry:rows.entrySet()) {
                    StoryWorkspace.resolve(project,entry.getKey()); var row=SimpleJson.asObject(entry.getValue());
                    loaded.put(entry.getKey(),new Draft(SimpleJson.asString(row.get("original")),SimpleJson.asString(row.get("content"))));
                }
            } catch(RuntimeException error) { throw new IOException("Gespeicherte Editorentwuerfe sind nicht lesbar.",error); }
        }
        draftsByProject.put(project,loaded);
    }

    private boolean persistLocalState() {
        try { saveDrafts(); if(review!=null && Files.exists(review.file)) StoryReviewStore.save(review); return true; }
        catch(IOException error) { status("Zwischenstand konnte nicht gesichert werden",error); return false; }
    }

    public boolean hasOpenStoryTempFiles() {
        if(busy) return true;
        persistLocalState();
        if(draftsByProject.values().stream().anyMatch(map->!map.isEmpty())) return true;
        try {
            Path workspace=Path.of(workspaceField.getText());
            if(!Files.isDirectory(workspace)) return false;
            try(var stream=Files.list(workspace)) {
                for(Path dir:stream.filter(Files::isDirectory).toList()) if(!StoryReviewStore.list(dir).isEmpty()) return true;
            }
        } catch(IOException ignored) { return true; }
        return false;
    }

    private void loadLanguage() throws IOException {
        modelPicker.usePreference("story."+StoryWorkspace.hash(project.toAbsolutePath().normalize().toString()));
        Properties properties=new Properties(); Path path=StoryWorkspace.resolve(project,".schreib-ai-project.properties");
        if(Files.isRegularFile(path)) try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) { properties.load(reader); }
        changingProject=true; languageCombo.setValue("Deutsch".equals(properties.getProperty("outputLanguage"))?"Deutsch":"Englisch"); changingProject=false;
    }
    private void saveLanguage() {
        try { StoryWorkspace.atomicWrite(StoryWorkspace.resolve(project,".schreib-ai-project.properties"),"outputLanguage="+languageCombo.getValue()+"\n"); }
        catch(IOException error) { status("Sprachauswahl konnte nicht gespeichert werden",error); }
    }

    private void setBusy(boolean value) { busy=value; modelPicker.setLocked(value); projectPane.setDisable(value); taskPane.setDisable(value); reviewPane.setDisable(value); updateButtons(); }
    private void updateButtons() {
        runTaskButton.setDisable(busy || (!memoryOnlyCheck.isSelected() && (project==null || client==null || !client.isConfigured())));
        openMemoryOnlyButton.setDisable(busy);
        saveSourceButton.setDisable(busy || editorPath==null); discardSourceButton.setDisable(busy || editorPath==null);
        applyOneButton.setDisable(busy || review==null || review.failed || proposalList.getSelectionModel().getSelectedItem()==null);
        applyAllButton.setDisable(busy || review==null || review.failed || review.pending().isEmpty());
        recoverTaskButton.setDisable(busy || savedTaskCombo.getItems().isEmpty());
        var selected=proposalList.getSelectionModel().getSelectedItem();
        proposedArea.setEditable(!busy && selected!=null && selected.writeIntent==null);
    }
    private void status(String prefix, Exception error) { statusLabel.setText(prefix+": "+error.getMessage()); }
}

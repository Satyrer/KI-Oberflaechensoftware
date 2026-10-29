package kioberflaeche.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import kioberflaeche.admin.N8nWebChatClient;
import kioberflaeche.admin.WebChatSummary;
import kioberflaeche.ai.AiClient;
import kioberflaeche.ai.ModelCatalogClient;
import kioberflaeche.ai.ModelPreferences;
import kioberflaeche.ui.ModelPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import kioberflaeche.ai.EphemeralTextClient;
import kioberflaeche.ui.EphemeralTextDialog;
import kioberflaeche.ai.N8nChatWebhookClient;
import kioberflaeche.model.ChatMessage;
import kioberflaeche.queue.ActivityRegistry;
import kioberflaeche.story.SchreibAiWorkflowClient;
import kioberflaeche.storage.ChatSession;
import kioberflaeche.storage.ChatStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class ChatController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private final AiClient aiClient;
    private final ChatStore chatStore;
    private final N8nWebChatClient webChatClient;
    private final SchreibAiWorkflowClient schreibAiClient;
    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private final ObservableList<WebChatSummary> webChats = FXCollections.observableArrayList();

    @FXML
    private ListView<ChatSession> chatListView;
    @FXML
    private VBox messagesBox;
    @FXML
    private ScrollPane messagesScrollPane;
    @FXML
    private TextArea inputArea;
    @FXML
    private Button sendButton;
    @FXML
    private Label titleLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private ListView<WebChatSummary> webChatListView;
    @FXML
    private Label webPreviewLabel;
    @FXML
    private Button refreshWebChatsButton;
    @FXML
    private Button importWebChatButton;
    @FXML
    private Button deleteWebChatButton;
    @FXML
    private Label webStatusLabel;
    @FXML
    private StoryPaneController storyTabController;
    @FXML private AgentPaneController agentTabController;
    @FXML private WorkQueueController workQueueController;
    @FXML private javafx.scene.control.TabPane mainTabs;
    @FXML private javafx.scene.control.Tab workQueueTab;
    @FXML private Label workQueueSummary;
    @FXML private ComboBox<ModelCatalogClient.Option> chatModelCombo;
    @FXML private ComboBox<ChatTask> chatTaskCombo;
    @FXML private Button refreshChatModelsButton;
    @FXML private Label chatModelHint;
    @FXML private CheckBox chatMemoryOnlyCheck;
    @FXML private Label chatStorageModeLabel;
    @FXML private Button openChatMemoryOnlyButton;
    private ModelPicker modelPicker;
    private boolean sending;
    public enum ChatTask {
        AUTO("auto","Automatisch erkennen"), CHAT("chat","Gespraech"), CODING("coding","Programmierung / Fehlersuche"), GUARD("guard_test","Guard-Untersuchung");
        final String id,label;
        ChatTask(String id,String label) { this.id=id; this.label=label; }
        @Override public String toString() { return label; }
    }
    private ChatSession activeSession;

    public ChatController(
            AiClient aiClient,
            ChatStore chatStore,
            N8nWebChatClient webChatClient,
            SchreibAiWorkflowClient schreibAiClient
    ) {
        this.aiClient = aiClient;
        this.chatStore = chatStore;
        this.webChatClient = webChatClient;
        this.schreibAiClient = schreibAiClient;
    }

    @FXML
    private void initialize() {
        chatTaskCombo.getItems().setAll(ChatTask.values()); chatTaskCombo.setValue(ChatTask.AUTO);
        modelPicker=new ModelPicker(chatModelCombo,chatModelHint,refreshChatModelsButton,()->chatTaskCombo.getValue().id);
        chatModelCombo.valueProperty().addListener((o,a,b)-> {
            boolean dolphin=b!=null && EphemeralTextClient.MODEL_ID.equals(b.id());
            if(a==null || b==null || !a.id().equals(b.id()))chatMemoryOnlyCheck.setSelected(dolphin);
            chatMemoryOnlyCheck.setDisable(!dolphin);updatePrivacyMode();
        });
        chatMemoryOnlyCheck.selectedProperty().addListener((o,a,b)->updatePrivacyMode());
        openChatMemoryOnlyButton.setOnAction(e->openMemoryOnly());
        chatMemoryOnlyCheck.setDisable(true);updatePrivacyMode();
        try { String saved=ModelPreferences.get("chat.task","auto");
            for(var task:ChatTask.values()) if(task.id.equals(saved)) chatTaskCombo.setValue(task);
        } catch(IOException error) { statusLabel.setText("Gespeicherte Aufgabenwahl konnte nicht gelesen werden."); }
        modelPicker.usePreference("chat.model");
        chatTaskCombo.setTooltip(new javafx.scene.control.Tooltip("Die Aufgabe steuert die automatische Modellwahl. Bestehende Guard-Regeln werden dadurch nicht veraendert."));
        chatTaskCombo.valueProperty().addListener((o,a,b)-> {
            modelPicker.refilter();
            try { ModelPreferences.put("chat.task",b.id); } catch(IOException error) { statusLabel.setText("Aufgabenwahl konnte nicht dauerhaft gespeichert werden."); }
        });
        modelPicker.configure(ModelCatalogClient.workflow(schreibAiClient.baseUrl()));
        chatListView.setItems(sessions);
        chatListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(ChatSession session, boolean empty) {
                super.updateItem(session, empty);
                setText(empty || session == null ? null : session.title());
                setContextMenu(empty || session == null ? null : createLocalChatContextMenu(session));
                setOnMousePressed(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.SECONDARY) {
                        chatListView.getSelectionModel().select(getItem());
                    }
                });
            }
        });
        chatListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSession, newSession) -> {
            if (newSession != null) {
                activeSession = newSession;
                renderMessages();
            }
        });

        webChatListView.setItems(webChats);
        webChatListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(WebChatSummary chat, boolean empty) {
                super.updateItem(chat, empty);
                setText(empty || chat == null ? null : chat.displayTitle());
            }
        });
        webChatListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, chat) -> {
            boolean selected = chat != null;
            importWebChatButton.setDisable(!selected);
            deleteWebChatButton.setDisable(!selected);
            webPreviewLabel.setText(selected ? chat.preview() : "Kein WebUI-Chat ausgewaehlt.");
        });
        boolean webConfigured = webChatClient.isConfigured();
        refreshWebChatsButton.setDisable(!webConfigured);
        webStatusLabel.setText(webConfigured ? "n8n-WebUI-Import bereit" : "n8n-WebUI-Zugangsdaten fehlen");

        storyTabController.configure(schreibAiClient);
        agentTabController.configure(schreibAiClient.baseUrl());
        workQueueSummary.textProperty().bind(workQueueController.summaryProperty());
        workQueueController.startMonitoring();

        updateMainChatStatus();

        loadSessions();
    }

    @FXML
    private void showWorkQueue() { mainTabs.getSelectionModel().select(workQueueTab); }

    @FXML
    private void startNewChat() {
        ChatSession session = chatStore.newSession();
        sessions.add(0, session);
        chatListView.getSelectionModel().select(session);
        activeSession = session;
        renderMessages();
        saveSession(session);
    }

    @FXML
    private void sendMessage() {
        if(sending) return;
        if(chatMemoryOnlyCheck.isSelected()) { openMemoryOnly();return; }
        String selection;
        try { selection=modelPicker.selection(); } catch(IOException error) { statusLabel.setText(error.getMessage()); return; }
        if (activeSession == null) {
            startNewChat();
        }

        String userText = inputArea.getText().trim();
        if (userText.isBlank()) {
            return;
        }

        ChatSession targetSession = activeSession;
        List<ChatMessage> historyBeforeAnswer = AiClient.recentHistory(targetSession.messages());
        inputArea.clear();
        targetSession.add(ChatMessage.Sender.USER, userText);
        chatListView.refresh();
        renderMessages();
        saveSession(targetSession);
        setSendingState(true);

        var activity = ActivityRegistry.shared().start("Chat", targetSession.title(), "KI-Antwort ausstehend");
        aiClient.askWithModel(targetSession.id(), historyBeforeAnswer, userText, selection,chatTaskCombo.getValue().id)
                .thenAccept(answer -> Platform.runLater(() -> {
                    activity.done("Antwort empfangen");
                    if (!sessions.contains(targetSession)) {
                        setSendingState(false); return;
                    }
                    targetSession.add(ChatMessage.Sender.ASSISTANT, answer.text());
                    targetSession.add(ChatMessage.Sender.SYSTEM, answer.modelReport());
                    saveSession(targetSession);
                    chatListView.refresh();
                    if (targetSession == activeSession) {
                        renderMessages();
                    }
                    setSendingState(false);
                }))
                .exceptionally(error -> {
                    activity.failed("KI-Antwort fehlgeschlagen; Details im Chat");
                    Platform.runLater(() -> {
                        if (!sessions.contains(targetSession)) {
                            setSendingState(false); return;
                        }
                        targetSession.add(ChatMessage.Sender.SYSTEM, "Fehler bei der KI-Anbindung: " + error.getMessage());
                        saveSession(targetSession);
                        if (targetSession == activeSession) {
                            renderMessages();
                        }
                        setSendingState(false);
                    });
                    return null;
                });
    }

    @FXML
    private void handleInputKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
            event.consume();
            sendMessage();
        }
    }

    @FXML
    private void refreshWebChats() {
        if (!webChatClient.isConfigured()) {
            webStatusLabel.setText("n8n-WebUI-Zugangsdaten fehlen");
            return;
        }

        setWebActionsDisabled(true);
        webStatusLabel.setText("Lade n8n-WebUI-Chats...");
        var activity = ActivityRegistry.shared().start("Chat-Import", "WebUI-Chats laden", "Liste wird angefordert");
        Thread.startVirtualThread(() -> {
            try {
                List<WebChatSummary> chats = webChatClient.listChats();
                activity.done(chats.size() + " Chats gefunden");
                Platform.runLater(() -> {
                    webChats.setAll(chats);
                    webStatusLabel.setText(chats.size() + " WebUI-Chats gefunden");
                    setWebActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                activity.failed("Chatliste konnte nicht geladen werden");
                Platform.runLater(() -> {
                    webStatusLabel.setText("n8n-WebUI-Chats konnten nicht geladen werden");
                    setWebActionsDisabled(false);
                    showError("n8n-WebUI-Chats konnten nicht geladen werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void importWebChat() {
        WebChatSummary chat = webChatListView.getSelectionModel().getSelectedItem();
        if (chat == null) {
            return;
        }

        setWebActionsDisabled(true);
        webStatusLabel.setText("Importiere WebUI-Chat " + chat.id() + "...");
        var activity = ActivityRegistry.shared().start("Chat-Import", "Chat " + chat.id(), "Import laeuft");
        Thread.startVirtualThread(() -> {
            try {
                N8nWebChatClient.ImportedWebChat imported = webChatClient.importChat(chat);
                Path folder = chatStore.saveInFolder(imported.session(), imported.files());
                activity.done("Chat lokal gespeichert");
                Platform.runLater(() -> {
                    sessions.add(0, imported.session());
                    chatListView.getSelectionModel().select(imported.session());
                    webStatusLabel.setText("WebUI-Chat lokal importiert: " + folder);
                    setWebActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    webStatusLabel.setText("Import fehlgeschlagen");
                    activity.failed("Import fehlgeschlagen");
                    setWebActionsDisabled(false);
                    showError("n8n-WebUI-Chat konnte nicht importiert werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void deleteWebChat() {
        WebChatSummary chat = webChatListView.getSelectionModel().getSelectedItem();
        if (chat == null || !confirmFinalWebChatDelete(chat)) {
            return;
        }

        setWebActionsDisabled(true);
        webStatusLabel.setText("Loesche WebUI-Chat " + chat.id() + " final...");
        var activity = ActivityRegistry.shared().start("Chat-Verwaltung", "Chat " + chat.id(), "Loeschung angefordert");
        Thread.startVirtualThread(() -> {
            try {
                webChatClient.deleteChat(chat.id());
                activity.done("Loeschung bestaetigt");
                Platform.runLater(() -> {
                    webChats.remove(chat);
                    webStatusLabel.setText("Ausgewaehlter WebUI-Chat wurde final geloescht");
                    setWebActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    webStatusLabel.setText("Finales Loeschen fehlgeschlagen");
                    activity.failed("Loeschung fehlgeschlagen");
                    setWebActionsDisabled(false);
                    showError("n8n-WebUI-Chat konnte nicht geloescht werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void loadSessions() {
        try {
            sessions.setAll(chatStore.loadAll());
            if (sessions.isEmpty()) {
                startNewChat();
            } else {
                chatListView.getSelectionModel().selectFirst();
            }
            statusLabel.setText("Bereit");
        } catch (IOException | IllegalStateException e) {
            showError("Chats konnten nicht geladen werden", e);
            startNewChat();
        }
    }

    private ContextMenu createLocalChatContextMenu(ChatSession session) {
        MenuItem exportItem = new MenuItem("Chat exportieren");
        exportItem.setOnAction(event -> openExportWindow(session));

        MenuItem deleteItem = new MenuItem("Chat loeschen");
        deleteItem.setOnAction(event -> confirmAndDeleteChat(session));

        return new ContextMenu(exportItem, deleteItem);
    }

    private boolean confirmFinalWebChatDelete(WebChatSummary chat) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(ownerWindow());
        alert.setTitle("WebUI-Chat final loeschen");
        alert.setHeaderText("Ausgewaehlten n8n-WebUI-Chat wirklich final loeschen?");
        alert.setContentText("Geloescht wird nur dieser Chat:\n\n" + chat.displayTitle()
                + "\n\nDer lokale Import bleibt erhalten, falls du ihn vorher erstellt hast.");

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void confirmAndDeleteChat(ChatSession session) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(ownerWindow());
        alert.setTitle("Chat loeschen");
        alert.setHeaderText("Chat wirklich loeschen?");
        alert.setContentText("Der Chat \"" + session.title() + "\" wird lokal aus dem Speicherordner entfernt.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        try {
            chatStore.delete(session);
            sessions.remove(session);
            if (sessions.isEmpty()) {
                startNewChat();
            } else {
                chatListView.getSelectionModel().selectFirst();
            }
            statusLabel.setText("Chat geloescht.");
        } catch (IOException e) {
            showError("Chat konnte nicht geloescht werden", e);
        }
    }

    private void openExportWindow(ChatSession session) {
        Stage stage = new Stage();
        stage.setTitle("Chat exportieren");
        Window owner = ownerWindow();
        if (owner != null) {
            stage.initOwner(owner);
        }

        Label title = new Label("Chat exportieren");
        title.getStyleClass().add("dialog-title");
        Label description = new Label("Waehle einen Zielordner fuer \"" + session.title() + "\".");
        description.setWrapText(true);

        TextField targetField = new TextField();
        targetField.setPromptText("Zielordner auswaehlen");
        Button chooseButton = new Button("Ordner waehlen");
        Button exportButton = new Button("Exportieren");
        Button cancelButton = new Button("Abbrechen");
        exportButton.getStyleClass().add("send-button");

        chooseButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Zielordner fuer Chat-Export waehlen");
            File selectedDirectory = chooser.showDialog(stage);
            if (selectedDirectory != null) {
                targetField.setText(selectedDirectory.getAbsolutePath());
            }
        });
        cancelButton.setOnAction(event -> stage.close());
        exportButton.setOnAction(event -> exportChatToSelectedDirectory(session, targetField, stage));

        HBox pathRow = new HBox(8, targetField, chooseButton);
        targetField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(targetField, javafx.scene.layout.Priority.ALWAYS);
        HBox actions = new HBox(8, exportButton, cancelButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, title, description, pathRow, actions);
        root.getStyleClass().add("dialog-pane");
        Scene scene = new Scene(root, 520, 190);
        scene.getStylesheets().add(getClass().getResource("/css/chat.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(190);
        stage.show();
    }

    private void exportChatToSelectedDirectory(ChatSession session, TextField targetField, Stage stage) {
        String targetPath = targetField.getText() == null ? "" : targetField.getText().trim();
        if (targetPath.isBlank()) {
            statusLabel.setText("Bitte zuerst einen Zielordner waehlen.");
            return;
        }

        try {
            Path exportedPath = chatStore.export(session, Path.of(targetPath));
            statusLabel.setText("Chat exportiert: " + exportedPath);
            stage.close();
        } catch (IOException e) {
            showError("Chat konnte nicht exportiert werden", e);
        }
    }

    private void renderMessages() {
        messagesBox.getChildren().clear();
        titleLabel.setText(activeSession == null ? "KI Chat" : activeSession.title());

        if (activeSession == null) {
            return;
        }

        for (ChatMessage message : activeSession.messages()) {
            messagesBox.getChildren().add(createMessageRow(message));
        }

        Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
    }

    private HBox createMessageRow(ChatMessage message) {
        boolean fromUser = message.sender() == ChatMessage.Sender.USER;
        boolean system = message.sender() == ChatMessage.Sender.SYSTEM;

        Label textLabel = new Label(message.text());
        textLabel.setWrapText(true);
        textLabel.getStyleClass().add("message-text");

        Label timeLabel = new Label(TIME_FORMAT.format(message.timestamp()));
        timeLabel.getStyleClass().add("message-time");

        VBox bubble = new VBox(4, textLabel, timeLabel);
        bubble.getStyleClass().add("message-bubble");
        bubble.getStyleClass().add(system ? "system-bubble" : fromUser ? "user-bubble" : "assistant-bubble");
        bubble.maxWidthProperty().bind(messagesScrollPane.widthProperty().multiply(0.68));

        HBox row = new HBox(bubble);
        row.getStyleClass().add("message-row");
        row.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private void setSendingState(boolean sending) {
        this.sending=sending; modelPicker.setLocked(sending); chatTaskCombo.setDisable(sending);
        sendButton.setDisable(sending);
        updatePrivacyMode();
        if (sending) {
            statusLabel.setText("KI antwortet ueber " + mainChatEndpointLabel() + "...");
        } else {
            updateMainChatStatus();
        }
    }

    private void openMemoryOnly() {
        if(sending)return;
        // No new chat session, history copy, input transfer or store call on this branch.
        EphemeralTextDialog.open(inputArea.getScene()==null?null:inputArea.getScene().getWindow(),EphemeralTextClient.configured());
    }

    private void updatePrivacyMode() {
        boolean memoryOnly=chatMemoryOnlyCheck.isSelected();
        inputArea.setDisable(sending || memoryOnly);chatTaskCombo.setDisable(sending || memoryOnly);
        openChatMemoryOnlyButton.setDisable(sending);
        sendButton.setText(sending?"Warte...":memoryOnly?"Leere Anzeige oeffnen":"Senden (mit Speicherung)");
        chatStorageModeLabel.setText(memoryOnly
                ? "Nur anzeigen: Eine leere Eingabe ohne diesen Chatverlauf wird geoeffnet. Ein- und Ausgabe werden nicht im Chat gespeichert."
                : "Mit Speicherung: Nachrichten und Antworten bleiben in der Chat-Historie. Dolphin kann alternativ nur in der getrennten Anzeige verwendet werden.");
    }

    private void updateMainChatStatus() {
        statusLabel.setText("Bereit: " + mainChatEndpointLabel());
    }

    private String mainChatEndpointLabel() {
        if (aiClient instanceof N8nChatWebhookClient n8nClient && !n8nClient.endpoint().isBlank()) {
            return n8nClient.endpoint();
        }
        return "lokaler KI-Endpunkt";
    }

    private void setWebActionsDisabled(boolean disabled) {
        refreshWebChatsButton.setDisable(disabled || !webChatClient.isConfigured());
        WebChatSummary selected = webChatListView.getSelectionModel().getSelectedItem();
        importWebChatButton.setDisable(disabled || selected == null);
        deleteWebChatButton.setDisable(disabled || selected == null);
    }

    public boolean hasOpenStoryTempFiles() {
        return storyTabController != null && storyTabController.hasOpenStoryTempFiles();
    }

    private void saveSession(ChatSession session) {
        try {
            chatStore.save(session);
        } catch (IOException e) {
            showError("Chat konnte nicht gespeichert werden", e);
        }
    }

    private void showError(String title, Exception exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(ownerWindow());
        alert.setTitle("Fehler");
        alert.setHeaderText(title);
        alert.setContentText(exception.getMessage());
        alert.showAndWait();
    }

    private Window ownerWindow() {
        if (chatListView != null && chatListView.getScene() != null) {
            return chatListView.getScene().getWindow();
        }
        return null;
    }
}


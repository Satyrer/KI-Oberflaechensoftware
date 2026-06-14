package kioberflaeche.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
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
import kioberflaeche.ai.N8nChatWebhookClient;
import kioberflaeche.model.ChatMessage;
import kioberflaeche.story.SchreibAiWorkflowClient;
import kioberflaeche.story.StoryFile;
import kioberflaeche.story.StoryProject;
import kioberflaeche.storage.ChatSession;
import kioberflaeche.storage.ChatStore;
import kioberflaeche.util.SimpleJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public class ChatController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DEFAULT_STORY_WORKSPACE = "F:\\Virtuelle Maschinenen\\SchreibAI\\Arbeitsprojekte";
    private static final String STORY_SYNTHESIS_STATE_FILE = ".schreib-ai-synthesis.properties";
    private static final String STORY_SYNTHESIS_SNAPSHOT_DIR = ".schreib-ai-snapshots";
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private final AiClient aiClient;
    private final ChatStore chatStore;
    private final N8nWebChatClient webChatClient;
    private final SchreibAiWorkflowClient schreibAiClient;
    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private final ObservableList<WebChatSummary> webChats = FXCollections.observableArrayList();
    private final ObservableList<StoryProject> storyProjects = FXCollections.observableArrayList();
    private final ObservableList<StoryFile> storyFiles = FXCollections.observableArrayList();
    private final ObservableList<StoryChange> storyChanges = FXCollections.observableArrayList();

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
    @FXML
    private ListView<StoryFile> storyFileListView;
    @FXML
    private TextField storyWorkspaceField;
    @FXML
    private ComboBox<StoryProject> storyProjectComboBox;
    @FXML
    private ComboBox<String> storyOutputLanguageComboBox;
    @FXML
    private TextField storyPathField;
    @FXML
    private TextArea storyFileContentArea;
    @FXML
    private TextField storyTitleField;
    @FXML
    private TextField storyGenreField;
    @FXML
    private TextField storyProtagonistField;
    @FXML
    private TextField storySettingField;
    @FXML
    private TextField storyToneField;
    @FXML
    private TextArea storyPremiseArea;
    @FXML
    private TextArea storyConflictArea;
    @FXML
    private TextField storyChapterField;
    @FXML
    private TextField storyTimelineField;
    @FXML
    private TextArea storyDocumentArea;
    @FXML
    private TextField storyTargetPathField;
    @FXML
    private TextField storyStepsField;
    @FXML
    private TextArea storyContinuePromptArea;
    @FXML
    private TextArea storyWorkflowResultArea;
    @FXML
    private ListView<StoryChange> storyChangeListView;
    @FXML
    private TextArea storyOriginalContentArea;
    @FXML
    private TextArea storyProposedContentArea;
    @FXML
    private Label storyStatusLabel;
    @FXML
    private Button refreshStoryFilesButton;
    @FXML
    private Button refreshStoryProjectsButton;
    @FXML
    private Button chooseStoryWorkspaceButton;
    @FXML
    private Button saveStoryFileButton;
    @FXML
    private Button continueFromStoryFileButton;
    @FXML
    private Button promoteToFinalStoryButton;
    @FXML
    private Button reviewFinalChapterButton;
    @FXML
    private Button createStoryButton;
    @FXML
    private Button synthesizeStoryButton;
    @FXML
    private Button syncQdrantMemoryButton;
    @FXML
    private Button recoverStoryTempButton;
    @FXML
    private Button analyzeStoryDocumentButton;
    @FXML
    private Button continueStoryButton;
    @FXML
    private Button applyStoryChangeButton;
    @FXML
    private Button applyAllStoryChangesButton;

    private ChatSession activeSession;

    private record StoryChange(String action, String path, String content) {
        String displayTitle() {
            return (action == null || action.isBlank() ? "upsert" : action) + " " + path;
        }

        @Override
        public String toString() {
            return displayTitle();
        }
    }

    private record SynthesisSelection(
            List<SchreibAiWorkflowClient.StoryDocument> originalDocuments,
            List<SchreibAiWorkflowClient.StoryDocument> documentsToProcess,
            Properties state
    ) {
    }

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

        bindStoryPaneFields();
        wireStoryActions();
        storyWorkspaceField.setText(DEFAULT_STORY_WORKSPACE);
        storyProjectComboBox.setItems(storyProjects);
        storyProjectComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldProject, newProject) -> {
            storyFiles.clear();
            storyFileContentArea.clear();
            storyWorkflowResultArea.clear();
            storyChanges.clear();
            storyOriginalContentArea.clear();
            storyProposedContentArea.clear();
            if (newProject != null && schreibAiClient.isConfigured()) {
                refreshStoryFiles();
            } else {
                updateStoryTempStatus();
            }
        });
        storyOutputLanguageComboBox.setItems(FXCollections.observableArrayList(
                "Englisch",
                "Deutsch"
        ));
        storyOutputLanguageComboBox.getSelectionModel().selectFirst();

        storyFileListView.setItems(storyFiles);
        storyFileListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(StoryFile file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.displayTitle());
            }
        });
        storyFileListView.getSelectionModel().selectedItemProperty().addListener((observable, oldFile, newFile) -> {
            if (newFile != null) {
                loadStoryFile(newFile);
            }
        });
        storyChangeListView.setItems(storyChanges);
        storyChangeListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(StoryChange change, boolean empty) {
                super.updateItem(change, empty);
                setText(empty || change == null ? null : change.displayTitle());
            }
        });
        storyChangeListView.getSelectionModel().selectedItemProperty().addListener((observable, oldChange, newChange) -> {
            if (oldChange != null && oldChange != newChange) {
                replaceStoryChangeContent(oldChange, storyProposedContentArea.getText());
            }
            boolean selected = newChange != null;
            applyStoryChangeButton.setDisable(!selected);
            if (selected) {
                showStoryChangeComparison(newChange);
            } else {
                storyOriginalContentArea.clear();
                storyProposedContentArea.clear();
            }
        });
        boolean storyConfigured = schreibAiClient.isConfigured();
        setStoryActionsDisabled(!storyConfigured);
        storyStatusLabel.setText(storyConfigured
                ? "Schreib-AI Workflow bereit: " + schreibAiClient.baseUrl()
                : "Schreib-AI Workflow-URL fehlt");

        updateMainChatStatus();

        loadSessions();
        if (storyConfigured) {
            refreshStoryProjects();
        }
    }

    @SuppressWarnings("unchecked")
    private void bindStoryPaneFields() {
        storyFileListView = storyTabController.storyFileListView;
        storyWorkspaceField = storyTabController.storyWorkspaceField;
        storyProjectComboBox = storyTabController.storyProjectComboBox;
        storyOutputLanguageComboBox = storyTabController.storyOutputLanguageComboBox;
        storyPathField = storyTabController.storyPathField;
        storyFileContentArea = storyTabController.storyFileContentArea;
        storyTitleField = storyTabController.storyTitleField;
        storyGenreField = storyTabController.storyGenreField;
        storyProtagonistField = storyTabController.storyProtagonistField;
        storySettingField = storyTabController.storySettingField;
        storyToneField = storyTabController.storyToneField;
        storyPremiseArea = storyTabController.storyPremiseArea;
        storyConflictArea = storyTabController.storyConflictArea;
        storyChapterField = storyTabController.storyChapterField;
        storyTimelineField = storyTabController.storyTimelineField;
        storyDocumentArea = storyTabController.storyDocumentArea;
        storyTargetPathField = storyTabController.storyTargetPathField;
        storyStepsField = storyTabController.storyStepsField;
        storyContinuePromptArea = storyTabController.storyContinuePromptArea;
        storyWorkflowResultArea = storyTabController.storyWorkflowResultArea;
        storyChangeListView = (ListView<StoryChange>) storyTabController.storyChangeListView;
        storyOriginalContentArea = storyTabController.storyOriginalContentArea;
        storyProposedContentArea = storyTabController.storyProposedContentArea;
        storyStatusLabel = storyTabController.storyStatusLabel;
        refreshStoryFilesButton = storyTabController.refreshStoryFilesButton;
        refreshStoryProjectsButton = storyTabController.refreshStoryProjectsButton;
        chooseStoryWorkspaceButton = storyTabController.chooseStoryWorkspaceButton;
        saveStoryFileButton = storyTabController.saveStoryFileButton;
        continueFromStoryFileButton = storyTabController.continueFromStoryFileButton;
        promoteToFinalStoryButton = storyTabController.promoteToFinalStoryButton;
        reviewFinalChapterButton = storyTabController.reviewFinalChapterButton;
        createStoryButton = storyTabController.createStoryButton;
        synthesizeStoryButton = storyTabController.synthesizeStoryButton;
        syncQdrantMemoryButton = storyTabController.syncQdrantMemoryButton;
        recoverStoryTempButton = storyTabController.recoverStoryTempButton;
        analyzeStoryDocumentButton = storyTabController.analyzeStoryDocumentButton;
        continueStoryButton = storyTabController.continueStoryButton;
        applyStoryChangeButton = storyTabController.applyStoryChangeButton;
        applyAllStoryChangesButton = storyTabController.applyAllStoryChangesButton;
    }

    private void wireStoryActions() {
        chooseStoryWorkspaceButton.setOnAction(event -> chooseStoryWorkspace());
        refreshStoryProjectsButton.setOnAction(event -> refreshStoryProjects());
        synthesizeStoryButton.setOnAction(event -> synthesizeStoryProject());
        refreshStoryFilesButton.setOnAction(event -> refreshStoryFiles());
        createStoryButton.setOnAction(event -> createStoryProject());
        syncQdrantMemoryButton.setOnAction(event -> syncQdrantMemory());
        recoverStoryTempButton.setOnAction(event -> recoverLatestStoryTemp());
        analyzeStoryDocumentButton.setOnAction(event -> analyzeStoryDocument());
        continueStoryButton.setOnAction(event -> continueStory());
        continueFromStoryFileButton.setOnAction(event -> continueStoryFromSelectedFile());
        promoteToFinalStoryButton.setOnAction(event -> finalizeSelectedStoryFile(false));
        reviewFinalChapterButton.setOnAction(event -> finalizeSelectedStoryFile(true));
        saveStoryFileButton.setOnAction(event -> saveStoryFile());
        applyStoryChangeButton.setOnAction(event -> applySelectedStoryChange());
        applyAllStoryChangesButton.setOnAction(event -> applyAllStoryChanges());
    }

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
        if (activeSession == null) {
            startNewChat();
        }

        String userText = inputArea.getText().trim();
        if (userText.isBlank()) {
            return;
        }

        ChatSession targetSession = activeSession;
        inputArea.clear();
        targetSession.add(ChatMessage.Sender.USER, userText);
        chatListView.refresh();
        renderMessages();
        saveSession(targetSession);
        setSendingState(true);

        List<ChatMessage> historyBeforeAnswer = targetSession.messages();
        aiClient.ask(targetSession.id(), historyBeforeAnswer, userText)
                .thenAccept(answer -> Platform.runLater(() -> {
                    if (!sessions.contains(targetSession)) {
                        return;
                    }
                    targetSession.add(ChatMessage.Sender.ASSISTANT, answer);
                    saveSession(targetSession);
                    chatListView.refresh();
                    if (targetSession == activeSession) {
                        renderMessages();
                        setSendingState(false);
                    }
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (!sessions.contains(targetSession)) {
                            return;
                        }
                        targetSession.add(ChatMessage.Sender.SYSTEM, "Fehler bei der KI-Anbindung: " + error.getMessage());
                        saveSession(targetSession);
                        if (targetSession == activeSession) {
                            renderMessages();
                            setSendingState(false);
                        }
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
        Thread.startVirtualThread(() -> {
            try {
                List<WebChatSummary> chats = webChatClient.listChats();
                Platform.runLater(() -> {
                    webChats.setAll(chats);
                    webStatusLabel.setText(chats.size() + " WebUI-Chats gefunden");
                    setWebActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
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
        Thread.startVirtualThread(() -> {
            try {
                N8nWebChatClient.ImportedWebChat imported = webChatClient.importChat(chat);
                Path folder = chatStore.saveInFolder(imported.session(), imported.files());
                Platform.runLater(() -> {
                    sessions.add(0, imported.session());
                    chatListView.getSelectionModel().select(imported.session());
                    webStatusLabel.setText("WebUI-Chat lokal importiert: " + folder);
                    setWebActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    webStatusLabel.setText("Import fehlgeschlagen");
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
        Thread.startVirtualThread(() -> {
            try {
                webChatClient.deleteChat(chat.id());
                Platform.runLater(() -> {
                    webChats.remove(chat);
                    webStatusLabel.setText("Ausgewaehlter WebUI-Chat wurde final geloescht");
                    setWebActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    webStatusLabel.setText("Finales Loeschen fehlgeschlagen");
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
        sendButton.setDisable(sending);
        inputArea.setDisable(sending);
        sendButton.setText(sending ? "Warte..." : "Senden");
        if (sending) {
            statusLabel.setText("KI antwortet ueber " + mainChatEndpointLabel() + "...");
        } else {
            updateMainChatStatus();
        }
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

    @FXML
    private void chooseStoryWorkspace() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("SchreibAI Arbeitsplatzordner waehlen");
        File current = new File(storyWorkspaceField.getText() == null ? "" : storyWorkspaceField.getText().trim());
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        Window window = storyWorkspaceField.getScene() == null ? null : storyWorkspaceField.getScene().getWindow();
        File selected = chooser.showDialog(window);
        if (selected == null) {
            return;
        }
        storyWorkspaceField.setText(selected.getAbsolutePath());
        refreshStoryProjects();
    }

    @FXML
    private void refreshStoryProjects() {
        storyProjectComboBox.getSelectionModel().clearSelection();
        storyProjects.clear();
        storyFiles.clear();
        File workspace = new File(storyWorkspaceField.getText() == null ? "" : storyWorkspaceField.getText().trim());
        File[] directories = workspace.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                storyProjects.add(new StoryProject(directory.getName(), directory.getName(), false));
            }
        }
        if (!storyProjects.isEmpty()) {
            storyProjectComboBox.getSelectionModel().selectFirst();
        } else {
            storyStatusLabel.setText("Keine Arbeitsprojekte gefunden: " + workspace.getAbsolutePath());
            setStoryActionsDisabled(false);
        }
        if (!storyProjects.isEmpty()) {
            storyStatusLabel.setText("Arbeitsplatz geladen: " + workspace.getAbsolutePath());
        }
    }

    @FXML
    private void refreshStoryFiles() {
        if (!schreibAiClient.isConfigured()) {
            storyStatusLabel.setText("Schreib-AI Workflow-URL fehlt");
            return;
        }
        String projectId = selectedStoryProjectId();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            return;
        }

        setStoryActionsDisabled(true);
        storyStatusLabel.setText("Lade Story-Schichten fuer " + projectId + "...");
        Thread.startVirtualThread(() -> {
            try {
                List<StoryFile> files = listLocalStoryFiles();
                Platform.runLater(() -> {
                    storyFiles.setAll(files);
                    storyStatusLabel.setText(files.size() + " Story-Dateien geladen: " + projectId + storyTempStatusSuffix());
                    setStoryActionsDisabled(false);
                    if (!files.isEmpty() && storyFileListView.getSelectionModel().isEmpty()) {
                        storyFileListView.getSelectionModel().selectFirst();
                    }
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Story-Schichten konnten nicht geladen werden");
                    setStoryActionsDisabled(false);
                    showError("Schreib-AI Story-Dateien konnten nicht geladen werden", e);
                });
            }
        });
    }

    @FXML
    private void saveStoryFile() {
        String path = storyPathField.getText() == null ? "" : storyPathField.getText().trim();
        if (path.isBlank()) {
            storyStatusLabel.setText("Bitte einen Story-Dateipfad angeben.");
            return;
        }

        setStoryActionsDisabled(true);
        storyStatusLabel.setText("Speichere " + path + "...");
        String projectId = selectedStoryProjectId();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                writeLocalStoryFile(path, storyFileContentArea.getText());
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText("{\"ok\":true,\"saved\":\"" + path + "\"}");
                    storyStatusLabel.setText("Story-Datei gespeichert");
                    setStoryActionsDisabled(false);
                    refreshStoryFiles();
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Speichern fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Story-Datei konnte nicht gespeichert werden", e);
                });
            }
        });
    }

    @FXML
    private void createStoryProject() {
        setStoryActionsDisabled(true);
        storyStatusLabel.setText("Lege Story-Schichten an...");
        String projectId = selectedStoryProjectId();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String result = bootstrapLocalStoryProject();
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText(result);
                    storyStatusLabel.setText("Story-Schichten angelegt");
                    setStoryActionsDisabled(false);
                    refreshStoryFiles();
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Story-Anlage fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Story-Schichten konnten nicht angelegt werden", e);
                });
            }
        });
    }

    @FXML
    private void synthesizeStoryProject() {
        setStoryActionsDisabled(true);
        String projectId = selectedStoryProjectId();
        String outputLanguage = selectedOutputLanguage();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        List<SchreibAiWorkflowClient.StoryDocument> documents;
        try {
            documents = collectLocalStoryDocuments(true);
        } catch (IOException e) {
            storyStatusLabel.setText("Projektdateien konnten nicht gelesen werden");
            setStoryActionsDisabled(false);
            showError("Projektdateien konnten nicht gelesen werden", e);
            return;
        }
        SynthesisSelection selection;
        try {
            selection = selectDocumentsForSynthesis(documents);
        } catch (IOException e) {
            storyStatusLabel.setText("Synthese-Status konnte nicht gelesen werden");
            setStoryActionsDisabled(false);
            showError("Synthese-Status konnte nicht gelesen werden", e);
            return;
        }
        if (selection.documentsToProcess().isEmpty()) {
            storyStatusLabel.setText("Keine neuen oder geaenderten Texte fuer die Synthese gefunden.");
            storyWorkflowResultArea.setText("{\"ok\":true,\"skipped\":\"all_sources_unchanged\"}");
            storyChanges.clear();
            setStoryActionsDisabled(false);
            return;
        }
        List<SchreibAiWorkflowClient.StoryDocument> documentsToProcess = selection.documentsToProcess();
        storyStatusLabel.setText("Synthetisiere Gesamtdokument fuer " + projectId + " aus " + documentsToProcess.size() + " Quelldatei(en)...");
        storyWorkflowResultArea.clear();
        storyChanges.clear();
        Thread.startVirtualThread(() -> {
            try {
                Platform.runLater(() -> storyStatusLabel.setText(
                        "KI verarbeitet Gesamtsynthese: " + documentsToProcess.stream()
                                .map(SchreibAiWorkflowClient.StoryDocument::path)
                                .findFirst()
                                .orElse(projectId)
                ));
                String result = schreibAiClient.synthesizeProject(projectId, outputLanguage, documentsToProcess);
                saveStoryTempResult("synthesize", 1, 1, result);
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText(result);
                    addStoryChangesFromResult(result);
                    storyStatusLabel.setText("Gesamtsynthese empfangen und zwischengespeichert.");
                });
                Platform.runLater(() -> {
                    try {
                        saveSynthesisState(selection.originalDocuments(), selection.state());
                    } catch (IOException e) {
                        storyStatusLabel.setText("Story synthetisiert, aber Synthese-Status konnte nicht gespeichert werden.");
                    }
                    storyStatusLabel.setText("Story-Schichten gesamthaft synthetisiert: " + projectId);
                    setStoryActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Synthese fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Entwurf konnte nicht synthetisiert werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void syncQdrantMemory() {
        setStoryActionsDisabled(true);
        String projectId = selectedStoryProjectId();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        List<SchreibAiWorkflowClient.StoryDocument> documents;
        try {
            documents = collectLocalStoryDocuments(true);
        } catch (IOException e) {
            storyStatusLabel.setText("Projektdateien konnten nicht gelesen werden");
            setStoryActionsDisabled(false);
            showError("Projektdateien konnten nicht gelesen werden", e);
            return;
        }
        storyStatusLabel.setText("Synchronisiere " + documents.size() + " Memory-Datei(en) nach Qdrant fuer " + projectId + "...");
        storyWorkflowResultArea.clear();
        Thread.startVirtualThread(() -> {
            try {
                String result = schreibAiClient.syncQdrantMemory(projectId, documents);
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText(result);
                    storyStatusLabel.setText("Qdrant-Memory synchronisiert: " + projectId);
                    setStoryActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Qdrant-Sync fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Memory konnte nicht in Qdrant gespeichert werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void analyzeStoryDocument() {
        setStoryActionsDisabled(true);
        storyStatusLabel.setText("Sende Dokument an Schreib-AI Workflow...");
        String projectId = selectedStoryProjectId();
        String outputLanguage = selectedOutputLanguage();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        List<SchreibAiWorkflowClient.StoryDocument> documents;
        try {
            documents = collectGenerationContextDocuments();
        } catch (IOException e) {
            storyStatusLabel.setText("Projektdateien konnten nicht gelesen werden");
            setStoryActionsDisabled(false);
            showError("Projektdateien konnten nicht gelesen werden", e);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String result = schreibAiClient.analyzeDocument(
                        projectId,
                        outputLanguage,
                        storyDocumentArea.getText(),
                        storyChapterField.getText(),
                        storyTimelineField.getText(),
                        documents
                );
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText(result);
                    loadStoryChangesFromResult(result);
                    storyStatusLabel.setText("Analyseplan erstellt");
                    setStoryActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Analyse fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Dokument konnte nicht analysiert werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void continueStory() {
        continueStoryWithPrompt(storyContinuePromptArea.getText());
    }

    @FXML
    private void continueStoryFromSelectedFile() {
        String sourcePath = storyPathField.getText() == null ? "" : storyPathField.getText().trim();
        String sourceContent = storyFileContentArea.getText() == null ? "" : storyFileContentArea.getText().trim();
        if (sourcePath.isBlank() || sourceContent.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst eine Story-Datei mit Inhalt auswaehlen.");
            return;
        }
        if (storyTargetPathField.getText() == null || storyTargetPathField.getText().trim().isBlank()) {
            storyTargetPathField.setText(sourcePath.startsWith("manuscript/") ? sourcePath : "manuscript/chapter-01.md");
        }
        String userPrompt = storyContinuePromptArea.getText() == null ? "" : storyContinuePromptArea.getText().trim();
        String prompt = (userPrompt.isBlank() ? "Fuehre die Geschichte auf Grundlage der ausgewaehlten Datei fort." : userPrompt)
                + "\n\nAusgewaehlte Datei als konkrete Arbeitsgrundlage: " + sourcePath
                + "\n\n" + sourceContent;
        continueStoryWithPrompt(prompt);
    }

    private void continueStoryWithPrompt(String prompt) {
        setStoryActionsDisabled(true);
        storyStatusLabel.setText("Plane Fortsetzung mit Schicht-Kontext...");
        String projectId = selectedStoryProjectId();
        String outputLanguage = selectedOutputLanguage();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        List<SchreibAiWorkflowClient.StoryDocument> documents;
        try {
            documents = collectGenerationContextDocuments();
        } catch (IOException e) {
            storyStatusLabel.setText("Projektdateien konnten nicht gelesen werden");
            setStoryActionsDisabled(false);
            showError("Projektdateien konnten nicht gelesen werden", e);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String result = schreibAiClient.continueStory(
                      projectId,
                      outputLanguage,
                      storyTargetPathField.getText(),
                      prompt,
                      storyStepsField.getText(),
                      documents
              );
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText(result);
                    loadStoryChangesFromResult(result);
                    storyStatusLabel.setText("Fortsetzungsplan erstellt");
                    setStoryActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Fortsetzung fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Geschichte konnte nicht fortgefuehrt werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void finalizeSelectedStoryFile(boolean reviewExistingChapter) {
        String sourcePath = storyPathField.getText() == null ? "" : storyPathField.getText().trim();
        String sourceContent = storyFileContentArea.getText() == null ? "" : storyFileContentArea.getText().trim();
        if (sourcePath.isBlank() || sourceContent.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst eine Manuskript- oder Chapter-Datei mit Inhalt auswaehlen.");
            return;
        }
        if (!sourcePath.startsWith("manuscript/") && !sourcePath.startsWith("chapters/")) {
            storyStatusLabel.setText("Finale Ueberfuehrung ist fuer manuscript/ oder chapters/ gedacht.");
            return;
        }
        setStoryActionsDisabled(true);
        storyStatusLabel.setText(reviewExistingChapter ? "Reviewe Chapter fuer finale Geschichte..." : "Fuehre Manuskript in finale Geschichte ueber...");
        String projectId = selectedStoryProjectId();
        String outputLanguage = selectedOutputLanguage();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        List<SchreibAiWorkflowClient.StoryDocument> documents;
        try {
            documents = collectFinalizationContextDocuments(sourcePath);
        } catch (IOException e) {
            storyStatusLabel.setText("Projektdateien konnten nicht gelesen werden");
            setStoryActionsDisabled(false);
            showError("Projektdateien konnten nicht gelesen werden", e);
            return;
        }
        String instruction = storyContinuePromptArea.getText() == null ? "" : storyContinuePromptArea.getText().trim();
        Thread.startVirtualThread(() -> {
            try {
                String result = schreibAiClient.finalizeChapter(
                        projectId,
                        outputLanguage,
                        sourcePath,
                        sourceContent,
                        instruction,
                        reviewExistingChapter,
                        documents
                );
                Platform.runLater(() -> {
                    storyWorkflowResultArea.setText(result);
                    loadStoryChangesFromResult(result);
                    storyStatusLabel.setText(reviewExistingChapter
                            ? "Finales Chapter-Review erstellt"
                            : "Finaler Story-Vorschlag erstellt");
                    setStoryActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Finale Ueberfuehrung fehlgeschlagen");
                    setStoryActionsDisabled(false);
                    showError("Finale Geschichte konnte nicht erstellt werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void applySelectedStoryChange() {
        StoryChange change = storyChangeListView.getSelectionModel().getSelectedItem();
        if (change == null) {
            storyStatusLabel.setText("Bitte zuerst eine Aenderung auswaehlen.");
            return;
        }
        StoryChange editedChange = new StoryChange(change.action(), change.path(), storyProposedContentArea.getText());
        if (applyStoryChange(editedChange)) {
            storyChanges.remove(change);
            afterStoryMergeAccepted(1);
        }
    }

    @FXML
    private void applyAllStoryChanges() {
        if (storyChanges.isEmpty()) {
            storyStatusLabel.setText("Keine Aenderungen zum Uebernehmen vorhanden.");
            return;
        }
        syncSelectedStoryChangeFromEditor();
        int accepted = 0;
        for (StoryChange change : List.copyOf(storyChanges)) {
            if (applyStoryChange(change)) {
                accepted++;
            }
        }
        if (accepted > 0) {
            storyChanges.clear();
            afterStoryMergeAccepted(accepted);
        }
    }

    private boolean applyStoryChange(StoryChange change) {
        try {
            writeLocalStoryFile(change.path(), normalizeProposedContent(change.content()));
            storyStatusLabel.setText("Aenderung uebernommen: " + change.path());
            return true;
        } catch (IOException e) {
            storyStatusLabel.setText("Aenderung konnte nicht uebernommen werden.");
            showError("Aenderung konnte nicht uebernommen werden", e);
            return false;
        }
    }

    private void syncSelectedStoryChangeFromEditor() {
        StoryChange selected = storyChangeListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            replaceStoryChangeContent(selected, storyProposedContentArea.getText());
        }
    }

    private void replaceStoryChangeContent(StoryChange change, String content) {
        int index = storyChanges.indexOf(change);
        if (index >= 0) {
            storyChanges.set(index, new StoryChange(change.action(), change.path(), content));
        }
    }

    private void afterStoryMergeAccepted(int accepted) {
        storyChangeListView.getSelectionModel().clearSelection();
        storyOriginalContentArea.clear();
        storyProposedContentArea.clear();
        applyStoryChangeButton.setDisable(true);
        applyAllStoryChangesButton.setDisable(storyChanges.isEmpty());
        if (storyChanges.isEmpty()) {
            deleteStoryTempFiles();
        }
        storyStatusLabel.setText(accepted + " Aenderung(en) uebernommen.");
        refreshStoryFiles();
    }

    private void loadStoryChangesFromResult(String result) {
        storyChanges.clear();
        storyOriginalContentArea.clear();
        storyProposedContentArea.clear();
        addStoryChangesFromResult(result);
    }

    private void addStoryChangesFromResult(String result) {
        try {
            Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(result));
            for (Object item : SimpleJson.asArray(root.get("file_changes"))) {
                Map<String, Object> change = SimpleJson.asObject(item);
                String path = SimpleJson.asString(change.get("path"));
                String content = SimpleJson.asString(change.get("content"));
                if (!path.isBlank() && !content.isBlank()) {
                    storyChanges.add(new StoryChange(
                            SimpleJson.asString(change.get("action")),
                            path,
                            content
                    ));
                }
            }
            applyAllStoryChangesButton.setDisable(storyChanges.isEmpty());
            applyStoryChangeButton.setDisable(true);
            if (!storyChanges.isEmpty()) {
                storyChangeListView.getSelectionModel().selectFirst();
                storyStatusLabel.setText(storyChanges.size() + " Aenderungsvorschlaege geladen.");
            }
        } catch (RuntimeException e) {
            applyAllStoryChangesButton.setDisable(true);
            applyStoryChangeButton.setDisable(true);
            storyStatusLabel.setText("Workflow-Antwort enthaelt keine lesbaren file_changes.");
        }
    }

    private SynthesisSelection selectDocumentsForSynthesis(List<SchreibAiWorkflowClient.StoryDocument> documents) throws IOException {
        Properties state = loadSynthesisState();
        List<SchreibAiWorkflowClient.StoryDocument> selected = new ArrayList<>();
        for (SchreibAiWorkflowClient.StoryDocument document : documents) {
            String path = document.path() == null ? "" : document.path();
            String content = document.content() == null ? "" : document.content();
            String key = synthesisKey(path);
            String hash = sha256(content);
            String previousHash = state.getProperty(key + ".hash", "");
            if (hash.equals(previousHash)) {
                continue;
            }
            String previousContent = readSynthesisSnapshot(path);
            if (previousContent.isBlank()) {
                selected.add(document);
            } else {
                String changedPassage = changedPassageWithSentenceContext(previousContent, content);
                if (!changedPassage.isBlank()) {
                    selected.add(new SchreibAiWorkflowClient.StoryDocument(path + "#changed", changedPassage));
                }
            }
        }
        return new SynthesisSelection(List.copyOf(documents), selected, state);
    }

    private Properties loadSynthesisState() throws IOException {
        Properties properties = new Properties();
        Path statePath = selectedStoryProjectDirectory().resolve(STORY_SYNTHESIS_STATE_FILE);
        if (Files.isRegularFile(statePath)) {
            try (var reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private void saveSynthesisState(List<SchreibAiWorkflowClient.StoryDocument> documents, Properties state) throws IOException {
        Path project = selectedStoryProjectDirectory();
        Path snapshotDir = project.resolve(STORY_SYNTHESIS_SNAPSHOT_DIR);
        Files.createDirectories(snapshotDir);
        for (SchreibAiWorkflowClient.StoryDocument document : documents) {
            String path = document.path() == null ? "" : document.path();
            String content = document.content() == null ? "" : document.content();
            String key = synthesisKey(path);
            String snapshotName = key + ".txt";
            state.setProperty(key + ".path", path);
            state.setProperty(key + ".hash", sha256(content));
            state.setProperty(key + ".snapshot", STORY_SYNTHESIS_SNAPSHOT_DIR + "/" + snapshotName);
            Files.writeString(snapshotDir.resolve(snapshotName), content, StandardCharsets.UTF_8);
        }
        try (var writer = Files.newBufferedWriter(project.resolve(STORY_SYNTHESIS_STATE_FILE), StandardCharsets.UTF_8)) {
            state.store(writer, "Schreib-AI synthesis source state");
        }
    }

    private String readSynthesisSnapshot(String path) throws IOException {
        String key = synthesisKey(path);
        Properties state = loadSynthesisState();
        String snapshot = state.getProperty(key + ".snapshot", "");
        if (snapshot.isBlank()) {
            return "";
        }
        Path snapshotPath = localStoryPath(snapshot);
        return Files.isRegularFile(snapshotPath) ? repairTextEncoding(Files.readString(snapshotPath, StandardCharsets.UTF_8)) : "";
    }

    private String changedPassageWithSentenceContext(String oldText, String newText) {
        List<String> oldSentences = splitSentences(oldText);
        List<String> newSentences = splitSentences(newText);
        int prefix = 0;
        while (prefix < oldSentences.size()
                && prefix < newSentences.size()
                && oldSentences.get(prefix).equals(newSentences.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldSentences.size() - prefix
                && suffix < newSentences.size() - prefix
                && oldSentences.get(oldSentences.size() - 1 - suffix).equals(newSentences.get(newSentences.size() - 1 - suffix))) {
            suffix++;
        }
        if (prefix == oldSentences.size() && prefix == newSentences.size()) {
            return "";
        }
        int start = Math.max(0, prefix - 2);
        int end = Math.min(newSentences.size(), newSentences.size() - suffix + 2);
        if (start >= end) {
            return newText;
        }
        return String.join(" ", newSentences.subList(start, end)).trim();
    }

    private List<String> splitSentences(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split("(?<=[.!?。！？])\\s+|\\R{2,}");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences.isEmpty() ? List.of(value) : sentences;
    }

    private String synthesisKey(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((path == null ? "" : path).getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    private Path storyTempDirectory() throws IOException {
        return storyTempDirectory(true);
    }

    private Path storyTempDirectory(boolean create) throws IOException {
        Path dir = selectedStoryProjectDirectory().resolve(".schreib-ai-temp").normalize();
        if (create) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    private void saveStoryTempResult(String operation, int current, int total, String result) throws IOException {
        String fileName = operation + "-" + System.currentTimeMillis() + "-block-" + current + "-of-" + total + ".json";
        Files.writeString(storyTempDirectory().resolve(fileName), result == null ? "" : result, StandardCharsets.UTF_8);
    }

    @FXML
    private void recoverLatestStoryTemp() {
        try {
            Path tempDir = storyTempDirectory();
            List<Path> files;
            try (var stream = Files.list(tempDir)) {
                files = stream.filter(path -> path.toString().endsWith(".json"))
                        .sorted(Comparator.comparing(this::lastModified).reversed())
                        .toList();
            }
            if (files.isEmpty()) {
                storyStatusLabel.setText("Keine temporaeren Schreib-AI-Dateien gefunden.");
                return;
            }
            storyChanges.clear();
            storyWorkflowResultArea.clear();
            for (Path file : files) {
                String result = Files.readString(file, StandardCharsets.UTF_8);
                storyWorkflowResultArea.appendText("\n\n--- " + file.getFileName() + " ---\n" + result);
                addStoryChangesFromResult(result);
            }
            storyStatusLabel.setText(files.size() + " temporaere Dateien geladen.");
        } catch (IOException e) {
            showError("Temporaere Schreib-AI-Dateien konnten nicht geladen werden", e);
        }
    }

    private java.nio.file.attribute.FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    public boolean hasOpenStoryTempFiles() {
        return countOpenStoryTempFiles() > 0;
    }

    private int countOpenStoryTempFiles() {
        try {
            Path tempDir = storyTempDirectory(false);
            if (!Files.isDirectory(tempDir)) {
                return 0;
            }
            try (var stream = Files.list(tempDir)) {
                return (int) stream.filter(path -> path.toString().endsWith(".json")).count();
            }
        } catch (IOException e) {
            return 0;
        }
    }

    private void deleteStoryTempFiles() {
        try {
            Path tempDir = storyTempDirectory(false);
            if (!Files.isDirectory(tempDir)) {
                return;
            }
            try (var stream = Files.list(tempDir)) {
                for (Path path : stream.filter(path -> path.toString().endsWith(".json")).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
            // Temp cleanup should never block accepted user edits.
        }
    }

    private void updateStoryTempStatus() {
        String suffix = storyTempStatusSuffix();
        if (!suffix.isBlank()) {
            storyStatusLabel.setText(suffix.trim());
        }
    }

    private String storyTempStatusSuffix() {
        int tempFiles = countOpenStoryTempFiles();
        if (tempFiles == 0) {
            return "";
        }
        return " | " + tempFiles + " Zwischenstand-Datei(en) vorhanden";
    }

    private void showStoryChangeComparison(StoryChange change) {
        try {
            Path path = localStoryPath(change.path());
            storyOriginalContentArea.setText(Files.isRegularFile(path)
                    ? repairTextEncoding(Files.readString(path, StandardCharsets.UTF_8))
                    : "");
            storyProposedContentArea.setText(normalizeProposedContent(change.content()));
            storyPathField.setText(change.path());
        } catch (IOException e) {
            storyOriginalContentArea.setText("");
            storyProposedContentArea.setText(normalizeProposedContent(change.content()));
        }
    }

    private String normalizeProposedContent(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            value = value.replaceFirst("\\s*```$", "");
        }
        return value;
    }

    private void loadStoryFile(StoryFile file) {
        storyPathField.setText(file.path());
        if (file.path().startsWith("manuscript/")) {
            storyTargetPathField.setText(file.path());
        }

        setStoryActionsDisabled(true);
        storyStatusLabel.setText("Lade " + file.path() + "...");
        String projectId = selectedStoryProjectId();
        if (projectId.isBlank()) {
            storyStatusLabel.setText("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
            setStoryActionsDisabled(false);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String content = readLocalStoryFile(file.path());
                Platform.runLater(() -> {
                    storyFileContentArea.setText(content);
                    storyStatusLabel.setText("Story-Datei geladen");
                    setStoryActionsDisabled(false);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    storyStatusLabel.setText("Story-Datei konnte nicht geladen werden");
                    setStoryActionsDisabled(false);
                    showError("Story-Datei konnte nicht geladen werden", e);
                });
            }
        });
    }

    private void setStoryActionsDisabled(boolean disabled) {
        boolean unavailable = disabled || !schreibAiClient.isConfigured();
        refreshStoryProjectsButton.setDisable(disabled);
      chooseStoryWorkspaceButton.setDisable(disabled);
      refreshStoryFilesButton.setDisable(unavailable);
      saveStoryFileButton.setDisable(disabled);
      continueFromStoryFileButton.setDisable(unavailable);
      promoteToFinalStoryButton.setDisable(unavailable);
      reviewFinalChapterButton.setDisable(unavailable);
      createStoryButton.setDisable(unavailable);
        synthesizeStoryButton.setDisable(unavailable);
        syncQdrantMemoryButton.setDisable(unavailable);
        recoverStoryTempButton.setDisable(disabled);
        analyzeStoryDocumentButton.setDisable(unavailable);
        continueStoryButton.setDisable(unavailable);
    }

    private Path selectedStoryProjectDirectory() throws IOException {
        String projectId = selectedStoryProjectId();
        if (projectId.isBlank()) {
            throw new IOException("Bitte zuerst ein Arbeitsprojekt auswaehlen.");
        }
        Path workspace = Path.of(storyWorkspaceField.getText() == null ? "" : storyWorkspaceField.getText().trim()).toAbsolutePath().normalize();
        Path project = workspace.resolve(projectId).normalize();
        if (!project.startsWith(workspace) || !Files.isDirectory(project)) {
            throw new IOException("Arbeitsprojekt nicht gefunden: " + project);
        }
        return project;
    }

    private Path localStoryPath(String relative) throws IOException {
        Path project = selectedStoryProjectDirectory();
        Path target = project.resolve(relative == null ? "" : relative).normalize();
        if (!target.startsWith(project)) {
            throw new IOException("Dateipfad verlaesst das Arbeitsprojekt: " + relative);
        }
        return target;
    }

    private List<StoryFile> listLocalStoryFiles() throws IOException {
        Path project = selectedStoryProjectDirectory();
        List<StoryFile> files = new ArrayList<>();
        try (var stream = Files.walk(project)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md") || path.toString().endsWith(".txt"))
                    .filter(path -> !project.relativize(path).toString().startsWith(".schreib-ai-temp"))
                    .filter(path -> !project.relativize(path).toString().startsWith(".schreib-ai-snapshots"))
                    .sorted(Comparator.comparing(path -> project.relativize(path).toString()))
                    .forEach(path -> files.add(toStoryFile(project, path)));
        }
        return files;
    }

    private StoryFile toStoryFile(Path project, Path path) {
        String relative = project.relativize(path).toString().replace("\\", "/");
        String layer = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "source";
        try {
            return new StoryFile(
                    relative,
                    layer,
                    path.getFileName().toString(),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant().toString()
            );
        } catch (IOException e) {
            return new StoryFile(relative, layer, path.getFileName().toString(), 0L, "");
        }
    }

    private String readLocalStoryFile(String relative) throws IOException {
        return repairTextEncoding(Files.readString(localStoryPath(relative), StandardCharsets.UTF_8));
    }

    private void writeLocalStoryFile(String relative, String content) throws IOException {
        Path path = localStoryPath(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, repairTextEncoding(content == null ? "" : content), StandardCharsets.UTF_8);
    }

    private String repairTextEncoding(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        if (!(text.contains("Ã") || text.contains("Â") || text.contains("â€"))) {
            return text;
        }
        try {
            String repaired = new String(text.getBytes(WINDOWS_1252), StandardCharsets.UTF_8);
            return mojibakeScore(repaired) < mojibakeScore(text) ? repaired : text;
        } catch (RuntimeException e) {
            return text;
        }
    }

    private int mojibakeScore(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return countOccurrences(value, "Ã") + countOccurrences(value, "Â") + countOccurrences(value, "â€");
    }

    private int countOccurrences(String value, String marker) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }

    private List<SchreibAiWorkflowClient.StoryDocument> collectLocalStoryDocuments(boolean includeSourceOnly) throws IOException {
        Path project = selectedStoryProjectDirectory();
        List<SchreibAiWorkflowClient.StoryDocument> documents = new ArrayList<>();
        try (var stream = Files.walk(project)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md") || path.toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> project.relativize(path).toString()))
                    .toList()) {
                String relative = project.relativize(path).toString().replace("\\", "/");
                if (relative.startsWith(".schreib-ai-temp/")) {
                    continue;
                }
                if (relative.startsWith(".schreib-ai-snapshots/")) {
                    continue;
                }
                if (includeSourceOnly && isGeneratedStoryLayer(relative)) {
                    continue;
                }
                documents.add(new SchreibAiWorkflowClient.StoryDocument(relative, repairTextEncoding(Files.readString(path, StandardCharsets.UTF_8))));
            }
        }
        return documents;
    }

    private List<SchreibAiWorkflowClient.StoryDocument> collectFinalizationContextDocuments(String sourcePath) throws IOException {
        List<SchreibAiWorkflowClient.StoryDocument> documents = new ArrayList<>();
        for (SchreibAiWorkflowClient.StoryDocument document : collectLocalStoryDocuments(false)) {
            String path = document.path() == null ? "" : document.path();
            if (path.equals(sourcePath)) {
                continue;
            }
            if (isFinalizationContextPath(path)) {
                documents.add(document);
            }
        }
        return documents;
    }

    private List<SchreibAiWorkflowClient.StoryDocument> collectGenerationContextDocuments() throws IOException {
        List<SchreibAiWorkflowClient.StoryDocument> documents = new ArrayList<>();
        for (SchreibAiWorkflowClient.StoryDocument document : collectLocalStoryDocuments(false)) {
            String path = document.path() == null ? "" : document.path();
            if (isAutomaticSourceDraft(path)) {
                continue;
            }
            if (path.equals("overview/open-questions.md") || path.equals("system/pending-updates.md")) {
                continue;
            }
            documents.add(document);
        }
        return documents;
    }

    private boolean isFinalizationContextPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (path.equals("overview/open-questions.md") || path.equals("system/pending-updates.md")) {
            return false;
        }
        return path.startsWith("final/")
                || path.startsWith("overview/")
                || path.startsWith("timeline/")
                || path.startsWith("characters/")
                || path.startsWith("world/")
                || path.equals("system/consistency-report.md");
    }

    private boolean isAutomaticSourceDraft(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        if (path.startsWith("manuscript/")
                || path.startsWith("chapters/")
                || path.startsWith("final/")
                || path.startsWith("overview/")
                || path.startsWith("timeline/")
                || path.startsWith("characters/")
                || path.startsWith("world/")
                || path.equals("system/consistency-report.md")) {
            return false;
        }
        return path.endsWith(".txt") || !path.contains("/");
    }

    private boolean isGeneratedStoryLayer(String relative) {
        return relative.startsWith("manuscript/")
                || relative.startsWith("chapters/")
                || relative.startsWith("final/")
                || relative.startsWith("overview/")
                || relative.startsWith("timeline/")
                || relative.startsWith("characters/")
                || relative.startsWith("world/")
                || relative.startsWith("system/");
    }

    private String bootstrapLocalStoryProject() throws IOException {
        String title = textOr(storyTitleField.getText(), selectedStoryProjectId());
        String genre = textOr(storyGenreField.getText(), "Offen");
        String protagonist = textOr(storyProtagonistField.getText(), "Hauptfigur");
        String setting = textOr(storySettingField.getText(), "Offen");
        String tone = textOr(storyToneField.getText(), "Offen");
        String premise = textOr(storyPremiseArea.getText(), "Noch offen.");
        String conflict = textOr(storyConflictArea.getText(), "Noch offen.");

        writeLocalStoryFile("overview/story-overview.md", "# " + title + "\n\n"
                + "## Genre\n\n" + genre + "\n\n"
                + "## Praemisse\n\n" + premise + "\n\n"
                + "## Zentraler Konflikt\n\n" + conflict + "\n\n"
                + "## Ton\n\n" + tone + "\n\n"
                + "## Hauptfigur\n\n- " + protagonist + "\n\n"
                + "## Startort\n\n- " + setting + "\n");
        writeLocalStoryFile("timeline/master-timeline.md", "# Master-Timeline\n\n- Start: " + premise + "\n");
        writeLocalStoryFile("timeline/character-timeline.md", "# Figuren-Timeline\n\n## " + protagonist + "\n\n- Startort: " + setting + "\n");
        writeLocalStoryFile("manuscript/chapter-01.md", "# Kapitel 1\n\n");
        writeLocalStoryFile("system/consistency-report.md", "# Konsistenzbericht\n\n- Projekt lokal angelegt. KI-Synthese kann die Schichten verfeinern.\n");
        return "{\"ok\":true,\"created_local_layers\":true,\"projectId\":\"" + selectedStoryProjectId() + "\"}";
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String selectedStoryProjectId() {
        StoryProject project = storyProjectComboBox.getSelectionModel().getSelectedItem();
        return project == null ? "" : project.id();
    }

    private String selectedOutputLanguage() {
        String value = storyOutputLanguageComboBox.getSelectionModel().getSelectedItem();
        if (value == null || value.isBlank()) {
            return "English";
        }
        if (value.startsWith("Deutsch")) {
            return "Deutsch";
        }
        return "English";
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


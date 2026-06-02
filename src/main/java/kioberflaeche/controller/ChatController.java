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
import kioberflaeche.admin.ChatExecution;
import kioberflaeche.admin.N8nChatAdminClient;
import kioberflaeche.ai.AiClient;
import kioberflaeche.model.ChatMessage;
import kioberflaeche.storage.ChatSession;
import kioberflaeche.storage.ChatStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class ChatController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final AiClient aiClient;
    private final ChatStore chatStore;
    private final N8nChatAdminClient chatAdminClient;
    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private final ObservableList<ChatExecution> remoteExecutions = FXCollections.observableArrayList();

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
    private ListView<ChatExecution> remoteChatListView;
    @FXML
    private Label remotePreviewLabel;
    @FXML
    private Button refreshRemoteChatsButton;
    @FXML
    private Button exportRemoteChatButton;
    @FXML
    private Button deleteRemoteChatButton;
    @FXML
    private Label remoteStatusLabel;

    private ChatSession activeSession;

    public ChatController(AiClient aiClient, ChatStore chatStore, N8nChatAdminClient chatAdminClient) {
        this.aiClient = aiClient;
        this.chatStore = chatStore;
        this.chatAdminClient = chatAdminClient;
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

        remoteChatListView.setItems(remoteExecutions);
        remoteChatListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(ChatExecution execution, boolean empty) {
                super.updateItem(execution, empty);
                setText(empty || execution == null ? null : execution.displayTitle());
            }
        });
        remoteChatListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, execution) -> {
            boolean selected = execution != null;
            exportRemoteChatButton.setDisable(!selected);
            deleteRemoteChatButton.setDisable(!selected);
            remotePreviewLabel.setText(selected ? execution.preview() : "Keine alte Ausfuehrung ausgewaehlt.");
        });
        boolean adminConfigured = chatAdminClient.isConfigured();
        refreshRemoteChatsButton.setDisable(!adminConfigured);
        remoteStatusLabel.setText(adminConfigured ? "n8n-Verwaltung bereit" : "n8n-Token oder URL fehlt");

        loadSessions();
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
        aiClient.ask(historyBeforeAnswer, userText)
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
    private void refreshRemoteChats() {
        if (!chatAdminClient.isConfigured()) {
            remoteStatusLabel.setText("n8n-Token oder URL fehlt");
            return;
        }

        setRemoteActionsDisabled(true);
        remoteStatusLabel.setText("Lade n8n-Chats...");
        Thread.startVirtualThread(() -> {
            try {
                List<ChatExecution> executions = chatAdminClient.listChats();
                Platform.runLater(() -> {
                    remoteExecutions.setAll(executions);
                    remoteStatusLabel.setText(executions.size() + " alte n8n-Ausfuehrungen gefunden");
                    setRemoteActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    remoteStatusLabel.setText("n8n-Verwaltung nicht erreichbar");
                    setRemoteActionsDisabled(false);
                    showError("n8n-Chats konnten nicht geladen werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void exportRemoteChat() {
        ChatExecution execution = remoteChatListView.getSelectionModel().getSelectedItem();
        if (execution == null) {
            return;
        }

        setRemoteActionsDisabled(true);
        remoteStatusLabel.setText("Exportiere n8n-Chat " + execution.id() + "...");
        Thread.startVirtualThread(() -> {
            try {
                chatAdminClient.exportChat(execution.id());
                Platform.runLater(() -> {
                    remoteStatusLabel.setText("Export in der VM wurde erstellt");
                    setRemoteActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    remoteStatusLabel.setText("Export fehlgeschlagen");
                    setRemoteActionsDisabled(false);
                    showError("n8n-Chat konnte nicht exportiert werden", e);
                });
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @FXML
    private void deleteRemoteChat() {
        ChatExecution execution = remoteChatListView.getSelectionModel().getSelectedItem();
        if (execution == null) {
            return;
        }

        setRemoteActionsDisabled(true);
        remoteStatusLabel.setText("Loesche n8n-Chat " + execution.id() + "...");
        Thread.startVirtualThread(() -> {
            try {
                chatAdminClient.deleteChat(execution.id());
                Platform.runLater(() -> {
                    remoteExecutions.remove(execution);
                    remoteStatusLabel.setText("n8n-Chat wurde geloescht");
                    setRemoteActionsDisabled(false);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    remoteStatusLabel.setText("Loeschen fehlgeschlagen");
                    setRemoteActionsDisabled(false);
                    showError("n8n-Chat konnte nicht geloescht werden", e);
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
        statusLabel.setText(sending ? "KI antwortet..." : "Bereit");
    }

    private void setRemoteActionsDisabled(boolean disabled) {
        refreshRemoteChatsButton.setDisable(disabled || !chatAdminClient.isConfigured());
        ChatExecution selected = remoteChatListView.getSelectionModel().getSelectedItem();
        exportRemoteChatButton.setDisable(disabled || selected == null);
        deleteRemoteChatButton.setDisable(disabled || selected == null);
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

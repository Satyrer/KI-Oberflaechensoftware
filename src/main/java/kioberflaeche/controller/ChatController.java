package kioberflaeche.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import kioberflaeche.ai.AiClient;
import kioberflaeche.model.ChatMessage;
import kioberflaeche.storage.ChatSession;
import kioberflaeche.storage.ChatStore;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ChatController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final AiClient aiClient;
    private final ChatStore chatStore;
    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();

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

    private ChatSession activeSession;

    public ChatController(AiClient aiClient, ChatStore chatStore) {
        this.aiClient = aiClient;
        this.chatStore = chatStore;
    }

    @FXML
    private void initialize() {
        chatListView.setItems(sessions);
        chatListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(ChatSession session, boolean empty) {
                super.updateItem(session, empty);
                setText(empty || session == null ? null : session.title());
            }
        });
        chatListView.getSelectionModel().selectedItemProperty().addListener((observable, oldSession, newSession) -> {
            if (newSession != null) {
                activeSession = newSession;
                renderMessages();
            }
        });

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

    private void saveSession(ChatSession session) {
        try {
            chatStore.save(session);
        } catch (IOException e) {
            showError("Chat konnte nicht gespeichert werden", e);
        }
    }

    private void showError(String title, Exception exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Fehler");
        alert.setHeaderText(title);
        alert.setContentText(exception.getMessage());
        alert.showAndWait();
    }
}

package kioberflaeche.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import kioberflaeche.admin.N8nWebChatClient;
import kioberflaeche.ai.AiClient;
import kioberflaeche.controller.ChatController;
import kioberflaeche.story.SchreibAiWorkflowClient;
import kioberflaeche.storage.ChatStore;

import java.io.IOException;

public class ChatView {
    private final Parent view;
    private ChatController controller;

    public ChatView(
            AiClient aiClient,
            ChatStore chatStore,
            N8nWebChatClient webChatClient,
            SchreibAiWorkflowClient schreibAiClient
    ) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat_window.fxml"));
            loader.setControllerFactory(type -> {
                if (type == ChatController.class) {
                    controller = new ChatController(aiClient, chatStore, webChatClient, schreibAiClient);
                    return controller;
                }
                try {
                    return type.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Controller konnte nicht erstellt werden: " + type.getName(), e);
                }
            });
            view = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Chat-Fenster konnte nicht geladen werden.", e);
        }
    }

    public Parent getView() {
        return view;
    }

    public ChatController getController() {
        return controller;
    }
}

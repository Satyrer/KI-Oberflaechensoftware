package kioberflaeche.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import kioberflaeche.admin.N8nChatAdminClient;
import kioberflaeche.ai.AiClient;
import kioberflaeche.controller.ChatController;
import kioberflaeche.storage.ChatStore;

import java.io.IOException;

public class ChatView {
    private final Parent view;

    public ChatView(AiClient aiClient, ChatStore chatStore, N8nChatAdminClient chatAdminClient) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat_window.fxml"));
            loader.setControllerFactory(type -> {
                if (type == ChatController.class) {
                    return new ChatController(aiClient, chatStore, chatAdminClient);
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
}

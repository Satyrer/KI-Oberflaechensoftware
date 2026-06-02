package kioberflaeche;

import kioberflaeche.ai.AiClient;
import kioberflaeche.ai.HttpAiClient;
import kioberflaeche.config.AppConfig;
import kioberflaeche.storage.ChatStore;
import kioberflaeche.ui.ChatWindow;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        AiClient aiClient = new HttpAiClient(config.aiEndpoint(), config.aiApiKey(), config.aiModel());
        ChatStore chatStore = new ChatStore(Path.of(config.chatDirectory()));

        SwingUtilities.invokeLater(() -> {
            ChatWindow window = new ChatWindow(aiClient, chatStore);
            window.setVisible(true);
        });
    }
}

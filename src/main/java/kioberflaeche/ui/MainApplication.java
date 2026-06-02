package kioberflaeche.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import kioberflaeche.admin.N8nChatAdminClient;
import kioberflaeche.ai.AiClient;
import kioberflaeche.ai.HttpAiClient;
import kioberflaeche.config.AppConfig;
import kioberflaeche.storage.ChatStore;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

public class MainApplication extends Application {
    private static Stage primaryApplicationStage;
    private static boolean applicationClosing;

    @Override
    public void start(Stage primaryStage) {
        AppConfig config = AppConfig.load();
        AiClient aiClient = new HttpAiClient(
                config.aiEndpoint(),
                config.aiApiKey(),
                config.aiModel(),
                config.aiTimeoutSeconds()
        );
        ChatStore chatStore = new ChatStore(Path.of(config.chatDirectory()));
        N8nChatAdminClient chatAdminClient = new N8nChatAdminClient(config.n8nAdminBaseUrl(), config.n8nAdminToken());
        ChatView chatView = new ChatView(aiClient, chatStore, chatAdminClient);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(Math.max(980, screenBounds.getWidth() * 0.85), screenBounds.getWidth());
        double height = Math.min(Math.max(680, screenBounds.getHeight() * 0.85), screenBounds.getHeight());

        Scene scene = new Scene(chatView.getView(), width, height);
        scene.getStylesheets().add(getClass().getResource("/css/chat.css").toExternalForm());

        primaryStage.setTitle("KI Oberflaechensoftware");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(920);
        primaryStage.setMinHeight(620);
        primaryStage.setX(screenBounds.getMinX() + (screenBounds.getWidth() - width) / 2);
        primaryStage.setY(screenBounds.getMinY() + (screenBounds.getHeight() - height) / 2);
        primaryApplicationStage = primaryStage;
        primaryStage.setOnCloseRequest(event -> {
            if (!applicationClosing) {
                event.consume();
                closeApplication();
            }
        });
        primaryStage.show();
    }

    public static void closeApplication() {
        applicationClosing = true;
        List<Window> windows = new ArrayList<>(Window.getWindows());
        for (Window window : windows) {
            if (window != primaryApplicationStage && window instanceof Stage stage) {
                stage.close();
            }
        }
        if (primaryApplicationStage != null) {
            primaryApplicationStage.close();
        }
        Platform.exit();
    }

    @Override
    public void stop() {
        if (!applicationClosing) {
            applicationClosing = true;
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage stage) {
                    stage.close();
                }
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

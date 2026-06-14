package kioberflaeche.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import kioberflaeche.admin.N8nWebChatClient;
import kioberflaeche.ai.AiClient;
import kioberflaeche.ai.HttpAiClient;
import kioberflaeche.ai.N8nChatWebhookClient;
import kioberflaeche.config.AppConfig;
import kioberflaeche.controller.ChatController;
import kioberflaeche.story.SchreibAiWorkflowClient;
import kioberflaeche.storage.ChatStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.nio.file.Path;

public class MainApplication extends Application {
    private static Stage primaryApplicationStage;
    private static ChatController activeChatController;
    private static boolean applicationClosing;

    @Override
    public void start(Stage primaryStage) {
        AppConfig config = AppConfig.load();
        AiClient aiClient = createAiClient(config);
        ChatStore chatStore = new ChatStore(Path.of(config.chatDirectory()));
        N8nWebChatClient webChatClient = new N8nWebChatClient(
                config.n8nWebBaseUrl(),
                config.n8nWebEmail(),
                config.n8nWebPassword(),
                config.n8nWebTimeoutSeconds()
        );
        SchreibAiWorkflowClient schreibAiClient = new SchreibAiWorkflowClient(
                config.n8nSchreibAiBaseUrl(),
                config.n8nSchreibAiTimeoutSeconds()
        );
        ChatView chatView = new ChatView(aiClient, chatStore, webChatClient, schreibAiClient);
        activeChatController = chatView.getController();

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

    private AiClient createAiClient(AppConfig config) {
        if ("n8n".equalsIgnoreCase(config.aiProvider())) {
            return new N8nChatWebhookClient(
                    config.n8nChatWebhookUrl(),
                    config.n8nChatWebhookAuthorization(),
                    config.aiTimeoutSeconds()
            );
        }

        return new HttpAiClient(
                config.aiEndpoint(),
                config.aiApiKey(),
                config.aiModel(),
                config.aiTimeoutSeconds()
        );
    }

    public static void closeApplication() {
        if (!confirmCloseWithStoryTempFiles()) {
            return;
        }
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

    private static boolean confirmCloseWithStoryTempFiles() {
        if (applicationClosing || activeChatController == null || !activeChatController.hasOpenStoryTempFiles()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (primaryApplicationStage != null) {
            alert.initOwner(primaryApplicationStage);
        }
        alert.setTitle("Zwischenstaende vorhanden");
        alert.setHeaderText("Es gibt noch gespeicherte Schreib-AI-Zwischenstaende.");
        alert.setContentText("Die Dateien bleiben im Projektordner unter .schreib-ai-temp erhalten und koennen beim naechsten Start geladen werden. Trotzdem beenden?");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
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

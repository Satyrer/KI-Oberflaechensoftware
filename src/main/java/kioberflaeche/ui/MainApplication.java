package kioberflaeche.ui;

import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import kioberflaeche.ai.AiClient;
import kioberflaeche.ai.HttpAiClient;
import kioberflaeche.config.AppConfig;
import kioberflaeche.storage.ChatStore;

import java.nio.file.Path;

public class MainApplication extends Application {
    @Override
    public void start(Stage primaryStage) {
        AppConfig config = AppConfig.load();
        AiClient aiClient = new HttpAiClient(config.aiEndpoint(), config.aiApiKey(), config.aiModel());
        ChatStore chatStore = new ChatStore(Path.of(config.chatDirectory()));
        ChatView chatView = new ChatView(aiClient, chatStore);

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
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
